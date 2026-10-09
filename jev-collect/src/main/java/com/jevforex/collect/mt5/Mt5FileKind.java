package com.jevforex.collect.mt5;

import java.util.List;
import java.util.Optional;

/**
 * Tipos de arquivo que os programas MQL5 escrevem. O tipo vem do prefixo do nome:
 * {@code <tipo>_<AAAAMMDDTHHMMSSZ>_<etiqueta>.csv} (ex.: candles_20261008T190100Z_live-000042.csv).
 */
public enum Mt5FileKind {

    /** Valores do calendário (CalendarValueHistory / CalendarValueLast). */
    CALENDAR("calendar", "mt5_calendar", List.of(
            "value_id", "event_id", "event_code", "currency", "country", "importance", "time_server", "time_utc",
            "period", "revision", "actual_raw", "forecast_raw", "prev_raw", "revised_prev_raw", "impact")),

    /** Dicionário de eventos (CalendarEventByCurrency). */
    CALEVENTS("calevents", "mt5_calendar_events", List.of(
            "event_id", "event_code", "name", "currency", "country", "importance", "type", "sector", "frequency",
            "time_mode", "unit", "multiplier", "digits", "source_url")),

    /** Barras M1 fechadas (CopyRates). */
    CANDLES("candles", "mt5_candles", List.of(
            "market", "symbol", "broker_symbol", "time_server", "time_utc", "open", "high", "low", "close",
            "tick_volume", "spread_points", "real_volume")),

    /** Especificações dos símbolos na corretora (SymbolInfo*). */
    SYMBOLS("symbols", "mt5_symbols", List.of(
            "market", "symbol", "broker_symbol", "digits", "point", "tick_size", "tick_value", "tick_value_profit",
            "tick_value_loss", "contract_size", "volume_min", "volume_step", "volume_max", "currency_base",
            "currency_profit", "currency_margin", "account_currency", "spread_points", "stops_level",
            "freeze_level", "swap_long", "swap_short", "trade_mode", "bid", "ask"));

    /** Campos obrigatórios da linha #meta, em todos os tipos. */
    public static final List<String> META = List.of("exporter", "server", "login", "offset_s", "origin", "seen_utc");

    private final String prefix;
    private final String lakeSource;
    private final List<String> columns;

    Mt5FileKind(String prefix, String lakeSource, List<String> columns) {
        this.prefix = prefix;
        this.lakeSource = lakeSource;
        this.columns = columns;
    }

    public String prefix() {
        return prefix;
    }

    /** Pasta no bronze (bronze/&lt;source&gt;/date=…). */
    public String lakeSource() {
        return lakeSource;
    }

    public List<String> columns() {
        return columns;
    }

    public static Optional<Mt5FileKind> fromFileName(String fileName) {
        int us = fileName.indexOf('_');
        if (us <= 0 || !fileName.endsWith(".csv")) return Optional.empty();
        String p = fileName.substring(0, us);
        for (Mt5FileKind k : values()) {
            if (k.prefix.equals(p)) return Optional.of(k);
        }
        return Optional.empty();
    }
}
