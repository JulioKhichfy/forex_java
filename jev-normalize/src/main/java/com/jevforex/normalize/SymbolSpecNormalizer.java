package com.jevforex.normalize;

import com.jevforex.lake.LakeSql;

import java.nio.file.Path;

/**
 * bronze/mt5_symbols → silver/instrument_specs/part-0.parquet: a especificação mais recente de cada símbolo
 * (point, dígitos, tick size/value, lotes). As features usam o point para converter spread em preço.
 */
public final class SymbolSpecNormalizer {

    public static final String SOURCE = "mt5_symbols";
    public static final String TABLE = "instrument_specs";

    private final Path lakeRoot;

    public SymbolSpecNormalizer(Path lakeRoot) {
        this.lakeRoot = lakeRoot;
    }

    /** @return quantos símbolos foram gravados (0 = ainda não há exportação de especificações) */
    public long run(LakeSql sql) {
        if (!Bronze.hasCsv(lakeRoot, SOURCE)) return 0;
        Bronze.createMetaTable(sql, lakeRoot, SOURCE, "spec_meta");
        String text = "'VARCHAR'";
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE specs AS
                SELECT r.market, r.symbol, r.broker_symbol,
                       CAST(r.digits AS INTEGER) AS digits, CAST(r.point AS DOUBLE) AS point,
                       CAST(r.tick_size AS DOUBLE) AS tick_size, CAST(r.tick_value AS DOUBLE) AS tick_value,
                       CAST(r.contract_size AS DOUBLE) AS contract_size, CAST(r.volume_min AS DOUBLE) AS volume_min,
                       CAST(r.volume_step AS DOUBLE) AS volume_step, CAST(r.volume_max AS DOUBLE) AS volume_max,
                       r.currency_base, r.currency_profit, r.account_currency, m.seen_utc
                  FROM read_csv(%s, skip = 1, header = true, delim = ';', filename = true, auto_detect = false,
                                columns = {'market': %2$s, 'symbol': %2$s, 'broker_symbol': %2$s, 'digits': %2$s,
                                           'point': %2$s, 'tick_size': %2$s, 'tick_value': %2$s,
                                           'tick_value_profit': %2$s, 'tick_value_loss': %2$s,
                                           'contract_size': %2$s, 'volume_min': %2$s, 'volume_step': %2$s,
                                           'volume_max': %2$s, 'currency_base': %2$s, 'currency_profit': %2$s,
                                           'currency_margin': %2$s, 'account_currency': %2$s,
                                           'spread_points': %2$s, 'stops_level': %2$s, 'freeze_level': %2$s,
                                           'swap_long': %2$s, 'swap_short': %2$s, 'trade_mode': %2$s, 'bid': %2$s,
                                           'ask': %2$s}) r
                  JOIN spec_meta m ON m.sha = %3$s
                QUALIFY row_number() OVER (PARTITION BY r.market, r.symbol ORDER BY m.seen_utc DESC) = 1
                """.formatted(Bronze.csvGlob(lakeRoot, SOURCE), text, Bronze.shaOf("r.filename")));
        Path out = lakeRoot.resolve("silver").resolve(TABLE);
        Path tmp = out.resolveSibling(TABLE + ".tmp");
        LakeSql.deleteRecursively(tmp);
        tmp.toFile().mkdirs();
        sql.execute("COPY (SELECT * FROM specs ORDER BY market, symbol) TO "
                + LakeSql.literal(tmp.resolve("part-0.parquet")) + " (FORMAT PARQUET)");
        LakeSql.replaceDirectory(tmp, out);
        return sql.scalar("SELECT count(*) FROM specs");
    }
}
