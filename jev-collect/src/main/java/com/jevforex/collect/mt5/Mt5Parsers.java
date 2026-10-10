package com.jevforex.collect.mt5;

import com.jevforex.core.BrokerSymbols;
import com.jevforex.lake.LocalDiskLakeStorage;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converte as linhas dos arquivos do MT5 em registros. Sem banco e sem Spring: testável isoladamente.
 * Valida TUDO antes de qualquer gravação: um arquivo com uma linha ruim não entra pela metade.
 */
public final class Mt5Parsers {

    /** O MT5 marca valor ausente do calendário com LONG_MIN. */
    static final String MQL_LONG_MIN = String.valueOf(Long.MIN_VALUE);
    private static final Set<String> ORIGINS = Set.of("LIVE", "SNAPSHOT", "HISTORY");
    private static final Set<String> MARKETS = Set.of("fx", "metals", "crypto", "indices", "stocks");

    private Mt5Parsers() {
    }

    /** Dados da linha #meta, comuns a todos os tipos. */
    public record FileMeta(String exporter, String server, long login, int offsetSeconds, String origin,
                           Instant seenUtc) {

        public static FileMeta of(Mt5Csv csv) {
            csv.requireMeta(Mt5FileKind.META);
            String origin = csv.meta("origin").toUpperCase();
            if (!ORIGINS.contains(origin)) throw new IllegalArgumentException("origin inválido: " + origin);
            try {
                return new FileMeta(csv.meta("exporter"), csv.meta("server"), Long.parseLong(csv.meta("login")),
                        Integer.parseInt(csv.meta("offset_s")), origin, Instant.parse(csv.meta("seen_utc")));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("meta inválida: " + csv.meta(), e);
            }
        }
    }

    // ------------------------------------------------------------------ calendário

    public record CalendarValue(long valueId, long eventId, String eventCode, String currency, String country,
                                String importance, Instant scheduledAt, int offsetSeconds, LocalDate period,
                                Integer revision, BigDecimal actual, BigDecimal forecast, BigDecimal previous,
                                BigDecimal revisedPrevious, String impact, String origin, String stateSha,
                                Instant firstSeenAt) {
    }

    public static List<CalendarValue> calendar(Mt5Csv csv, FileMeta meta) {
        csv.requireColumns(Mt5FileKind.CALENDAR.columns());
        List<CalendarValue> out = new ArrayList<>(csv.rows().size());
        for (Mt5Csv.Row r : csv.rows()) {
            Instant utc = checkedUtc(r, meta);
            String actual = r.str("actual_raw"), forecast = r.str("forecast_raw"),
                    prev = r.str("prev_raw"), revPrev = r.str("revised_prev_raw");
            String period = r.opt("period");
            // o "estado" de um valor: muda quando sai o actual, quando o forecast é revisto etc.
            String state = String.join("|", r.str("value_id"), r.str("event_id"), utc.toString(),
                    String.valueOf(r.optInt("revision")), actual, forecast, prev, revPrev,
                    String.valueOf(period), String.valueOf(r.opt("impact")));
            out.add(new CalendarValue(
                    r.lng("value_id"), r.lng("event_id"), r.str("event_code"), currency(r), r.opt("country"),
                    r.str("importance"), utc, meta.offsetSeconds(),
                    period == null ? null : LocalDate.parse(period), r.optInt("revision"),
                    decodeScaled(actual), decodeScaled(forecast), decodeScaled(prev), decodeScaled(revPrev),
                    r.opt("impact"), meta.origin(),
                    LocalDiskLakeStorage.sha256(state.getBytes(StandardCharsets.UTF_8)), meta.seenUtc()));
        }
        return out;
    }

    public record CalendarEventDef(long eventId, String eventCode, String name, String currency, String country,
                                   String importance, String type, String sector, String frequency, String timeMode,
                                   String unit, String multiplier, Integer digits, String sourceUrl) {
    }

    public static List<CalendarEventDef> calendarEvents(Mt5Csv csv) {
        csv.requireColumns(Mt5FileKind.CALEVENTS.columns());
        List<CalendarEventDef> out = new ArrayList<>(csv.rows().size());
        for (Mt5Csv.Row r : csv.rows()) {
            out.add(new CalendarEventDef(r.lng("event_id"), r.str("event_code"), r.str("name"), currency(r),
                    r.opt("country"), r.str("importance"), r.opt("type"), r.opt("sector"), r.opt("frequency"),
                    r.opt("time_mode"), r.opt("unit"), r.opt("multiplier"), r.optInt("digits"),
                    r.opt("source_url")));
        }
        return out;
    }

    /**
     * Valores do calendário chegam como inteiros multiplicados por 10^6; LONG_MIN = ausente
     * (documento mestre, capítulo 5).
     */
    public static BigDecimal decodeScaled(String raw) {
        if (raw == null || raw.isBlank() || MQL_LONG_MIN.equals(raw.trim())) return null;
        BigDecimal v = new BigDecimal(raw.trim()).movePointLeft(6).stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    // ------------------------------------------------------------------ candles

    public record Candle(String market, String symbol, String brokerSymbol, Instant timeUtc, double open,
                         double high, double low, double close, long tickVolume, int spreadPoints, long realVolume) {
    }

    /** Resumo por símbolo de um arquivo de candles (os candles em si ficam só no lake). */
    public record CandleSummary(String market, String symbol, String brokerSymbol, Instant firstBar, Instant lastBar,
                                double lastClose, int lastSpreadPoints, long bars) {
    }

    public static List<Candle> candles(Mt5Csv csv, FileMeta meta, BrokerSymbols symbols) {
        csv.requireColumns(Mt5FileKind.CANDLES.columns());
        List<Candle> out = new ArrayList<>(csv.rows().size());
        for (Mt5Csv.Row r : csv.rows()) {
            Instant utc = checkedUtc(r, meta);
            out.add(new Candle(market(r), checkedSymbol(r, symbols), r.str("broker_symbol"), utc,
                    r.dbl("open"), r.dbl("high"), r.dbl("low"), r.dbl("close"),
                    r.lng("tick_volume"), r.integer("spread_points"), r.lng("real_volume")));
        }
        return out;
    }

    public static List<CandleSummary> summarize(List<Candle> candles) {
        Map<String, CandleSummary> by = new LinkedHashMap<>();
        for (Candle c : candles) {
            String key = c.market() + "|" + c.symbol();
            CandleSummary s = by.get(key);
            if (s == null) {
                by.put(key, new CandleSummary(c.market(), c.symbol(), c.brokerSymbol(), c.timeUtc(), c.timeUtc(),
                        c.close(), c.spreadPoints(), 1));
            } else {
                boolean later = c.timeUtc().isAfter(s.lastBar());
                by.put(key, new CandleSummary(s.market(), s.symbol(), s.brokerSymbol(),
                        c.timeUtc().isBefore(s.firstBar()) ? c.timeUtc() : s.firstBar(),
                        later ? c.timeUtc() : s.lastBar(),
                        later ? c.close() : s.lastClose(),
                        later ? c.spreadPoints() : s.lastSpreadPoints(),
                        s.bars() + 1));
            }
        }
        return List.copyOf(by.values());
    }

    // ------------------------------------------------------------------ especificações

    public record InstrumentSpec(String market, String symbol, String brokerSymbol, int digits, double point,
                                 double tickSize, double tickValue, Double tickValueProfit, Double tickValueLoss,
                                 double contractSize, double volumeMin, double volumeStep, double volumeMax,
                                 String currencyBase, String currencyProfit, String currencyMargin,
                                 String accountCurrency, Integer spreadPoints, Integer stopsLevel,
                                 Integer freezeLevel, Double swapLong, Double swapShort, String tradeMode,
                                 Double bid, Double ask, String server, Instant seenAt) {
    }

    public static List<InstrumentSpec> symbols(Mt5Csv csv, FileMeta meta, BrokerSymbols symbols) {
        csv.requireColumns(Mt5FileKind.SYMBOLS.columns());
        List<InstrumentSpec> out = new ArrayList<>(csv.rows().size());
        for (Mt5Csv.Row r : csv.rows()) {
            InstrumentSpec s = new InstrumentSpec(market(r), checkedSymbol(r, symbols), r.str("broker_symbol"),
                    r.integer("digits"), r.dbl("point"), r.dbl("tick_size"), r.dbl("tick_value"),
                    r.optDbl("tick_value_profit"), r.optDbl("tick_value_loss"), r.dbl("contract_size"),
                    r.dbl("volume_min"), r.dbl("volume_step"), r.dbl("volume_max"), r.opt("currency_base"),
                    r.opt("currency_profit"), r.opt("currency_margin"), r.str("account_currency"),
                    r.optInt("spread_points"), r.optInt("stops_level"), r.optInt("freeze_level"),
                    r.optDbl("swap_long"), r.optDbl("swap_short"), r.opt("trade_mode"), r.optDbl("bid"),
                    r.optDbl("ask"), meta.server(), meta.seenUtc());
            if (s.tickSize() <= 0 || s.tickValue() <= 0 || s.volumeMin() <= 0 || s.volumeStep() <= 0) {
                // símbolo sem cotação ainda (fim de semana, não selecionado): não serve para sizing
                throw new IllegalArgumentException("linha " + r.line() + ": " + s.brokerSymbol()
                        + " com tick_size/tick_value/volume zerados (símbolo sem cotação no MT5?)");
            }
            out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ util

    /** time_utc tem de bater com time_server − offset: protege contra conversão de fuso errada. */
    private static Instant checkedUtc(Mt5Csv.Row r, FileMeta meta) {
        Instant utc = r.instant("time_utc");
        Instant fromServer = r.localDateTime("time_server").toInstant(ZoneOffset.UTC).minusSeconds(meta.offsetSeconds());
        if (!utc.equals(fromServer)) {
            throw new IllegalArgumentException(String.format("linha %d: time_utc %s ≠ time_server %s − offset %ds",
                    r.line(), utc, r.str("time_server"), meta.offsetSeconds()));
        }
        return utc;
    }

    private static String checkedSymbol(Mt5Csv.Row r, BrokerSymbols symbols) {
        String canonical = r.str("symbol");
        String fromBroker = symbols.toCanonical(r.str("broker_symbol"));
        if (!canonical.equals(fromBroker)) {
            throw new IllegalArgumentException("linha " + r.line() + ": symbol " + canonical
                    + " não corresponde a broker_symbol " + r.str("broker_symbol"));
        }
        return canonical;
    }

    private static String currency(Mt5Csv.Row r) {
        String c = r.str("currency");
        if (c.length() != 3) throw new IllegalArgumentException("linha " + r.line() + ": moeda inválida " + c);
        return c;
    }

    private static String market(Mt5Csv.Row r) {
        String m = r.str("market").toLowerCase();
        if (!MARKETS.contains(m)) throw new IllegalArgumentException("linha " + r.line() + ": market inválido " + m);
        return m;
    }
}
