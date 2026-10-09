package com.jevforex.collect.mt5;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Arquivo CSV escrito pelos programas MQL5 em Common\Files\jev\inbox.
 *
 * <pre>
 * #exporter=JevCandleExporter/1.0;server=Exness-MT5Trial11;login=123;offset_s=0;origin=LIVE;seen_utc=2026-10-08T19:01:00Z
 * coluna1;coluna2;...
 * valor1;valor2;...
 * </pre>
 * A primeira linha (meta) descreve o arquivo inteiro; a segunda é o cabeçalho. Separador: ponto e vírgula.
 */
public final class Mt5Csv {

    private final Map<String, String> meta;
    private final List<String> header;
    private final Map<String, Integer> index;
    private final List<Row> rows;

    private Mt5Csv(Map<String, String> meta, List<String> header, List<Row> rows) {
        this.meta = meta;
        this.header = header;
        this.rows = rows;
        this.index = new HashMap<>();
        for (int i = 0; i < header.size(); i++) index.put(header.get(i), i);
    }

    public static Mt5Csv parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        String[] lines = text.split("\r?\n");

        int i = 0;
        Map<String, String> meta = new LinkedHashMap<>();
        if (lines.length > 0 && lines[0].startsWith("#")) {
            for (String kv : lines[0].substring(1).split(";")) {
                int eq = kv.indexOf('=');
                if (eq > 0) meta.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
            i = 1;
        }
        while (i < lines.length && lines[i].isBlank()) i++;
        if (i >= lines.length) throw new IllegalArgumentException("arquivo sem cabeçalho");
        List<String> header = Arrays.stream(lines[i].split(";", -1)).map(String::trim).toList();

        List<Row> rows = new ArrayList<>();
        Mt5Csv csv = new Mt5Csv(Collections.unmodifiableMap(meta), header, rows);
        for (int n = i + 1; n < lines.length; n++) {
            if (lines[n].isBlank()) continue;
            String[] f = lines[n].split(";", -1);
            if (f.length != header.size()) {
                throw new IllegalArgumentException(String.format("linha %d: %d campos, cabeçalho tem %d",
                        n + 1, f.length, header.size()));
            }
            rows.add(csv.new Row(n + 1, f));
        }
        return csv;
    }

    public Map<String, String> meta() {
        return meta;
    }

    public String meta(String key) {
        String v = meta.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("meta sem o campo " + key);
        return v;
    }

    public List<String> header() {
        return header;
    }

    public List<Row> rows() {
        return Collections.unmodifiableList(rows);
    }

    public void requireMeta(List<String> keys) {
        for (String k : keys) meta(k);
    }

    public void requireColumns(List<String> cols) {
        List<String> missing = cols.stream().filter(c -> !index.containsKey(c)).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("colunas ausentes: " + missing);
    }

    /** Uma linha de dados. Os getters indicam a linha no erro, para achar o problema no arquivo. */
    public final class Row {
        private final int line;
        private final String[] fields;

        private Row(int line, String[] fields) {
            this.line = line;
            this.fields = fields;
        }

        public int line() {
            return line;
        }

        public String str(String col) {
            Integer i = index.get(col);
            if (i == null) throw new IllegalArgumentException("coluna inexistente: " + col);
            return fields[i].trim();
        }

        /** Valor ou null quando vazio. */
        public String opt(String col) {
            String v = str(col);
            return v.isEmpty() ? null : v;
        }

        public long lng(String col) {
            return parse(col, () -> Long.parseLong(str(col)));
        }

        public int integer(String col) {
            return parse(col, () -> Integer.parseInt(str(col)));
        }

        public Integer optInt(String col) {
            return opt(col) == null ? null : integer(col);
        }

        public double dbl(String col) {
            return parse(col, () -> Double.parseDouble(str(col)));
        }

        public Double optDbl(String col) {
            return opt(col) == null ? null : dbl(col);
        }

        public Instant instant(String col) {
            return parse(col, () -> Instant.parse(str(col)));
        }

        public LocalDateTime localDateTime(String col) {
            return parse(col, () -> LocalDateTime.parse(str(col)));
        }

        private <T> T parse(String col, java.util.function.Supplier<T> f) {
            try {
                return f.get();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(String.format("linha %d, coluna %s: valor inválido \"%s\"",
                        line, col, index.containsKey(col) ? fields[index.get(col)] : "?"), e);
            }
        }
    }
}
