package com.jevforex.app.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevforex.app.config.RiskSettingsHolder;
import com.jevforex.app.live.ScannerService;
import com.jevforex.core.Instrument;
import com.jevforex.features.scan.Scanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cassino (pedido do operador em 23/10/2026; substitui a alta frequência). Por símbolo, o operador escolhe alvo e stop
 * em US$, lote, lado (compra/venda), tentativas e, se quiser, saída por tempo. Ao ligar, a ordem sai NA HORA; fechou no
 * stop (ou por tempo), abre de novo; bateu o alvo, a linha termina; acabaram as tentativas, também.
 *
 * <p>Global (opcional): lucro e/ou perda da rodada (fechadas + abertas do Cassino). Atingiu, fecha todas as posições do
 * Cassino; com rodadas sobrando, recomeça todas as linhas ligadas com as tentativas zeradas. Se as linhas terminarem
 * sozinhas, o Cassino para (não recomeça).</p>
 *
 * <p>Ignora TODAS as travas de risco por ordem ({@link OrderService#CASINO_WAIVED}); valem as técnicas, o BLOCK e a
 * perda do dia/semana, que para todas as linhas. Recusa ou falha numa tentativa para a linha (falhar fechado). As
 * posições do Cassino ficam fora do trailing, da proteção de lucro e da prorrogação com lucro: saem só no alvo, no stop
 * ou no tempo.</p>
 */
@Service
@Profile("!cli")
public class CasinoService {

    private static final Logger log = LoggerFactory.getLogger(CasinoService.class);
    private static final String KEY = "casino";
    /** Ordem executada, sem posição aberta e sem o fechamento reportado por este tempo: algo deu errado. */
    private static final Duration MISSING_MAX = Duration.ofMinutes(3);

    private final TradeRepository repo;
    private final OrderService orders;
    private final RiskSettingsHolder riskHolder;
    private final ScannerService scanner;
    private final ObjectProvider<JdbcClient> jdbc;
    private final ObjectMapper mapper;
    private final Map<Long, Instant> missingSince = new HashMap<>();

    public CasinoService(TradeRepository repo, OrderService orders, RiskSettingsHolder riskHolder, ScannerService scanner,
                         ObjectProvider<JdbcClient> jdbc, ObjectMapper mapper) {
        this.repo = repo;
        this.orders = orders;
        this.riskHolder = riskHolder;
        this.scanner = scanner;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Uma linha da tabela.
     *
     * @param lot               null = o lote mínimo do símbolo
     * @param closeAfterMinutes null = sem saída por tempo
     * @param on                faz parte da sessão (o operador ligou e não desligou): recomeça a cada rodada
     * @param used              tentativas usadas na rodada
     * @param status            OFF | RUNNING | TARGET | EXHAUSTED | STOPPED | GLOBAL
     */
    public record Line(String symbol, String market, String side, Double lot, double takeUsd, double stopUsd,
                       int attempts, Integer closeAfterMinutes, boolean on, int used, String status, String message,
                       Instant startedAt) {

        Line with(boolean on, int used, String status, String message) {
            return new Line(symbol, market, side, lot, takeUsd, stopUsd, attempts, closeAfterMinutes, on, used, status,
                    message, startedAt);
        }

        boolean running() {
            return "RUNNING".equals(status);
        }
    }

    /** Lucro e perda da rodada em US$ (null = desligado) e quantas rodadas. */
    public record Global(Double takeUsd, Double lossUsd, int rounds) {
    }

    /**
     * @param session      quando a sessão começou (epoch ms); 0 = nenhuma
     * @param closing      o global foi atingido e as posições da rodada estão sendo fechadas
     */
    public record State(long session, int round, boolean closing, Global global, Map<String, Line> lines,
                        String lastEvent, Instant roundStartedAt) {
    }

    // ------------------------------------------------------------------ comandos da página

    public synchronized State state() {
        return repo.setting(KEY).map(this::parse).orElse(empty());
    }

    /** Liga uma linha e abre a 1ª ordem na hora. */
    public synchronized Map<String, Object> start(String symbol, String side, Double lot, double takeUsd, double stopUsd,
                                                  int attempts, Integer closeAfterMinutes) {
        Instrument inst = orders.instrument(symbol);
        if (inst == null) throw new IllegalArgumentException("Símbolo sem especificação do MT5: " + symbol);
        if (!"BUY".equals(side) && !"SELL".equals(side)) throw new IllegalArgumentException("Lado deve ser BUY ou SELL");
        if (!(takeUsd > 0)) throw new IllegalArgumentException("O alvo em US$ precisa ser > 0");
        if (!(stopUsd > 0)) throw new IllegalArgumentException("O stop em US$ precisa ser > 0 (o EA não abre sem stop)");
        if (attempts < 1) throw new IllegalArgumentException("Tentativas: pelo menos 1");
        if (closeAfterMinutes != null && closeAfterMinutes < 1) closeAfterMinutes = null;
        if (lot != null && (!(lot > 0) || lot > riskHolder.get().orders().maxLot() + 1e-9)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Lote %.2f fora do permitido (até %.2f, trading.risk.orders.max-lot)", lot, riskHolder.get().orders().maxLot()));
        }
        State s = state();
        if (s.closing()) throw new IllegalArgumentException("O global foi atingido e as posições estão fechando: espere terminar");
        Map<String, Line> lines = new LinkedHashMap<>(s.lines());
        boolean active = lines.values().stream().anyMatch(Line::running);
        long session = s.session();
        int round = s.round();
        Instant roundStart = s.roundStartedAt();
        if (!active || session == 0) {
            // nada rodando: sessão nova (as linhas de antes deixam de recomeçar nas rodadas)
            session = System.currentTimeMillis();
            round = 1;
            roundStart = Instant.now();
            lines.replaceAll((k, l) -> l.with(false, l.used(), l.running() ? "OFF" : l.status(), l.message()));
        }
        Line line = new Line(symbol, inst.market().code(), side, lot, takeUsd, stopUsd, attempts, closeAfterMinutes, true,
                0, "RUNNING", "ligada", Instant.now());
        lines.put(symbol, line);
        State next = new State(session, round, false, s.global(), lines, s.lastEvent(), roundStart);
        save(next);
        log.info("Cassino: {} LIGADO — {} lote {} · alvo US$ {} · stop US$ {} · {} tentativa(s){}", symbol, side,
                lot == null ? "mínimo" : lot, takeUsd, stopUsd, attempts,
                closeAfterMinutes == null ? "" : " · sai em " + closeAfterMinutes + " min");
        step(symbol);   // a 1ª ordem sai agora
        return Map.of("line", state().lines().get(symbol));
    }

    /** Desliga uma linha (a posição aberta continua com SL/TP no servidor; feche na página Operar se quiser). */
    public synchronized State stop(String symbol, String why) {
        State s = state();
        Line l = s.lines().get(symbol);
        if (l == null) return s;
        Map<String, Line> lines = new LinkedHashMap<>(s.lines());
        lines.put(symbol, l.with(false, l.used(), "OFF", why));
        State next = new State(s.session(), s.round(), s.closing(), s.global(), lines, s.lastEvent(), s.roundStartedAt());
        save(next);
        log.info("Cassino: {} desligado ({})", symbol, why);
        return next;
    }

    public synchronized State stopAll(String why, boolean closePositions) {
        State s = state();
        Map<String, Line> lines = new LinkedHashMap<>(s.lines());
        lines.replaceAll((k, l) -> l.on() || l.running() ? l.with(false, l.used(), l.running() ? "OFF" : l.status(), why) : l);
        int closed = closePositions ? closeOpen(s.session(), null) : 0;
        State next = new State(s.session(), s.round(), false, s.global(), lines,
                why + (closePositions ? " · " + closed + " posição(ões) mandada(s) fechar" : ""), s.roundStartedAt());
        save(next);
        log.info("Cassino: tudo parado ({}){}", why, closePositions ? ", " + closed + " posições fechando" : "");
        return next;
    }

    public synchronized State setGlobal(Double takeUsd, Double lossUsd, int rounds) {
        if (takeUsd != null && !(takeUsd > 0)) takeUsd = null;
        if (lossUsd != null && !(lossUsd > 0)) lossUsd = null;
        if (rounds < 1) throw new IllegalArgumentException("Rodadas: pelo menos 1");
        State s = state();
        State next = new State(s.session(), s.round(), s.closing(), new Global(takeUsd, lossUsd, rounds), s.lines(),
                s.lastEvent(), s.roundStartedAt());
        save(next);
        log.info("Cassino: global — lucro {} · perda {} · {} rodada(s)", takeUsd == null ? "desligado" : "US$ " + takeUsd,
                lossUsd == null ? "desligada" : "US$ " + lossUsd, rounds);
        return next;
    }

    // ------------------------------------------------------------------ o laço

    @Scheduled(initialDelayString = "PT40S", fixedDelayString = "PT3S")
    public synchronized void tick() {
        State s = state();
        if (s.session() == 0) return;
        boolean anyRunning = s.lines().values().stream().anyMatch(Line::running);
        if (!anyRunning && !s.closing() && openEntries(s.session()) == 0) return;
        try {
            settle(s.session());
            if (s.closing()) {
                finishClosing(s);
                return;
            }
            if (globalCheck(s)) return;
            for (Line l : s.lines().values()) if (l.running()) step(l.symbol());
        } catch (RuntimeException e) {
            log.warn("Cassino: {}", e.toString());
        }
    }

    /** O que fazer numa linha ligada, pela última tentativa da rodada. */
    private void step(String symbol) {
        State s = state();
        Line l = s.lines().get(symbol);
        if (l == null || !l.running() || s.closing()) return;
        Entry last = lastEntry(s.session(), s.round(), symbol, l.startedAt());
        Decision d = decide(l, last);
        switch (d.action()) {
            case WAIT -> { }
            case SUBMIT -> submit(s, l);
            case FINISH -> update(symbol, l.with(l.on(), l.used(), d.status(), d.message()));
        }
    }

    enum Action { WAIT, SUBMIT, FINISH }

    record Decision(Action action, String status, String message) {
    }

    /** A última tentativa de uma linha: status (SENT, REFUSED, TARGET, CLOSED, FAILED) e o resultado em US$. */
    record Entry(long id, int attempt, String status, Double resultUsd, String message) {
    }

    /** Puro (testável): continua, espera ou termina. */
    static Decision decide(Line l, Entry last) {
        if (last == null) return new Decision(Action.SUBMIT, null, null);
        return switch (last.status()) {
            case "SENT" -> new Decision(Action.WAIT, null, null);
            case "TARGET" -> new Decision(Action.FINISH, "TARGET", String.format(Locale.ROOT,
                    "alvo na tentativa %d de %d (US$ %+.2f)", last.attempt(), l.attempts(), nz(last.resultUsd())));
            case "CLOSED" -> l.used() < l.attempts() ? new Decision(Action.SUBMIT, null, null)
                    : new Decision(Action.FINISH, "EXHAUSTED", String.format(Locale.ROOT,
                    "%d tentativa(s) sem o alvo (a última: US$ %+.2f)", l.attempts(), nz(last.resultUsd())));
            default -> new Decision(Action.FINISH, "STOPPED", last.message());   // REFUSED, FAILED: falhar fechado
        };
    }

    private void submit(State s, Line l) {
        Instrument inst = orders.instrument(l.symbol());
        Double lot = l.lot() != null ? l.lot() : inst == null ? null : inst.minLot();
        int attempt = l.used() + 1;
        OrderService.Submit r = orders.submit(l.symbol(), l.side(), lot, OrderService.Roulette.casino(
                l.closeAfterMinutes() == null ? 0 : l.closeAfterMinutes(), l.takeUsd(), l.stopUsd()));
        if (r.orderId() != null) {
            insertEntry(s, l, attempt, lot, r.orderId(), "SENT", String.format(Locale.ROOT, "%s %.2f · alvo US$ %.2f · stop US$ %.2f",
                    l.side(), r.preview().lot(), l.takeUsd(), l.stopUsd()));
            update(l.symbol(), l.with(l.on(), attempt, "RUNNING", String.format(Locale.ROOT, "tentativa %d de %d enviada",
                    attempt, l.attempts())));
            log.info("Cassino: {} tentativa {}/{} — {} {} (ordem {})", l.symbol(), attempt, l.attempts(), l.side(),
                    r.preview().lot(), r.orderId());
            return;
        }
        String failed = r.preview().checks().stream().filter(x -> !x.ok())
                .map(x -> x.gate() + " (" + x.value() + ")").collect(Collectors.joining("; "));
        insertEntry(s, l, attempt, lot, null, "REFUSED", failed);
        update(l.symbol(), l.with(l.on(), l.used(), "STOPPED", "recusada: " + failed));
        log.info("Cassino: {} recusada — {}", l.symbol(), failed);
        if (r.preview().checks().stream().anyMatch(x -> !x.ok() && x.gate().startsWith("Perda d"))) {
            stopAll("disjuntor de perda: " + failed, false);
        }
    }

    // ------------------------------------------------------------------ resultado de cada tentativa

    /** Fecha as contas das tentativas enviadas: alvo, fechada (stop/tempo/manual) ou falha. */
    private void settle(long session) {
        Set<Long> open = repo.ownPositions().stream().map(TradeRepository.Position::ticket).collect(Collectors.toSet());
        List<Map<String, Object>> rows = db().sql("""
                        SELECT e.id, o.status, o.ticket, o.message,
                               (SELECT sum(d.profit + d.swap + d.commission) FROM ea_deal d WHERE d.position_ticket = o.ticket) AS result,
                               (SELECT d.reason FROM ea_deal d WHERE d.position_ticket = o.ticket ORDER BY d.closed_at DESC LIMIT 1) AS reason
                          FROM casino_entry e JOIN order_request o ON o.id = e.order_id
                         WHERE e.session_id = :s AND e.status = 'SENT'
                        """).param("s", session).query().listOfRows();
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("id")).longValue();
            String st = (String) r.get("status");
            Long ticket = r.get("ticket") == null ? null : ((Number) r.get("ticket")).longValue();
            if ("PENDING".equals(st) || "SENT".equals(st)) continue;
            if ("SHADOW".equals(st)) {
                finishEntry(id, "FAILED", null, "modo SHADOW: a ordem não vai ao MT5");
            } else if (!"FILLED".equals(st)) {
                finishEntry(id, "FAILED", null, "ordem " + st + (r.get("message") == null ? "" : ": " + r.get("message")));
            } else if (ticket != null && open.contains(ticket)) {
                missingSince.remove(id);
            } else if (r.get("result") != null) {
                double result = ((Number) r.get("result")).doubleValue();
                boolean target = "TP".equals(r.get("reason"));
                finishEntry(id, target ? "TARGET" : "CLOSED", result, String.format(Locale.ROOT, "fechou %s: US$ %+.2f",
                        reasonText((String) r.get("reason")), result));
                missingSince.remove(id);
            } else {
                Instant since = missingSince.computeIfAbsent(id, k -> Instant.now());
                if (since.plus(MISSING_MAX).isBefore(Instant.now())) {
                    finishEntry(id, "FAILED", null, "a posição sumiu sem o fechamento reportado pelo EA");
                    missingSince.remove(id);
                }
            }
        }
    }

    private static String reasonText(String reason) {
        return switch (reason == null ? "" : reason) {
            case "TP" -> "no alvo";
            case "SL" -> "no stop";
            case "EA" -> "pelo sistema (tempo ou global)";
            case "MANUAL" -> "à mão";
            case "STOP_OUT" -> "por stop out";
            default -> reason == null ? "" : reason;
        };
    }

    // ------------------------------------------------------------------ global

    /** Resultado da rodada: fechadas + abertas (lucro flutuante do MT5), em US$. */
    double roundResult(long session, int round) {
        Map<Long, Double> floating = new HashMap<>();
        for (TradeRepository.Position p : repo.ownPositions()) floating.put(p.ticket(), p.profit() == null ? 0 : p.profit());
        double sum = 0;
        for (Map<String, Object> r : db().sql("""
                        SELECT e.status, e.result_usd, o.ticket FROM casino_entry e JOIN order_request o ON o.id = e.order_id
                         WHERE e.session_id = :s AND e.round = :r
                        """).param("s", session).param("r", round).query().listOfRows()) {
            if (r.get("result_usd") != null) sum += ((Number) r.get("result_usd")).doubleValue();
            else if (r.get("ticket") != null) sum += floating.getOrDefault(((Number) r.get("ticket")).longValue(), 0.0);
        }
        return sum;
    }

    /** Puro (testável): o global foi atingido? null = não; senão o motivo. */
    static String globalHit(Global g, double result) {
        if (g == null) return null;
        if (g.takeUsd() != null && result >= g.takeUsd() - 1e-9) {
            return String.format(Locale.ROOT, "lucro global atingido (US$ %+.2f ≥ %.2f)", result, g.takeUsd());
        }
        if (g.lossUsd() != null && result <= -g.lossUsd() + 1e-9) {
            return String.format(Locale.ROOT, "perda global atingida (US$ %+.2f ≤ −%.2f)", result, g.lossUsd());
        }
        return null;
    }

    /** @return true = o global foi atingido agora (as posições da rodada começaram a fechar) */
    private boolean globalCheck(State s) {
        if (s.global() == null || (s.global().takeUsd() == null && s.global().lossUsd() == null)) return false;
        if (s.lines().values().stream().noneMatch(Line::running)) return false;
        double result = roundResult(s.session(), s.round());
        String why = globalHit(s.global(), result);
        if (why == null) return false;
        int closed = closeOpen(s.session(), s.round());
        Map<String, Line> lines = new LinkedHashMap<>(s.lines());
        lines.replaceAll((k, l) -> l.running() ? l.with(l.on(), l.used(), "GLOBAL", why) : l);
        String event = String.format(Locale.ROOT, "rodada %d de %d: %s · %d posição(ões) fechando", s.round(),
                s.global().rounds(), why, closed);
        save(new State(s.session(), s.round(), true, s.global(), lines, event, s.roundStartedAt()));
        log.info("Cassino: {}", event);
        return true;
    }

    /** Fechando a rodada: manda fechar o que ainda abrir; tudo fechado → próxima rodada ou fim. */
    private void finishClosing(State s) {
        closeOpen(s.session(), s.round());
        if (openEntries(s.session()) > 0) return;   // ainda há ordem pendente ou posição aberta
        Map<String, Line> lines = new LinkedHashMap<>(s.lines());
        int rounds = s.global() == null ? 1 : s.global().rounds();
        if (s.round() < rounds) {
            int next = s.round() + 1;
            lines.replaceAll((k, l) -> l.on() ? l.with(true, 0, "RUNNING", "rodada " + next + " de " + rounds) : l);
            save(new State(s.session(), next, false, s.global(), lines, s.lastEvent() + " · rodada " + next + " começou",
                    Instant.now()));
            log.info("Cassino: rodada {} de {} começou", next, rounds);
        } else {
            lines.replaceAll((k, l) -> l.on() ? l.with(false, l.used(), "GLOBAL", "fim das " + rounds + " rodada(s)") : l);
            save(new State(s.session(), s.round(), false, s.global(), lines, s.lastEvent() + " · fim das rodadas",
                    s.roundStartedAt()));
            log.info("Cassino: fim das {} rodada(s)", rounds);
        }
    }

    /** Manda fechar as posições abertas do Cassino (da rodada, ou da sessão inteira com round null). */
    private int closeOpen(long session, Integer round) {
        if (session == 0) return 0;
        Set<Long> open = repo.ownPositions().stream().map(TradeRepository.Position::ticket).collect(Collectors.toSet());
        int n = 0;
        for (Long ticket : db().sql("""
                        SELECT o.ticket FROM casino_entry e JOIN order_request o ON o.id = e.order_id
                         WHERE e.session_id = :s AND (CAST(:r AS INT) IS NULL OR e.round = :r) AND o.ticket IS NOT NULL
                           AND NOT EXISTS (SELECT 1 FROM order_request c WHERE c.action = 'CLOSE' AND c.ticket = o.ticket
                                            AND (c.status = 'FILLED' OR (c.status IN ('PENDING', 'SENT') AND c.expires_at > now())))
                        """).param("s", session).param("r", round, Types.INTEGER).query(Long.class).list()) {
            if (!open.contains(ticket)) continue;
            try {
                orders.close(ticket, "Cassino: global");
                n++;
            } catch (RuntimeException e) {
                log.warn("Cassino: não consegui fechar a posição {}: {}", ticket, e.getMessage());
            }
        }
        return n;
    }

    /** Tentativas enviadas ainda sem resultado (ordem pendente ou posição aberta). */
    private long openEntries(long session) {
        return db().sql("SELECT count(*) FROM casino_entry WHERE session_id = :s AND status = 'SENT'")
                .param("s", session).query(Long.class).single();
    }

    // ------------------------------------------------------------------ backtest

    /** Os últimos 4 quartos de hora com esta configuração (página Cassino, um símbolo por vez). */
    public Map<String, Object> backtest(String symbol, String side, Double lot, double takeUsd, double stopUsd,
                                        Integer closeAfterMinutes) {
        Instrument inst = orders.instrument(symbol);
        if (inst == null) throw new IllegalArgumentException("Símbolo sem especificação do MT5: " + symbol);
        double l = lot != null ? lot : inst.minLot();
        double usdPerPrice = inst.lossPerLot(1.0) * l;
        Scanner.Input in = scanner.input(inst.market().code(), symbol);
        Double point = orders.point(symbol);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("lot", l);
        if (in == null || in.m1().isEmpty() || point == null || !(usdPerPrice > 0)) {
            out.put("quarters", List.of());
            out.put("message", "sem candles M1 recentes de " + symbol + " (o MT5 está exportando?)");
            return out;
        }
        double spreadPx = (in.spreadNow() == null ? 0 : in.spreadNow()) * point;
        double tpDist = takeUsd / usdPerPrice, slDist = stopUsd / usdPerPrice;
        Instant lastBar = in.m1().get(in.m1().size() - 1).t().toInstant(java.time.ZoneOffset.UTC);
        Instant ref = Instant.now().isAfter(lastBar.plusSeconds(120)) ? lastBar.plusSeconds(60) : Instant.now();
        out.put("quarters", CasinoBacktest.run(in.m1(), ref, "BUY".equals(side), tpDist, slDist, spreadPx,
                closeAfterMinutes == null ? 0 : closeAfterMinutes, usdPerPrice));
        out.put("spread_usd", spreadPx * usdPerPrice);
        out.put("take_distance", tpDist);
        out.put("stop_distance", slDist);
        out.put("spread_points", in.spreadNow());
        out.put("too_close", tpDist <= 2 * spreadPx || slDist <= 2 * spreadPx);
        out.put("last_bar", lastBar);
        return out;
    }

    // ------------------------------------------------------------------ o que a página mostra

    public Map<String, Object> view() {
        State s = state();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", s);
        out.put("symbols", orders.symbolsByMarket());
        out.put("round_result", s.session() == 0 ? null : roundResult(s.session(), s.round()));
        out.put("session_result", s.session() == 0 ? null : db().sql("""
                        SELECT sum(result_usd) FROM casino_entry WHERE session_id = :s
                        """).param("s", s.session()).query(Double.class).single());
        out.put("entries", db().sql("""
                        SELECT e.id, e.round, e.market, e.symbol, e.attempt, e.side, e.lot, e.status, e.result_usd,
                               e.message, e.created_at, e.finished_at
                          FROM casino_entry e ORDER BY e.id DESC LIMIT 60
                        """).query().listOfRows());
        out.put("totals", db().sql("""
                        SELECT count(*) FILTER (WHERE status IN ('TARGET', 'CLOSED')) AS trades,
                               count(*) FILTER (WHERE status = 'TARGET') AS at_target,
                               sum(result_usd) AS result_usd, min(result_usd) AS worst
                          FROM casino_entry WHERE created_at >= :from
                        """).param("from", Timestamp.from(Instant.now().minus(Duration.ofDays(7)))).query().singleRow());
        return out;
    }

    // ------------------------------------------------------------------ banco e estado

    /** A última tentativa da rodada desde que a linha foi ligada (religar não herda o alvo de antes). */
    private Entry lastEntry(long session, int round, String symbol, Instant since) {
        return db().sql("""
                        SELECT id, attempt, status, result_usd, message FROM casino_entry
                         WHERE session_id = :s AND round = :r AND symbol = :sym AND created_at >= :since
                         ORDER BY id DESC LIMIT 1
                        """).param("s", session).param("r", round).param("sym", symbol)
                .param("since", Timestamp.from(since == null ? Instant.EPOCH : since))
                .query((rs, i) -> new Entry(rs.getLong("id"), rs.getInt("attempt"), rs.getString("status"),
                        (Double) rs.getObject("result_usd"), rs.getString("message"))).optional().orElse(null);
    }

    private void insertEntry(State s, Line l, int attempt, Double lot, Long orderId, String status, String message) {
        db().sql("""
                        INSERT INTO casino_entry (session_id, round, market, symbol, attempt, side, lot, take_usd, stop_usd,
                                                  close_after_minutes, order_id, status, message, finished_at)
                        VALUES (:s, :r, :m, :sym, :a, :side, :lot, :take, :stop, :ca, :o, :st, :msg,
                                CASE WHEN :st = 'SENT' THEN NULL ELSE now() END)
                        """)
                .param("s", s.session()).param("r", s.round()).param("m", l.market()).param("sym", l.symbol())
                .param("a", attempt).param("side", l.side()).param("lot", lot, Types.DOUBLE).param("take", l.takeUsd())
                .param("stop", l.stopUsd()).param("ca", l.closeAfterMinutes(), Types.INTEGER).param("o", orderId, Types.BIGINT)
                .param("st", status).param("msg", message, Types.VARCHAR).update();
    }

    private void finishEntry(long id, String status, Double result, String message) {
        db().sql("UPDATE casino_entry SET status = :st, result_usd = :r, message = :m, finished_at = now() WHERE id = :id")
                .param("st", status).param("r", result, Types.DOUBLE).param("m", message, Types.VARCHAR).param("id", id).update();
    }

    private void update(String symbol, Line l) {
        State s = state();
        Map<String, Line> lines = new LinkedHashMap<>(s.lines());
        lines.put(symbol, l);
        save(new State(s.session(), s.round(), s.closing(), s.global(), lines, s.lastEvent(), s.roundStartedAt()));
    }

    private static State empty() {
        return new State(0, 0, false, new Global(null, null, 1), Map.of(), null, null);
    }

    private static double nz(Double d) {
        return d == null ? 0 : d;
    }

    private JdbcClient db() {
        return jdbc.getObject();
    }

    private State parse(String json) {
        try {
            State s = mapper.readValue(json, State.class);
            return s.lines() == null ? new State(s.session(), s.round(), s.closing(), s.global(), Map.of(), s.lastEvent(),
                    s.roundStartedAt()) : s;
        } catch (Exception e) {
            log.warn("Cassino: estado ilegível ({}); começando vazio", e.getMessage());
            return empty();
        }
    }

    private void save(State s) {
        try {
            repo.setSetting(KEY, mapper.writeValueAsString(s));
        } catch (Exception e) {
            throw new IllegalStateException("Não consegui gravar o estado do Cassino", e);
        }
    }
}
