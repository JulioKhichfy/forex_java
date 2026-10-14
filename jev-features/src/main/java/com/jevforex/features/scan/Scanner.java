package com.jevforex.features.scan;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Radar de movimentos atípicos (página Radar do dashboard). Só descreve o que aconteceu — RSI esticado, fechamento
 * fora das bandas, volume acima do normal, movimento grande em ATR, rompimentos, gaps, spread alto. Nenhum destes
 * alertas foi validado como sinal de entrada (a reversão às bandas de Bollinger, testada no passo 9a, deu E[R]
 * negativo nos 5 mercados): é contexto para o operador, não ordem.
 *
 * <p>Os indicadores de cada período usam só barras FECHADAS; o movimento "agora" usa a última barra M1.</p>
 */
public final class Scanner {

    private static final Locale PT = Locale.forLanguageTag("pt-BR");

    private Scanner() {
    }

    /**
     * @param bars          barras de cada período, em ordem de tempo (a última pode estar em formação)
     * @param m1            barras M1 das últimas ~26 h, em ordem (movimentos recentes e gaps)
     * @param spreadNow     spread da última barra M1, em pontos
     * @param spreadTypical mediana do spread na mesma hora UTC nos 20 dias anteriores
     */
    public record Input(String symbol, List<String> currencies, Map<Tf, List<Bar>> bars, List<Bar> m1,
                        Integer spreadNow, Double spreadTypical) {
    }

    /**
     * @param kind     RSI, BB, SQUEEZE, VOLUME, CANDLE, MOVE, STREAK, EMA, BREAKOUT, RANGE, GAP, SPREAD
     * @param tf       período (M5…D1) ou janela do movimento (15m, 1h, 4h, 24h, dia)
     * @param severity 1 = informativo, 2 = notável, 3 = forte
     * @param dir      up | down | null — a direção do movimento descrito (não é previsão)
     * @param at       quando aconteceu (fim da barra), UTC
     */
    public record Alert(String kind, String tf, int severity, String dir, String text, double value, Instant at) {

        Alert(String kind, String tf, int severity, String dir, String text, double value, LocalDateTime at) {
            this(kind, tf, severity, dir, text, value, utc(at));
        }
    }

    /**
     * Estado de um período na última barra fechada.
     *
     * @param rsiNow    RSI contando a barra em formação (o que o gráfico do MT5 mostra agora)
     * @param pctB      posição no canal de Bollinger: 0 = banda inferior, 1 = superior (fora: < 0 ou > 1)
     * @param bandwidth largura das bandas ÷ média
     * @param squeeze   largura no mínimo das últimas 120 barras
     * @param volRatio  volume de ticks ÷ volume normal (mesmo horário nos 20 dias anteriores no M15/H1; senão as 20
     *                  barras anteriores)
     * @param streak    barras seguidas fechando na mesma direção (+ alta, − queda)
     * @param trend     up | down | null: MME 20 acima/abaixo da MME 50
     */
    public record TfState(String tf, Instant barUtc, Double close, Double rsi, Double rsiNow, Double pctB,
                          Double upper, Double mid, Double lower, Double bandwidth, boolean squeeze, Double atr,
                          Double volRatio, int streak, String trend) {
    }

    /** Variação de uma janela: em % e em múltiplos do ATR do período correspondente. */
    public record Move(String window, Double pct, Double atrs) {
    }

    /** O dia (UTC) até agora e as referências dos dias anteriores. */
    public record Day(Double open, Double high, Double low, Double prevHigh, Double prevLow, Double high20,
                      Double low20, Double adr, Double rangeAdr) {
    }

    public record Result(String symbol, List<String> currencies, Instant lastBarUtc, Double price,
                         Integer spreadNow, Double spreadRatio, List<Move> moves, List<TfState> tfs, Day day,
                         List<Alert> alerts) {
    }

    // ------------------------------------------------------------------------------------------------ cálculo

    public static Result scan(Input in) {
        List<Alert> alerts = new ArrayList<>();
        Map<Tf, TfState> states = new EnumMap<>(Tf.class);
        for (Tf tf : Tf.values()) {
            TfState s = state(tf, in.bars().getOrDefault(tf, List.of()), alerts);
            states.put(tf, s);
        }
        List<Bar> m1 = in.m1();
        Bar last = m1.isEmpty() ? null : m1.get(m1.size() - 1);
        Double price = last == null ? null : last.close();
        LocalDateTime now = last == null ? null : last.t().plusMinutes(1);

        List<Move> moves = new ArrayList<>();
        if (last != null) {
            moves.add(move("15m", m1, 15, atrOf(states, Tf.M15), alerts, 2.0));
            moves.add(move("1h", m1, 60, atrOf(states, Tf.H1), alerts, 2.0));
            moves.add(move("4h", m1, 240, atrOf(states, Tf.H4), alerts, 2.0));
            moves.add(move("24h", m1, 1440, atrOf(states, Tf.D1), null, 0));
        }
        Day day = day(in.bars().getOrDefault(Tf.D1, List.of()), price, atrOf(states, Tf.D1), now, moves, alerts);
        if (last != null) gaps(m1, atrOf(states, Tf.H1), alerts);

        Double spreadRatio = in.spreadNow() == null || in.spreadTypical() == null || in.spreadTypical() <= 0 ? null
                : in.spreadNow() / in.spreadTypical();
        if (spreadRatio != null && spreadRatio >= 2) {
            alerts.add(new Alert("SPREAD", "M1", spreadRatio >= 4 ? 3 : 2, null,
                    f("Spread de %d pontos = %.1f× o típico deste horário (custo alto para entrar agora)",
                            in.spreadNow(), spreadRatio), spreadRatio, now));
        }
        alerts.sort(Comparator.comparingInt(Alert::severity).reversed()
                .thenComparing(Alert::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return new Result(in.symbol(), in.currencies(), last == null ? null : utc(last.t()), price, in.spreadNow(),
                spreadRatio, moves, List.copyOf(states.values()), day, alerts);
    }

    private static Double atrOf(Map<Tf, TfState> states, Tf tf) {
        TfState s = states.get(tf);
        return s == null ? null : s.atr();
    }

    private static TfState state(Tf tf, List<Bar> all, List<Alert> alerts) {
        List<Bar> closed = all.stream().filter(Bar::closed).toList();
        if (closed.size() < 2) {
            return new TfState(tf.name(), null, null, null, null, null, null, null, null, null, false, null, null, 0,
                    null);
        }
        int i = closed.size() - 1;
        Bar bar = closed.get(i);
        LocalDateTime at = bar.t().plusMinutes(tf.minutes);
        double[] c = closed.stream().mapToDouble(Bar::close).toArray();
        double[] rsi = Indicators.rsi(c, 14);
        double[] allC = all.stream().mapToDouble(Bar::close).toArray();
        double[] rsiAll = Indicators.rsi(allC, 14);
        double[] mid = Indicators.sma(c, 20), sd = Indicators.std(c, 20);
        double[] atr = Indicators.atr(closed, 14);

        Double r = num(rsi[i]);
        Double upper = num(mid[i] + 2 * sd[i]), lower = num(mid[i] - 2 * sd[i]);
        Double pctB = upper == null || upper.equals(lower) ? null : (c[i] - lower) / (upper - lower);
        double[] bw = new double[c.length];
        for (int k = 0; k < c.length; k++) bw[k] = mid[k] > 0 ? 4 * sd[k] / mid[k] : Double.NaN;
        boolean squeeze = false;
        if (i >= 20 + 119 && Double.isFinite(bw[i])) {
            double min = Double.MAX_VALUE;
            for (int k = i - 119; k < i; k++) min = Math.min(min, bw[k]);
            squeeze = bw[i] <= min;
        }
        Double volRatio = volumeRatio(tf, closed);
        int streak = 0;
        for (int k = i; k >= 1; k--) {
            int s = Double.compare(c[k], c[k - 1]);
            if (s == 0 || (streak != 0 && Integer.signum(streak) != s)) break;
            streak += s;
        }
        String trend = null;
        boolean crossed = false;
        if (closed.size() >= 100) {
            double[] e20 = Indicators.ema(c, 20), e50 = Indicators.ema(c, 50);
            trend = e20[i] > e50[i] ? "up" : "down";
            for (int k = i - 2; k <= i; k++) {
                if (Math.signum(e20[k] - e50[k]) != Math.signum(e20[k - 1] - e50[k - 1])) crossed = true;
            }
        }
        Double a = num(atr[i]);
        TfState st = new TfState(tf.name(), utc(bar.t()), c[i], r, num(rsiAll[rsiAll.length - 1]), pctB, upper, num(mid[i]),
                lower, num(bw[i]), squeeze, a, volRatio, streak, trend);

        // --------------------------------------------------------------- alertas do período
        int penalty = tf == Tf.M5 ? 1 : 0;   // no M5 tudo isso é comum: vale um nível a menos
        String barDir = bar.close() > bar.open() ? "up" : bar.close() < bar.open() ? "down" : null;
        if (r != null && (r >= 70 || r <= 30)) {
            boolean high = r >= 70;
            double ext = high ? r : 100 - r;
            int sev = sev(ext, 70, 80, 90) - penalty;
            alerts.add(new Alert("RSI", tf.name(), Math.max(1, sev), high ? "up" : "down",
                    f("RSI(14) %s fechou em %.0f: %s%s", tf.name(), r, high ? "sobrecompra" : "sobrevenda",
                            ext >= 80 ? " forte" : ""), r, at));
        }
        if (pctB != null && (pctB > 1 || pctB < 0)) {
            boolean above = pctB > 1;
            double out = above ? pctB - 1 : -pctB;
            int sev = (tf.minutes >= 60 ? 2 : 1) + (out >= 0.3 ? 1 : 0) - penalty;
            alerts.add(new Alert("BB", tf.name(), Math.max(1, Math.min(3, sev)), above ? "up" : "down",
                    f("%s fechou %s da banda %s de Bollinger (20, 2) — %%B %.2f", tf.name(),
                            above ? "acima" : "abaixo", above ? "superior" : "inferior", pctB), pctB, at));
        }
        if (squeeze && tf != Tf.M5) {
            alerts.add(new Alert("SQUEEZE", tf.name(), 1, null,
                    f("Bandas de Bollinger %s na menor largura de 120 barras: volatilidade comprimida (costuma vir antes "
                            + "de um movimento maior, sem dizer para que lado)", tf.name()), bw[i], at));
        }
        if (volRatio != null && volRatio >= 2.5) {
            alerts.add(new Alert("VOLUME", tf.name(), Math.max(1, sev(volRatio, 2.5, 4, 6) - penalty), barDir,
                    f("Volume de ticks %.1f× o normal na barra %s (%s)", volRatio, tf.name(),
                            barDir == null ? "sem direção" : barDir.equals("up") ? "barra de alta" : "barra de queda"),
                    volRatio, at));
        }
        if (a != null && a > 0 && tf != Tf.M5) {
            double body = Math.abs(bar.close() - bar.open()) / a;
            if (body >= 1.5) {
                alerts.add(new Alert("CANDLE", tf.name(), sev(body, 1.5, 2.5, 4), barDir,
                        f("Barra %s de %s com corpo de %.1f ATR", tf.name(), "up".equals(barDir) ? "alta" : "queda",
                                body), body, at));
            }
        }
        int minStreak = tf.minutes >= 240 ? 5 : 6;
        if (tf != Tf.M5 && Math.abs(streak) >= minStreak) {
            alerts.add(new Alert("STREAK", tf.name(), Math.abs(streak) >= minStreak + 3 ? 2 : 1,
                    streak > 0 ? "up" : "down", f("%d barras %s seguidas fechando em %s", Math.abs(streak), tf.name(),
                    streak > 0 ? "alta" : "queda"), Math.abs(streak), at));
        }
        if (crossed && (tf == Tf.H1 || tf == Tf.H4)) {
            alerts.add(new Alert("EMA", tf.name(), tf == Tf.H4 ? 2 : 1, trend,
                    f("Média móvel exponencial de 20 cruzou %s a de 50 no %s", "up".equals(trend) ? "para cima" : "para baixo",
                            tf.name()), 0, at));
        }
        return st;
    }

    /**
     * Volume da última barra fechada ÷ normal. No M15 e no H1 o normal é o MESMO horário nos 20 dias anteriores
     * (o volume tem sazonalidade forte: a abertura de Londres não é comparável à madrugada de Tóquio).
     */
    static Double volumeRatio(Tf tf, List<Bar> closed) {
        Bar bar = closed.get(closed.size() - 1);
        List<Double> base = new ArrayList<>();
        if (tf == Tf.M15 || tf == Tf.H1) {
            LocalDateTime from = bar.t().minusDays(20);
            for (int k = closed.size() - 2; k >= 0 && !closed.get(k).t().isBefore(from); k--) {
                Bar b = closed.get(k);
                if (b.t().toLocalTime().equals(bar.t().toLocalTime())) base.add((double) b.volume());
            }
            if (base.size() < 5) base.clear();
        }
        if (base.isEmpty()) {
            for (int k = closed.size() - 2; k >= 0 && base.size() < 20; k--) base.add((double) closed.get(k).volume());
            if (base.size() < 10) return null;
        }
        double med = Indicators.median(base.stream().mapToDouble(Double::doubleValue).toArray());
        return med > 0 ? bar.volume() / med : null;
    }

    private static Move move(String window, List<Bar> m1, int minutes, Double atr, List<Alert> alerts, double min) {
        Bar last = m1.get(m1.size() - 1);
        LocalDateTime ref = last.t().minusMinutes(minutes);
        Bar from = null;
        for (int k = m1.size() - 1; k >= 0; k--) {
            if (!m1.get(k).t().isAfter(ref)) {
                from = m1.get(k);
                break;
            }
        }
        if (from == null) return new Move(window, null, null);
        double d = last.close() - from.close();
        Double atrs = atr == null || atr <= 0 ? null : d / atr;
        if (alerts != null && atrs != null && Math.abs(atrs) >= min) {
            alerts.add(new Alert("MOVE", window, sev(Math.abs(atrs), min, min * 1.5, min * 2.25), d > 0 ? "up" : "down",
                    f("%s de %+.2f%% em %s (%.1f ATR)", d > 0 ? "Alta" : "Queda", 100 * d / from.close(), window,
                            Math.abs(atrs)), atrs, last.t().plusMinutes(1)));
        }
        return new Move(window, 100 * d / from.close(), atrs);
    }

    private static Day day(List<Bar> d1, Double price, Double atr, LocalDateTime now, List<Move> moves,
                           List<Alert> alerts) {
        if (d1.isEmpty() || price == null) return new Day(null, null, null, null, null, null, null, null, null);
        Bar today = d1.get(d1.size() - 1).closed() ? null : d1.get(d1.size() - 1);
        List<Bar> closed = d1.stream().filter(Bar::closed).toList();
        Bar prev = closed.isEmpty() ? null : closed.get(closed.size() - 1);
        List<Bar> last20 = closed.subList(Math.max(0, closed.size() - 20), closed.size());
        Double hi20 = last20.size() >= 10 ? last20.stream().mapToDouble(Bar::high).max().getAsDouble() : null;
        Double lo20 = last20.size() >= 10 ? last20.stream().mapToDouble(Bar::low).min().getAsDouble() : null;
        Double adr = last20.size() >= 10 ? last20.stream().mapToDouble(b -> b.high() - b.low()).average().getAsDouble()
                : null;
        Double rangeAdr = today == null || adr == null || adr <= 0 ? null : (today.high() - today.low()) / adr;
        if (today != null) {
            double d = price - today.open();
            Double atrs = atr == null || atr <= 0 ? null : d / atr;
            moves.add(new Move("dia", 100 * d / today.open(), atrs));
            if (atrs != null && Math.abs(atrs) >= 1.2) {
                alerts.add(new Alert("MOVE", "dia", sev(Math.abs(atrs), 1.2, 2, 3), d > 0 ? "up" : "down",
                        f("%s de %+.2f%% no dia (%.1f ATR diário)", d > 0 ? "Alta" : "Queda", 100 * d / today.open(),
                                Math.abs(atrs)), atrs, now));
            }
            if (rangeAdr != null && rangeAdr >= 1.5) {
                alerts.add(new Alert("RANGE", "D1", sev(rangeAdr, 1.5, 2, 3), d > 0 ? "up" : d < 0 ? "down" : null,
                        f("Amplitude do dia = %.1f× a média dos últimos 20 dias", rangeAdr), rangeAdr, now));
            }
            // rompimentos: só o mais forte de cada lado
            if (hi20 != null && price > hi20) {
                alerts.add(new Alert("BREAKOUT", "D1", 2, "up", "Preço acima da máxima dos últimos 20 dias", price, now));
            } else if (prev != null && price > prev.high()) {
                alerts.add(new Alert("BREAKOUT", "D1", 1, "up", "Preço acima da máxima do dia anterior", price, now));
            }
            if (lo20 != null && price < lo20) {
                alerts.add(new Alert("BREAKOUT", "D1", 2, "down", "Preço abaixo da mínima dos últimos 20 dias", price, now));
            } else if (prev != null && price < prev.low()) {
                alerts.add(new Alert("BREAKOUT", "D1", 1, "down", "Preço abaixo da mínima do dia anterior", price, now));
            }
        }
        return new Day(today == null ? null : today.open(), today == null ? null : today.high(),
                today == null ? null : today.low(), prev == null ? null : prev.high(), prev == null ? null : prev.low(),
                hi20, lo20, adr, rangeAdr);
    }

    /** Reabertura com salto (fim de semana, abertura do pregão, pausa): buraco de 30+ min e preço 1+ ATR (H1) longe. */
    private static void gaps(List<Bar> m1, Double atrH1, List<Alert> alerts) {
        if (atrH1 == null || atrH1 <= 0) return;
        LocalDateTime from = m1.get(m1.size() - 1).t().minusHours(24);
        for (int k = 1; k < m1.size(); k++) {
            Bar a = m1.get(k - 1), b = m1.get(k);
            if (b.t().isBefore(from) || a.t().plusMinutes(30).isAfter(b.t())) continue;
            double g = (b.open() - a.close()) / atrH1;
            if (Math.abs(g) >= 1) {
                alerts.add(new Alert("GAP", "M1", sev(Math.abs(g), 1, 2, 4), g > 0 ? "up" : "down",
                        f("Gap de %s de %.1f ATR (H1) na reabertura das %02d:%02d UTC", g > 0 ? "alta" : "baixa",
                                Math.abs(g), b.t().getHour(), b.t().getMinute()), g, b.t()));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ força das moedas

    /**
     * Força de cada moeda no forex, por janela: o retorno dela contra o dólar (pelos 7 pares com USD) menos a média
     * das 8 moedas. Positivo = mais forte que a cesta. Em %.
     */
    public static Map<String, Map<String, Double>> currencyStrength(List<Result> fx, List<String> windows) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (String w : windows) {
            Map<String, Double> vsUsd = new LinkedHashMap<>();
            vsUsd.put("USD", 0.0);
            for (Result r : fx) {
                Move m = r.moves().stream().filter(x -> x.window().equals(w)).findFirst().orElse(null);
                if (m == null || m.pct() == null || r.symbol().length() != 6) continue;
                String base = r.symbol().substring(0, 3), quote = r.symbol().substring(3);
                if (quote.equals("USD")) vsUsd.put(base, m.pct());
                else if (base.equals("USD")) vsUsd.put(quote, -m.pct());
            }
            if (vsUsd.size() < 3) continue;
            double mean = vsUsd.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            Map<String, Double> s = new LinkedHashMap<>();
            vsUsd.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .forEach(e -> s.put(e.getKey(), e.getValue() - mean));
            out.put(w, s);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ utilidades

    private static int sev(double v, double t1, double t2, double t3) {
        return v >= t3 ? 3 : v >= t2 ? 2 : 1;
    }

    private static Instant utc(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    private static Double num(double v) {
        return Double.isFinite(v) ? v : null;
    }

    private static String f(String fmt, Object... args) {
        return String.format(PT, fmt, args);
    }
}
