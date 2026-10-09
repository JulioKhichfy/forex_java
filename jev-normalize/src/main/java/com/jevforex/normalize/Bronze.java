package com.jevforex.normalize;

import com.jevforex.lake.LakeSql;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.stream.Stream;

/**
 * Leitura do bronze pelo DuckDB. Cada arquivo do bronze se chama &lt;sha256&gt;.csv e tem ao lado
 * &lt;sha256&gt;.meta.json com a origem e o seen_utc: a junção é pelo hash.
 */
final class Bronze {

    private Bronze() {
    }

    static Path dir(Path lakeRoot, String source) {
        return lakeRoot.resolve("bronze").resolve(source);
    }

    static boolean hasCsv(Path lakeRoot, String source) {
        Path d = dir(lakeRoot, source);
        if (!Files.isDirectory(d)) return false;
        try (Stream<Path> s = Files.walk(d, 2)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(".csv"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 'C:/lake/bronze/mt5_candles/date=*&#47;*.csv' */
    static String csvGlob(Path lakeRoot, String source) {
        return "'" + LakeSql.slashes(dir(lakeRoot, source)) + "/date=*/*.csv'";
    }

    /** Hash do arquivo a partir da coluna filename do read_csv. */
    static String shaOf(String filenameColumn) {
        return "regexp_extract(" + filenameColumn + ", '([0-9a-f]{64})\\.csv$', 1)";
    }

    /** Tabela temporária (sha, origin, seen_utc) com os metadados de todos os arquivos de uma fonte. */
    static void createMetaTable(LakeSql sql, Path lakeRoot, String source, String table) {
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE %s AS
                SELECT sha256 AS sha,
                       upper(origin) AS origin,
                       CAST(replace(seen_utc, 'Z', '') AS TIMESTAMP) AS seen_utc
                  FROM read_json('%s/date=*/*.meta.json',
                                 columns = {sha256: 'VARCHAR', origin: 'VARCHAR', seen_utc: 'VARCHAR'})
                """.formatted(table, LakeSql.slashes(dir(lakeRoot, source))));
    }

    /** '2026-10-09 13:55:00' (CAST de TIMESTAMP para VARCHAR) → LocalDateTime; null passa. */
    static LocalDateTime ts(String s) {
        return s == null ? null : LocalDateTime.parse(s.replace(' ', 'T'));
    }
}
