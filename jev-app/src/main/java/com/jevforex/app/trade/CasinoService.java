package com.jevforex.app.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevforex.app.config.RiskSettingsHolder;
import com.jevforex.app.live.PredictionRepository;
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
    private final PredictionRepository predictions;
    private final CasinoProperties casinoProps;
    private final ObjectProvider<JdbcClient> jdbc;
    private final ObjectMapper mapper;
    private final Map<Long, Instant> missingSince = new HashMap<>();

    public CasinoService(TradeRepository repo, OrderService orders, RiskSettingsHolder riskHolder, ScannerService scanner,
                         PredictionRepository predictions, CasinoProperties casinoProps, ObjectProvider<JdbcClient> jdbc,
                         ObjectMapper mapper) {
        this.repo = repo;
        this.orders = orders;
        this.riskHolder = riskHolder;
        this.scanner = scanner;
        this.predictions = predictions;
        this.casinoProps = casinoProps;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Uma linha da tabela. 23/10/2026 (operador): "vezes" = quantas vezes abrir, aconteça o que acontecer; em cada vez,
     * até {@code attempts} tentativas para bater o alvo. Total de ordens ≤ vezes × tentativas.
     *
     * @param side              BUY | SELL; com {@code auto}, o lado de cada ordem vem da previsão (o "Deixa comigo!")
     * @param lot               null = o lote mínimo do símbolo
     * @param attempts          tentativas por vez (para quando bate o alvo)
     * @param times             vezes (o laço de fora)
     * @param closeAfterMinutes null = sem saída por tempo
     * @param on                faz parte da sessão (o operador ligou e não desligou): recomeça a cada rodada
     * @param cycle             a vez atual (1…times)
     * @param used              tentativas usadas na vez atual
     * @param hits              vezes que bateram o alvo
     * @param counted           a última tentativa já contada (decidir de novo não conta duas vezes)
     * @param status            OFF | RUNNING | DONE | STOPPED | GLOBAL
     * @param minSpreads        "lucro mínimo" (23/10/2026): o alvo é o menor possível + esta margem em spreads, a cada
     *                          ordem (takeUsd não vale); null = alvo em US$
     */
    public record Line(String symbol, String market, String side, boolean auto, Double lot, double takeUsd,
                       double stopUsd, int attempts, int times, Integer closeAfterMinutes, boolean on, int cycle,
                       int used, int hits, long counted, String status, String message, Instant startedAt,
                       Double minSpreads) {

        Line with(boolean on, String status, String message) {
            return new Line(symbol, market, side, auto, lot, takeUsd, stopUsd, attempts, times, closeAfterMinutes, on,
                    cycle, used, hits, counted, status, message, startedAt, minSpreads);
        }

        Line counters(int cycle, int used, int hits, long counted) {
            return new Line(symbol, market, side, auto, lot, takeUsd, stopUsd, attempts, times, closeAfterMinutes, on,
                    cycle, used, hits, counted, status, message, startedAt, minSpreads);
        }

        /** Zera os contadores (ligar ou rodada nova). */
        Line restart(String message) {
            return new Line(symbol, market, side, auto, lot, takeUsd, stopUsd, attempts, times, closeAfterMinutes, true,
                    1, 0, 0, 0, "RUNNING", message, startedAt, minSpreads);
        }

        boolean running() {
            return "RUNNING".equals(status);
        }

        int timesOrOne() {
            return Math.max(1, times);
        }

        int cycleOrOne() {
            return Math.max(1, cycle);
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

    /** Liga uma linha e abre a 1ª ordem na hora (com o "Deixa comigo!", assim que houver previsão recente). */
    public synchronized Map<String, Object> start(String symbol, String side, boolean auto, Double lot, double takeUsd,
                                                  double stopUsd, int attempts, int times, Integer closeAfterMinutes) {
        return start(symbol, side, auto, lot, takeUsd, stopUsd, attempts, times, closeAfterMinutes, null);
    }

    /**
     * "Lucro mínimo" (pedido do operador em 23/10/2026): liga de uma vez todos os pares dados (os de um mercado, na
     * página), com o alvo no menor valor possível + margem; o lucro fica sempre no TP do servidor. Pares já ligados e
     * sem especificação ficam de fora; com o "Deixa comigo!", também os sem previsão do modelo (extras).
     *
     * @return por par: "ligado" ou o motivo de não ter ligado
     */
    public synchronized Map<String, String> startMin(List<String> symbols, String side, boolean auto, Double lot,
                                                     double marginSpreads, double stopUsd, int attempts, int times,
                                                     Integer closeAfterMinutes) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String sym : symbols) {
            Line cur = state().lines().get(sym);
            if (cur != null && cur.running()) {
                out.put(sym, "já estava ligado");
                continue;
            }
            boolean extra = casinoProps.groups().stream().anyMatch(g -> g.symbols().contains(sym));
            try {
                start(sym, side, auto && !extra, lot, 0, stopUsd, attempts, times, closeAfterMinutes, marginSpreads);
                Line l = state().lines().get(sym);
                out.put(sym, l == null ? "?" : l.running() ? "ligado" + (auto && extra ? " (extra: lado " + side + ")" : "")
                        : l.message());
            } catch (IllegalArgumentException e) {
                out.put(sym, e.getMessage());
            }
        }
        log.info("Cassino: lucro mínimo — {}", out);
        return out;
    }

    public synchronized Map<String, Object> start(String symbol, String side, boolean auto, Double lot, double takeUsd,
                                                  double stopUsd, int attempts, int times, Integer closeAfterMinutes,
                                                  Double minSpreads) {
        Instrument inst = orders.instrument(symbol);
        if (inst == null) throw new IllegalArgumentException("Símbolo sem especificação do MT5: " + symbol);
        if (auto && casinoProps.groups().stream().anyMatch(g -> g.symbols().contains(symbol))) {
            throw new IllegalArgumentException("\"Deixa comigo!\" só nos símbolos com previsão do modelo; " + symbol
                    + " é um extra do Cassino: escolha compra ou venda");
        }
        if (auto) side = "AUTO";
        else if (!"BUY".equals(side) && !"SELL".equals(side)) throw new IllegalArgumentException("Lado deve ser BUY ou SELL");
        if (minSpreads != null) {
            if (!(minSpreads >= 0) || minSpreads > 50) throw new IllegalArgumentException("Margem do lucro mínimo: de 0 a 50 spreads");
            takeUsd = 0;   // o alvo é calculado a cada ordem
        } else if (!(takeUsd > 0)) throw new IllegalArgumentException("O alvo em US$ precisa ser > 0");
        if (!(stopUsd > 0)) throw new IllegalArgumentException("O stop em US$ precisa ser > 0 (o EA não abre sem stop)");
        if (attempts < 1) throw new IllegalArgumentException("Tentativas: pelo menos 1");
        if (times < 1) throw new IllegalArgumentException("Vezes: pelo menos 1");
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
            lines.replaceAll((k, l) -> l.with(false, l.running() ? "OFF" : l.status(), l.message()));
        }
        Line line = new Line(symbol, inst.market().code(), side, auto, lot, takeUsd, stopUsd, attempts, times,
                closeAfterMinutes, true, 1, 0, 0, 0, "RUNNING", "ligada", Instant.now(), minSpreads);
        lines.put(symbol, line);
        State next = new State(session, round, false, s.global(), lines, s.lastEvent(), roundStart);
        save(next);
        if (minSpreads == null) {   // a configuração adotada fica salva para o par (o lucro mínimo tem a dele, na página)
            saveConfigs(Map.of(symbol, new Config(auto ? "BUY" : side, auto, lot, takeUsd, stopUsd, times, attempts,
                    closeAfterMinutes)));
        }
        log.info("Cassino: {} LIGADO — {} lote {} · alvo {} · stop US$ {} · {} vez(es) × até {} tentativa(s){}", symbol,
                auto ? "lado pela previsão" : side, lot == null ? "mínimo" : lot,
                minSpreads == null ? "US$ " + takeUsd : "mínimo + " + minSpreads + " spread(s)", stopUsd, times, attempts,
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
        lines.put(symbol, l.with(false, "OFF", why));
        State next = new State(s.session(), s.round(), s.closing(), s.global(), lines, s.lastEvent(), s.roundStartedAt());
        save(next);
        log.info("Cassino: {} desligado ({})", symbol, why);
        return next;
    }

    public synchronized State stopAll(String why, boolean closePositions) {
        State s = state();
        Map<String, Line> lines = new LinkedHashMap<>(s.lines());
        lines.replaceAll((k, l) -> l.on() || l.running() ? l.with(false, l.running() ? "OFF" : l.status(), why) : l);
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
            case SUBMIT -> {
                if (d.line() != l) update(symbol, d.line());   // contadores da vez avançaram
                submit(s, d.line());
            }
            case FINISH -> update(symbol, d.line());
        }
    }

    enum Action { WAIT, SUBMIT, FINISH }

    /** @param line a linha com os contadores já atualizados pela última tentativa */
    record Decision(Action action, Line line) {
    }

    /** A última tentativa de uma linha: status (SENT, REFUSED, TARGET, CLOSED, FAILED) e o resultado em US$. */
    record Entry(long id, int attempt, String status, Double resultUsd, String message) {
    }

    /**
     * Puro (testável): continua, espera ou termina. Fechou sem o alvo com tentativas sobrando → outra tentativa na
     * mesma vez; bateu o alvo ou acabaram as tentativas → a vez termina; com vezes sobrando → a próxima vez, aconteça o
     * que acontecer; senão a linha termina (DONE). Recusa ou falha → para (falhar fechado).
     */
    static Decision decide(Line l, Entry last) {
        if (last == null || last.id() == l.counted()) return new Decision(Action.SUBMIT, l);   // nada novo a contar
        return switch (last.status()) {
            case "SENT" -> new Decision(Action.WAIT, l);
            case "TARGET", "CLOSED" -> {
                boolean hit = "TARGET".equals(last.status());
                int hits = l.hits() + (hit ? 1 : 0);
                if (!hit && l.used() < l.attempts()) {
                    yield new Decision(Action.SUBMIT, l.counters(l.cycleOrOne(), l.used(), hits, last.id()));
                }
                String vez = String.format(Locale.ROOT, "vez %d de %d: %s", l.cycleOrOne(), l.timesOrOne(),
                        hit ? String.format(Locale.ROOT, "alvo na tentativa %d (US$ %+.2f)", last.attempt(), nz(last.resultUsd()))
                                : String.format(Locale.ROOT, "%d tentativa(s) sem o alvo", l.attempts()));
                if (l.cycleOrOne() < l.timesOrOne()) {
                    yield new Decision(Action.SUBMIT, l.counters(l.cycleOrOne() + 1, 0, hits, last.id())
                            .with(l.on(), "RUNNING", vez));
                }
                yield new Decision(Action.FINISH, l.counters(l.cycleOrOne(), l.used(), hits, last.id()).with(l.on(), "DONE",
                        String.format(Locale.ROOT, "%d vez(es), %d no alvo · a última — %s", l.timesOrOne(), hits, vez)));
            }
            default -> new Decision(Action.FINISH, l.with(l.on(), "STOPPED", last.message()));   // REFUSED, FAILED
        };
    }

    /** Até quando uma previsão serve para o "Deixa comigo!" (as horas cheias + folga). */
    static final Duration AUTO_MAX_AGE = Duration.ofMinutes(75);

    /** O lado escolhido pelo "Deixa comigo!" e o porquê. */
    record Choice(String side, String why) {
    }

    /**
     * "Deixa comigo!" (pedido do operador em 23/10/2026): o lado de cada ordem é o mais provável entre alta e queda na
     * previsão mais recente do modelo B (eventos + preço) do símbolo, 15 min antes de 60 min, de até
     * {@link #AUTO_MAX_AGE} antes de {@code at}. LATERAL não impede: o operador pediu uma direção. Sem previsão recente:
     * null (a linha espera; não gera previsão fora de hora, que dispararia a roleta e o sinal automático).
     */
    static Choice choose(List<PredictionRepository.Prediction> preds, String symbol, Instant at) {
        for (int h : new int[]{15, 60}) {
            PredictionRepository.Prediction best = null;
            for (PredictionRepository.Prediction p : preds) {
                if (!p.symbol().equals(symbol) || !"B".equals(p.model()) || p.horizonMin() != h) continue;
                if (p.momentUtc().isAfter(at) || p.momentUtc().isBefore(at.minus(AUTO_MAX_AGE))) continue;
                if (best == null || p.momentUtc().isAfter(best.momentUtc())) best = p;
            }
            if (best == null) continue;
            String side = best.pUp() >= best.pDown() ? "BUY" : "SELL";
            java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneOffset.UTC);
            return new Choice(side, String.format(Locale.ROOT, "B %d min das %s UTC: alta %.0f%% × queda %.0f%% (lateral %.0f%%)",
                    h, f.format(best.momentUtc()), best.pUp() * 100, best.pDown() * 100, best.pFlat() * 100));
        }
        return null;
    }

    private void submit(State s, Line l) {
        Instrument inst = orders.instrument(l.symbol());
        Double lot = l.lot() != null ? l.lot() : inst == null ? null : inst.minLot();
        String side = l.side(), why = null;
        if (l.auto()) {
            Choice c = choose(predictions.latest(l.market()), l.symbol(), Instant.now());
            if (c == null) {
                String wait = "esperando uma previsão do modelo B de até " + AUTO_MAX_AGE.toMinutes() + " min para decidir o lado";
                if (!wait.equals(l.message())) update(l.symbol(), l.with(l.on(), "RUNNING", wait));
                return;
            }
            side = c.side();
            why = c.why();
        }
        int attempt = l.used() + 1;
        int closeAfter = l.closeAfterMinutes() == null ? 0 : l.closeAfterMinutes();
        OrderService.Submit r = orders.submit(l.symbol(), side, lot, l.minSpreads() != null
                ? OrderService.Roulette.casinoMin(closeAfter, l.minSpreads(), l.stopUsd())
                : OrderService.Roulette.casino(closeAfter, l.takeUsd(), l.stopUsd()));
        // lucro mínimo: o alvo em US$ é o que a prévia calculou com o spread de agora
        double take = l.minSpreads() != null && r.preview().rewardUsd() != null ? r.preview().rewardUsd() : l.takeUsd();
        if (r.orderId() != null) {
            insertEntry(s, l, attempt, side, lot, take, r.orderId(), "SENT", String.format(Locale.ROOT,
                    "%s %.2f · alvo US$ %.2f%s · stop US$ %.2f%s", side, r.preview().lot(), take,
                    l.minSpreads() != null ? " (lucro mínimo)" : "", l.stopUsd(), why == null ? "" : " · " + why));
            update(l.symbol(), l.counters(l.cycleOrOne(), attempt, l.hits(), l.counted()).with(l.on(), "RUNNING",
                    String.format(Locale.ROOT, "vez %d de %d · tentativa %d de %d enviada (%s)", l.cycleOrOne(),
                            l.timesOrOne(), attempt, l.attempts(), "BUY".equals(side) ? "compra" : "venda")));
            log.info("Cassino: {} vez {}/{} tentativa {}/{} — {} {} (ordem {}){}", l.symbol(), l.cycleOrOne(), l.timesOrOne(),
                    attempt, l.attempts(), side, r.preview().lot(), r.orderId(), why == null ? "" : " · " + why);
            return;
        }
        String failed = r.preview().checks().stream().filter(x -> !x.ok())
                .map(x -> x.gate() + " (" + x.value() + ")").collect(Collectors.joining("; "));
        insertEntry(s, l, attempt, side, lot, take, null, "REFUSED", failed);
        update(l.symbol(), l.with(l.on(), "STOPPED", "recusada: " + failed));
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
        lines.replaceAll((k, l) -> l.running() ? l.with(l.on(), "GLOBAL", why) : l);
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
            lines.replaceAll((k, l) -> l.on() ? l.restart("rodada " + next + " de " + rounds) : l);
            save(new State(s.session(), next, false, s.global(), lines, s.lastEvent() + " · rodada " + next + " começou",
                    Instant.now()));
            log.info("Cassino: rodada {} de {} começou", next, rounds);
        } else {
            lines.replaceAll((k, l) -> l.on() ? l.with(false, "GLOBAL", "fim das " + rounds + " rodada(s)") : l);
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

    /**
     * Os últimos 4 quartos de hora com esta configuração (página Cassino, um símbolo por vez). Com o "Deixa comigo!"
     * ({@code auto}), o lado de cada quarto é o que {@link #choose} daria com as previsões gravadas até o início dele.
     */
    public Map<String, Object> backtest(String symbol, String side, boolean auto, Double lot, double takeUsd,
                                        double stopUsd, Integer closeAfterMinutes) {
        return backtest(symbol, side, auto, lot, takeUsd, stopUsd, closeAfterMinutes, null);
    }

    /**
     * Prévia do lucro mínimo por par, agora: o alvo em US$ (com o lote e o spread de agora) e se a ordem passaria nas
     * travas — a mesma prévia do envio (OrderService.preview).
     */
    public List<Map<String, Object>> minPreview(List<String> symbols, Double lot, double marginSpreads, double stopUsd) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (String sym : symbols) {
            Instrument inst = orders.instrument(sym);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", sym);
            if (inst == null) {
                m.put("ok", false);
                m.put("why", "sem especificação do MT5");
                out.add(m);
                continue;
            }
            OrderService.Preview p = orders.preview(sym, "BUY", lot != null ? lot : inst.minLot(),
                    OrderService.Roulette.casinoMin(0, marginSpreads, stopUsd));
            m.put("lot", p.lot());
            m.put("take_usd", p.rewardUsd());
            m.put("take_distance", p.tpDistance());
            m.put("ok", p.ok());
            m.put("why", p.checks().stream().filter(x -> !x.ok()).map(x -> x.gate() + " (" + x.value() + ")")
                    .collect(Collectors.joining("; ")));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> backtest(String symbol, String side, boolean auto, Double lot, double takeUsd,
                                        double stopUsd, Integer closeAfterMinutes, Double minSpreads) {
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
        Double minDist = minSpreads == null ? null : orders.minTakeDistance(symbol, in.spreadNow(), minSpreads);
        double tpDist = minDist != null ? minDist : takeUsd / usdPerPrice, slDist = stopUsd / usdPerPrice;
        out.put("take_usd", tpDist * usdPerPrice);
        Instant lastBar = in.m1().get(in.m1().size() - 1).t().toInstant(java.time.ZoneOffset.UTC);
        Instant ref = Instant.now().isAfter(lastBar.plusSeconds(120)) ? lastBar.plusSeconds(60) : Instant.now();
        java.util.function.Function<Instant, Boolean> sideAt;
        if (auto) {
            List<PredictionRepository.Prediction> hist = predictions.history(inst.market().code(), symbol,
                    ref.minus(Duration.ofHours(1)).minus(AUTO_MAX_AGE), 500);
            sideAt = t -> {
                Choice c = choose(hist, symbol, t);
                return c == null ? null : "BUY".equals(c.side());
            };
        } else {
            boolean buy = "BUY".equals(side);
            sideAt = t -> buy;
        }
        out.put("quarters", CasinoBacktest.run(in.m1(), ref, sideAt, tpDist, slDist, spreadPx,
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

    /** A configuração salva de um par na tabela (23/10/2026: "salvar a configuração adotada para cada par"). */
    public record Config(String side, boolean auto, Double lot, double takeUsd, double stopUsd, int times, int attempts,
                         Integer closeAfter) {
    }

    private static final String CONFIGS = "casino.configs";

    public synchronized Map<String, Config> configs() {
        return repo.setting(CONFIGS).map(json -> {
            try {
                return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Config>>() { });
            } catch (Exception e) {
                log.warn("Cassino: configurações salvas ilegíveis ({})", e.getMessage());
                return Map.<String, Config>of();
            }
        }).orElse(Map.of());
    }

    /** Salva (ou troca) a configuração de cada par recebido; os demais ficam como estão. */
    public synchronized Map<String, Config> saveConfigs(Map<String, Config> changes) {
        Map<String, Config> all = new java.util.TreeMap<>(configs());
        for (var e : changes.entrySet()) {
            Config c = e.getValue();
            if (c == null) {
                all.remove(e.getKey());
                continue;
            }
            if (!e.getKey().matches("[A-Za-z0-9._]+")) throw new IllegalArgumentException("símbolo inválido: " + e.getKey());
            if (!(c.takeUsd() > 0) || !(c.stopUsd() > 0) || c.times() < 1 || c.attempts() < 1) {
                throw new IllegalArgumentException(e.getKey() + ": alvo e stop > 0, vezes e tentativas ≥ 1");
            }
            all.put(e.getKey(), c);
        }
        try {
            repo.setSetting(CONFIGS, mapper.writeValueAsString(all));
        } catch (Exception ex) {
            throw new IllegalStateException("Não consegui gravar as configurações do Cassino", ex);
        }
        log.info("Cassino: configuração salva de {}", String.join(", ", changes.keySet()));
        return all;
    }

    /**
     * Os grupos da tabela: os símbolos do sistema por mercado e os extras do Cassino (casino.groups). {@code ready} =
     * a corretora mandou a especificação; {@code predicted} = tem previsão do modelo (o "Deixa comigo!" funciona).
     */
    private List<Map<String, Object>> groups() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (var e : orders.symbolsByMarket().entrySet()) {
            out.add(group("base-" + e.getKey(), null, e.getKey(), false, e.getValue(), true));
        }
        for (CasinoProperties.Group g : casinoProps.groups()) {
            out.add(group(g.code(), g.label(), g.market(), g.optional(), g.symbols(), false));
        }
        return out;
    }

    private Map<String, Object> group(String code, String label, String market, boolean optional, List<String> symbols,
                                      boolean predicted) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (String s : symbols) {
            Instrument inst = orders.instrument(s);
            rows.add(Map.of("symbol", s, "ready", inst != null, "predicted", predicted));
        }
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("code", code);
        g.put("label", label);
        g.put("market", market);
        g.put("optional", optional);
        g.put("symbols", rows);
        return g;
    }

    public Map<String, Object> view() {
        State s = state();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", s);
        out.put("groups", groups());
        out.put("configs", configs());
        out.put("round_result", s.session() == 0 ? null : roundResult(s.session(), s.round()));
        out.put("session_result", s.session() == 0 ? null : db().sql("""
                        SELECT sum(result_usd) FROM casino_entry WHERE session_id = :s
                        """).param("s", s.session()).query(Double.class).single());
        out.put("entries", db().sql("""
                        SELECT e.id, e.round, e.market, e.symbol, e.cycle, e.attempt, e.side, e.auto_side, e.lot, e.status, e.result_usd,
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

    private void insertEntry(State s, Line l, int attempt, String side, Double lot, double takeUsd, Long orderId,
                             String status, String message) {
        db().sql("""
                        INSERT INTO casino_entry (session_id, round, market, symbol, cycle, attempt, side, auto_side, lot,
                                                  take_usd, stop_usd, close_after_minutes, order_id, status, message, finished_at)
                        VALUES (:s, :r, :m, :sym, :c, :a, :side, :auto, :lot, :take, :stop, :ca, :o, :st, :msg,
                                CASE WHEN :st = 'SENT' THEN NULL ELSE now() END)
                        """)
                .param("s", s.session()).param("r", s.round()).param("m", l.market()).param("sym", l.symbol())
                .param("c", l.cycleOrOne()).param("a", attempt).param("side", side).param("auto", l.auto())
                .param("lot", lot, Types.DOUBLE).param("take", takeUsd)
                .param("stop", l.stopUsd()).param("ca", l.closeAfterMinutes(), Types.INTEGER).param("o", orderId, Types.BIGINT)
                .param("st", status).param("msg", message, Types.VARCHAR).update();
    }

    /** Fecha a tentativa; a mensagem do envio (com o porquê do lado) fica, o resultado vai depois dela. */
    private void finishEntry(long id, String status, Double result, String message) {
        db().sql("""
                        UPDATE casino_entry SET status = :st, result_usd = :r, finished_at = now(),
                               message = CASE WHEN message IS NULL THEN :m ELSE message || ' → ' || :m END
                         WHERE id = :id
                        """)
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
