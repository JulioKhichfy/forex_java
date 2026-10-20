package com.jevforex.ml;

import com.jevforex.lake.LakeSql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Laboratório de configuração (pedido do operador em 19/10/2026): que regra de entrada e de saída teria dado mais
 * resultado num mercado — sem escolher sorte.
 *
 * <p>Base: as previsões FORA DA AMOSTRA do walk-forward (cada mês previsto por um modelo que não o viu) e do cofre,
 * mais as previsões ao vivo já passadas. Grade: modelo (A, E, B) × horizonte (15, 60) × momento (todos, só evento, só
 * hora cheia) × probabilidade mínima × margem mínima × pular LATERAL × stop (ATR) × alvo (× stop). Cada saída é
 * simulada de novo no M1 ({@link Outcomes}: stop, alvo, tempo e spread).</p>
 *
 * <p>Contra o excesso de ajuste: a configuração é ESCOLHIDA num período (walk-forward até {@code split}) e só é
 * recomendada se também for positiva no período de CONFIRMAÇÃO (o resto do walk-forward + o cofre), que não entrou
 * na escolha; o ao vivo é a terceira conferência, só mostrada. A "sobrevivência" diz quantas das melhores na escolha
 * continuaram positivas na confirmação: perto de zero = o ranking é ruído.</p>
 */
public final class RiskLab {

    public static final List<String> MODELS = List.of("A", "E", "B");
    public static final List<Integer> HORIZONS = List.of(15, 60);
    public static final List<String> KINDS = List.of("ALL", "EVENT", "CONTROL");
    public static final List<Double> P_MIN = List.of(0.0, 0.40, 0.45, 0.50, 0.55, 0.60);
    public static final List<Double> M_MIN = List.of(0.0, 0.10, 0.20);
    public static final List<Double> STOPS = List.of(1.0, 1.5, 2.0);
    public static final List<Double> TARGETS = List.of(1.0, 1.5, 2.0);

    /**
     * Entrada: o lado mais provável entre alta e queda quando P(lado) ≥ pMin e P(lado) − P(oposto) ≥ mMin; com
     * skipFlat, não entra se LATERAL for o mais provável. Saída: stop em ATR, alvo em × stop, ou o fim do horizonte.
     *
     * @param kind ALL | EVENT (minutos depois de evento) | CONTROL (hora cheia)
     */
    public record Config(String model, int horizon, String kind, double pMin, double mMin, boolean skipFlat,
                         double stopAtr, double targetR) {
    }

    /**
     * @param n      operações
     * @param er     E[R] (resultado médio em R; 1 R = a perda no stop)
     * @param hit    fração com lucro
     * @param pf     profit factor (ganhos ÷ perdas; null = sem perdas)
     * @param maxDd  pior queda acumulada, em R
     * @param perMonth operações por mês
     */
    public record Stats(int n, double er, double sumR, double hit, Double pf, double maxDd, double perMonth) {
    }

    public record Row(Config config, Stats choose, Stats confirm, Stats live) {
    }

    /** Previsão ao vivo já passada (do Postgres): o mesmo formato das do gold. */
    public record LivePrediction(String symbol, Instant moment, String kind, String model, int horizon,
                                 double pDown, double pFlat, double pUp, double atr) {
    }

    /**
     * @param survivors das 20 melhores na escolha (E[R] > 0), quantas seguiram positivas na confirmação (são vizinhas:
     *                  limiares próximos da mesma regra)
     * @param baseSurvivors de TODAS as positivas na escolha (baseOf), quantas seguiram positivas: a taxa de base. Se as
     *                  melhores não sobrevivem bem mais que isso, o ranking é ruído
     * @param recommended a melhor na escolha que também foi positiva na confirmação (null = nenhuma: não operar)
     * @param current   a regra da roleta de hoje, nos dois horizontes (modelo B, lado mais provável, pula LATERAL,
     *                  saídas do mercado)
     */
    public record Report(String market, Instant builtAt, String wfRun, String lockboxRun, LocalDate chooseFrom,
                         LocalDate chooseTo, LocalDate confirmFrom, LocalDate confirmTo, Instant liveFrom,
                         Instant liveTo, int configs, int minTrades, List<Row> top, int survivors, int survivalOf,
                         int baseSurvivors, int baseOf,
                         Row recommended, List<Row> current, double seconds) {
    }

    private final Path lakeRoot;
    private final String market;
    private final List<String> symbols;
    private final String fset;
    private final LocalDate split;
    private final int entryDelayMinutes;
    private final int minTrades;
    private final double currentStop, currentTarget;

    /**
     * @param symbols          símbolos do mercado em uso (vazio = todos)
     * @param split            fim do período de escolha (o resto do walk-forward + cofre confirmam)
     * @param entryDelayMinutes atraso de entrada das previsões ao vivo (o mesmo do placar)
     * @param minTrades        mínimo de operações na escolha e na confirmação para valer
     */
    public RiskLab(Path lakeRoot, String market, List<String> symbols, String fset, LocalDate split,
                   int entryDelayMinutes, int minTrades, double currentStop, double currentTarget) {
        this.lakeRoot = lakeRoot;
        this.market = market;
        this.symbols = symbols == null ? List.of() : symbols;
        this.fset = fset;
        this.split = split;
        this.entryDelayMinutes = entryDelayMinutes;
        this.minTrades = minTrades;
        this.currentStop = currentStop;
        this.currentTarget = currentTarget;
    }

    public Report run(LakeSql sql, List<LivePrediction> live) {
        long t0 = System.nanoTime();
        Path preds = lakeRoot.resolve("gold/predictions/market=" + market);
        Path labels = lakeRoot.resolve("gold/labels/market=" + market + "/fset=" + fset);
        if (!Files.isDirectory(preds) || !Files.isDirectory(labels)) {
            throw new IllegalStateException("Sem previsões fora da amostra de " + market + " (rode train e train --open-lockbox)");
        }
        String read = "read_parquet('" + LakeSql.slashes(preds) + "/**/*.parquet', hive_partitioning = true, union_by_name = true)";
        String symFilter = symbols.isEmpty() ? "" : " AND symbol IN (" + String.join(",", symbols.stream()
                .map(s -> "'" + s.replace("'", "") + "'").toList()) + ")";
        // walk-forward = o run mais recente que começa antes do cofre; cofre = o mais recente que começa nele
        sql.execute("CREATE OR REPLACE TEMP TABLE runs AS SELECT run, min(moment_utc) AS a, max(moment_utc) AS b FROM "
                + read + " WHERE fset = '" + fset + "' GROUP BY run");
        String lbRun = one(sql, "SELECT run FROM runs WHERE a >= TIMESTAMP '2026-04-01' ORDER BY run DESC LIMIT 1");
        String lbStart = lbRun == null ? "2100-01-01" : one(sql, "SELECT CAST(CAST(a AS DATE) AS VARCHAR) FROM runs WHERE run = '" + lbRun + "'");
        String wfRun = one(sql, "SELECT run FROM runs WHERE a < TIMESTAMP '" + lbStart + "' ORDER BY run DESC LIMIT 1");
        if (wfRun == null) throw new IllegalStateException("Sem run de walk-forward de " + market + " no fset " + fset);

        // previsões em formato longo: período × modelo
        StringBuilder u = new StringBuilder();
        for (String m : MODELS) {
            String l = m.toLowerCase(Locale.ROOT);
            if (!u.isEmpty()) u.append(" UNION ALL ");
            u.append(String.format(Locale.ROOT, """
                    SELECT CASE WHEN run = '%1$s' AND moment_utc < TIMESTAMP '%2$s' THEN 'choose' ELSE 'confirm' END AS period,
                           symbol, moment_utc, kind, CAST(replace(horizon, 'm', '') AS INTEGER) AS h, '%3$s' AS model,
                           p_%4$s_down AS p_down, p_%4$s_flat AS p_flat, p_%4$s_up AS p_up
                      FROM %5$s WHERE run IN ('%1$s', '%6$s') AND p_%4$s_up IS NOT NULL%7$s
                    """, wfRun, split, m, l, read, lbRun == null ? wfRun : lbRun, symFilter));
        }
        sql.execute("CREATE OR REPLACE TEMP TABLE pg AS " + u);
        LocalDate from = LocalDate.parse(one(sql, "SELECT CAST(CAST(min(moment_utc) AS DATE) AS VARCHAR) FROM pg"));
        LocalDate chooseFrom = from;
        LocalDate confirmTo = LocalDate.parse(one(sql, "SELECT CAST(CAST(max(moment_utc) AS DATE) AS VARCHAR) FROM pg"));

        // ao vivo
        LinkedHashMap<String, String> cols = new LinkedHashMap<>();
        cols.put("symbol", "VARCHAR");
        cols.put("moment_utc", "TIMESTAMP");
        cols.put("kind", "VARCHAR");
        cols.put("model", "VARCHAR");
        cols.put("h", "INTEGER");
        cols.put("p_down", "DOUBLE");
        cols.put("p_flat", "DOUBLE");
        cols.put("p_up", "DOUBLE");
        cols.put("atr", "DOUBLE");
        List<Object[]> rows = new ArrayList<>();
        Instant liveFrom = null, liveTo = null;
        for (LivePrediction p : live) {
            if (!symbols.isEmpty() && !symbols.contains(p.symbol())) continue;
            if (!MODELS.contains(p.model()) || !HORIZONS.contains(p.horizon()) || !(p.atr() > 0)) continue;
            rows.add(new Object[]{p.symbol(), ts(p.moment()), p.kind(), p.model(), p.horizon(), p.pDown(), p.pFlat(),
                    p.pUp(), p.atr()});
            liveFrom = liveFrom == null || p.moment().isBefore(liveFrom) ? p.moment() : liveFrom;
            liveTo = liveTo == null || p.moment().isAfter(liveTo) ? p.moment() : liveTo;
        }
        rows.add(new Object[]{"-", "1970-01-01 00:00:00", "-", "-", 0, 0.0, 0.0, 0.0, 1.0});   // tabela nunca vazia
        sql.createTableFromRows("pl", cols, rows, lakeRoot.resolve("tmp"));

        Outcomes.prepare(sql, lakeRoot, market, from);

        // grade de entradas (uma linha por combinação; o id liga ao Config)
        List<double[]> exits = new ArrayList<>();
        for (double s : STOPS) for (double t : TARGETS) exits.add(new double[]{s, t});
        if (exits.stream().noneMatch(e -> e[0] == currentStop && e[1] == currentTarget)) {
            exits.add(new double[]{currentStop, currentTarget});
        }
        LinkedHashMap<String, String> xc = new LinkedHashMap<>();
        xc.put("stop_atr", "DOUBLE");
        xc.put("target_r", "DOUBLE");
        sql.createTableFromRows("exits", xc, exits.stream().map(e -> new Object[]{e[0], e[1]}).toList(), lakeRoot.resolve("tmp"));
        String symIn = symbols.isEmpty() ? "" : " AND l.symbol IN (" + String.join(",", symbols.stream()
                .map(x -> "'" + x.replace("'", "") + "'").toList()) + ")";

        Map<Integer, Config> byId = new HashMap<>();
        Map<Integer, Map<String, Stats>> stats = new HashMap<>();
        int id = 0;
        for (int h : HORIZONS) {
            // uma linha por combinação de entrada × saída (o id liga ao Config)
            List<Object[]> params = new ArrayList<>();
            for (double[] ex : exits) for (String m : MODELS) for (String k : KINDS) for (double p : P_MIN) for (double mm : M_MIN) for (boolean sk : List.of(true, false)) {
                if (p == 0.0 && mm == 0.0 && !sk) continue;   // "sempre entra" sem pular LATERAL: fica a com pulo
                if (p >= 0.5 && !sk) continue;                 // com P(lado) ≥ 50%, LATERAL nunca é o mais provável
                byId.put(id, new Config(m, h, k, p, mm, sk, ex[0], ex[1]));
                params.add(new Object[]{id++, m, k, p, mm, sk, ex[0], ex[1]});
            }
            LinkedHashMap<String, String> pc = new LinkedHashMap<>();
            pc.put("id", "INTEGER");
            pc.put("model", "VARCHAR");
            pc.put("kind", "VARCHAR");
            pc.put("pmin", "DOUBLE");
            pc.put("mmin", "DOUBLE");
            pc.put("skip", "BOOLEAN");
            pc.put("stop_atr", "DOUBLE");
            pc.put("target_r", "DOUBLE");
            sql.createTableFromRows("prm_" + h, pc, params, lakeRoot.resolve("tmp"));
            // o M1 é percorrido uma vez por horizonte para todas as saídas
            Outcomes.createGrid(sql, String.format(Locale.ROOT, """
                    SELECT l.symbol, l.moment_utc, l.atr, l.entry_t AS want_in
                      FROM read_parquet('%s/**/*.parquet', hive_partitioning = true) l
                      SEMI JOIN (SELECT DISTINCT symbol, moment_utc FROM pg WHERE h = %d) k USING (symbol, moment_utc)
                     WHERE l.horizon_min = %d%s
                    """, LakeSql.slashes(labels), h, h, symIn), "og", h);
            Outcomes.createGrid(sql, String.format(Locale.ROOT, """
                    SELECT DISTINCT symbol, moment_utc, atr, moment_utc + to_minutes(%d) AS want_in FROM pl WHERE h = %d
                    """, entryDelayMinutes, h), "ol", h);
            sql.execute(String.format(Locale.ROOT, """
                    CREATE OR REPLACE TEMP TABLE ev_%1$d AS
                    SELECT g.period, g.symbol, g.moment_utc, g.kind, g.model, g.p_down, g.p_flat, g.p_up,
                           o.stop_atr, o.target_r, o.r_buy, o.r_sell
                      FROM pg g JOIN og o USING (symbol, moment_utc) WHERE g.h = %1$d
                    UNION ALL
                    SELECT 'live', l.symbol, l.moment_utc, l.kind, l.model, l.p_down, l.p_flat, l.p_up,
                           o.stop_atr, o.target_r, o.r_buy, o.r_sell
                      FROM pl l JOIN ol o USING (symbol, moment_utc) WHERE l.h = %1$d
                    """, h));
            sql.query(String.format(Locale.ROOT, """
                    WITH x AS (%s)
                    SELECT id, period, count(*) AS n, avg(r) AS er, sum(r) AS sr, avg((r > 0)::INT) AS hit,
                           sum(r) FILTER (WHERE r > 0) AS gain, -sum(r) FILTER (WHERE r < 0) AS loss,
                           datediff('day', min(moment_utc), max(moment_utc)) AS days
                      FROM x GROUP BY id, period
                    """, trades(h, null)), rs -> {
                int n = rs.getInt("n");
                double gain = rs.getDouble("gain"), loss = rs.getDouble("loss");
                double months = Math.max(rs.getDouble("days"), 1) / 30.44;
                stats.computeIfAbsent(rs.getInt("id"), k -> new HashMap<>()).put(rs.getString("period"),
                        new Stats(n, rs.getDouble("er"), rs.getDouble("sr"), rs.getDouble("hit"),
                                loss > 0 ? gain / loss : null, 0, n / months));
                return null;
            });
        }

        List<Row> all = new ArrayList<>();
        byId.forEach((k, c) -> {
            Map<String, Stats> s = stats.getOrDefault(k, Map.of());
            all.add(new Row(c, s.get("choose"), s.get("confirm"), s.get("live")));
        });
        // combinações que filtram exatamente as mesmas operações (ex.: margem 0 e 0,10 quando nenhuma fica no meio)
        // contam uma vez só: fica a primeira, a mais simples
        Map<String, Row> distinct = new LinkedHashMap<>();
        all.stream().filter(r -> r.choose() != null && r.choose().n() >= minTrades)
                .sorted(Comparator.comparingDouble((Row r) -> r.choose().er()).reversed()
                        .thenComparingDouble(r -> r.config().pMin() + r.config().mMin()))
                .forEach(r -> distinct.putIfAbsent(signature(r), r));
        List<Row> ranked = List.copyOf(distinct.values());
        List<Row> positive = ranked.stream().filter(r -> r.choose().er() > 0).limit(20).toList();
        int survivors = (int) positive.stream().filter(RiskLab::confirmed).count();
        List<Row> allPositive = ranked.stream().filter(r -> r.choose().er() > 0).toList();
        int baseSurvivors = (int) allPositive.stream().filter(RiskLab::confirmed).count();
        Row recommended = ranked.stream().filter(r -> r.choose().er() > 0 && confirmed(r)).findFirst().orElse(null);
        List<Row> current = all.stream().filter(r -> r.config().model().equals("B") && r.config().kind().equals("ALL")
                && r.config().pMin() == 0.0 && r.config().mMin() == 0.0 && r.config().skipFlat()
                && r.config().stopAtr() == currentStop && r.config().targetR() == currentTarget)
                .sorted(Comparator.comparingInt((Row r) -> r.config().horizon())).toList();
        List<Row> top = ranked.stream().limit(15).toList();

        // pior queda acumulada só das finalistas (janela por id é cara para a grade inteira)
        Map<Config, Integer> idOf = new HashMap<>();
        byId.forEach((k, c) -> idOf.put(c, k));
        java.util.Set<Integer> wanted = new java.util.HashSet<>();
        for (Row r : top) wanted.add(idOf.get(r.config()));
        for (Row r : current) wanted.add(idOf.get(r.config()));
        if (recommended != null) wanted.add(idOf.get(recommended.config()));
        Map<Integer, Map<String, Double>> dd = new HashMap<>();
        for (int h : HORIZONS) {
            List<Integer> ids = wanted.stream().filter(k -> byId.get(k).horizon() == h).toList();
            if (ids.isEmpty()) continue;
            String in = String.join(",", ids.stream().map(String::valueOf).toList());
            sql.query(String.format(Locale.ROOT, """
                    WITH x AS (%s),
                    c AS (SELECT id, period, moment_utc, symbol,
                                 sum(r) OVER (PARTITION BY id, period ORDER BY moment_utc, symbol ROWS UNBOUNDED PRECEDING) AS cum
                            FROM x),
                    d AS (SELECT id, period,
                                 cum - greatest(0, max(cum) OVER (PARTITION BY id, period ORDER BY moment_utc, symbol
                                                                  ROWS UNBOUNDED PRECEDING)) AS dd
                            FROM c)
                    SELECT id, period, min(dd) AS mdd FROM d GROUP BY id, period
                    """, trades(h, in)), rs -> {
                dd.computeIfAbsent(rs.getInt("id"), k -> new HashMap<>()).put(rs.getString("period"), rs.getDouble("mdd"));
                return null;
            });
        }
        java.util.function.Function<Row, Row> withDd = r -> {
            Map<String, Double> d = dd.getOrDefault(idOf.get(r.config()), Map.of());
            return new Row(r.config(), dd(r.choose(), d.get("choose")), dd(r.confirm(), d.get("confirm")), dd(r.live(), d.get("live")));
        };
        return new Report(market, Instant.now(), wfRun, lbRun, chooseFrom, split.minusDays(1), split, confirmTo,
                liveFrom, liveTo, all.size(), minTrades, top.stream().map(withDd).toList(), survivors, positive.size(),
                baseSurvivors, allPositive.size(),
                recommended == null ? null : withDd.apply(recommended), current.stream().map(withDd).toList(),
                (System.nanoTime() - t0) / 1e9);
    }

    /** As operações de cada combinação (id) de um horizonte; {@code ids} = só estas (null = todas). */
    private static String trades(int h, String ids) {
        return String.format(Locale.ROOT, """
                SELECT p.id, e.period, e.moment_utc, e.symbol,
                       CASE WHEN e.p_up > e.p_down THEN e.r_buy ELSE e.r_sell END AS r
                  FROM ev_%1$d e JOIN prm_%1$d p ON p.model = e.model AND p.stop_atr = e.stop_atr AND p.target_r = e.target_r
                 WHERE (p.kind = 'ALL' OR e.kind = p.kind)
                   AND e.p_up <> e.p_down
                   AND greatest(e.p_up, e.p_down) >= p.pmin
                   AND abs(e.p_up - e.p_down) >= p.mmin
                   AND (NOT p.skip OR e.p_flat < greatest(e.p_up, e.p_down))
                   AND (CASE WHEN e.p_up > e.p_down THEN e.r_buy ELSE e.r_sell END) IS NOT NULL%2$s
                """, h, ids == null ? "" : " AND p.id IN (" + ids + ")");
    }

    private static Stats dd(Stats s, Double mdd) {
        return s == null || mdd == null ? s : new Stats(s.n(), s.er(), s.sumR(), s.hit(), s.pf(), mdd, s.perMonth());
    }
    private static String signature(Row r) {
        Config c = r.config();
        java.util.function.Function<Stats, String> f = s -> s == null ? "-" : s.n() + ":" + Math.round(s.sumR() * 1e6);
        return c.model() + c.horizon() + c.kind() + c.stopAtr() + "/" + c.targetR() + "|" + f.apply(r.choose()) + "|"
                + f.apply(r.confirm()) + "|" + f.apply(r.live());
    }

    /** Positiva também na confirmação, com operações bastantes. */
    private static boolean confirmed(Row r) {
        return r.confirm() != null && r.confirm().n() >= 20 && r.confirm().er() > 0;
    }

    private static String one(LakeSql sql, String q) {
        List<String> l = sql.query(q, rs -> rs.getString(1));
        return l.isEmpty() ? null : l.get(0);
    }

    private static String ts(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC).toString().replace('T', ' ');
    }
}
