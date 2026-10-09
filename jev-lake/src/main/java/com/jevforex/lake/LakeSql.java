package com.jevforex.lake;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * DuckDB embarcado para ler o bronze e escrever Parquet no silver/gold (documento mestre, capítulo 6).
 * Sem servidor: uma conexão em memória por execução; o que não couber vai para {@code tempDir}.
 */
public final class LakeSql implements AutoCloseable {

    private final Connection conn;

    private LakeSql(Connection conn) {
        this.conn = conn;
    }

    /**
     * @param tempDir     pasta para o DuckDB despejar dados quando passar do limite de memória
     * @param memoryLimit ex.: "2GB" (vazio = padrão do DuckDB, 80% da RAM)
     */
    public static LakeSql open(Path tempDir, String memoryLimit) {
        try {
            Class.forName("org.duckdb.DuckDBDriver");
            Files.createDirectories(tempDir);
            LakeSql s = new LakeSql(DriverManager.getConnection("jdbc:duckdb:"));
            s.execute("SET temp_directory = " + literal(tempDir));
            if (memoryLimit != null && !memoryLimit.isBlank()) {
                s.execute("SET memory_limit = '" + memoryLimit.replace("'", "") + "'");
            }
            s.execute("SET preserve_insertion_order = false");   // menos memória em COPY grandes
            return s;
        } catch (ClassNotFoundException | SQLException e) {
            throw new IllegalStateException("Não consegui abrir o DuckDB: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void execute(String sql) {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("DuckDB: " + e.getMessage() + "\nSQL: " + sql, e);
        }
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper) {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            List<T> out = new ArrayList<>();
            while (rs.next()) out.add(mapper.map(rs));
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("DuckDB: " + e.getMessage() + "\nSQL: " + sql, e);
        }
    }

    /**
     * Cria {@code table} com linhas produzidas em Java, passando por um arquivo JSON-por-linha temporário.
     * É o caminho para volumes grandes ou texto livre (aspas, quebras de linha): inserir dezenas de milhares
     * de linhas pelo JDBC esgota a memória do DuckDB.
     *
     * @param columns nome → tipo DuckDB, na ordem dos valores de cada linha
     */
    public void createTableFromRows(String table, java.util.LinkedHashMap<String, String> columns, List<Object[]> rows,
                                    Path tmpDir) {
        List<String> names = new ArrayList<>(columns.keySet());
        Path file = tmpDir.resolve(table + "-" + System.nanoTime() + ".jsonl");
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            Files.createDirectories(tmpDir);
            try (var w = Files.newBufferedWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
                for (Object[] row : rows) {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < names.size(); i++) m.put(names.get(i), row[i]);
                    w.write(json.writeValueAsString(m));
                    w.write('\n');
                }
            }
            StringBuilder cols = new StringBuilder();
            columns.forEach((k, v) -> cols.append(cols.isEmpty() ? "" : ", ").append("'").append(k).append("': '")
                    .append(v).append("'"));
            execute("CREATE OR REPLACE TEMP TABLE " + table + " AS SELECT * FROM read_json(" + literal(file)
                    + ", format = 'newline_delimited', columns = {" + cols + "})");
        } catch (IOException e) {
            throw new UncheckedIOException("Não consegui preparar " + file, e);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // fica no tmp
            }
        }
    }

    /** INSERT parametrizado em lote — só para poucas linhas (ver {@link #createTableFromRows}). */
    public void batch(String sql, List<Object[]> rows) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    if (row[i] == null) ps.setNull(i + 1, Types.VARCHAR);
                    else ps.setObject(i + 1, row[i]);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("DuckDB: " + e.getMessage() + "\nSQL: " + sql, e);
        }
    }

    /** Primeira coluna da primeira linha como número (count, sum…). */
    public long scalar(String sql) {
        List<Long> v = query(sql, rs -> rs.getLong(1));
        return v.isEmpty() ? 0 : v.get(0);
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** Caminho como literal SQL ('C:/x/y'): barras normais e aspas escapadas. */
    public static String literal(Path p) {
        return "'" + slashes(p).replace("'", "''") + "'";
    }

    /** Caminho com barras normais, para montar globs (ex.: slashes(dir) + "/date=*\/*.csv"). */
    public static String slashes(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /**
     * Troca {@code target} por {@code built} (uma pasta recém-gerada). Quem lê o silver nunca vê
     * uma pasta pela metade: ou a versão anterior inteira, ou a nova inteira.
     */
    public static void replaceDirectory(Path built, Path target) {
        try {
            Path old = target.resolveSibling(target.getFileName() + ".old");
            deleteRecursively(old);
            if (Files.exists(target)) Files.move(target, old);
            Files.move(built, target);
            deleteRecursively(old);
        } catch (IOException e) {
            throw new UncheckedIOException("Não consegui substituir " + target, e);
        }
    }

    public static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        } catch (IOException e) {
            throw new UncheckedIOException("Não consegui apagar " + dir, e);
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // fechando de qualquer jeito
        }
    }
}
