package com.jevforex.normalize;

import java.util.ArrayList;
import java.util.List;

/**
 * Divide um texto em trechos de até {@code maxChars}, respeitando parágrafos (documento mestre, cap. 9:
 * ~1.500 tokens por chamada ao Jev ≈ 6.000 caracteres). Parágrafo maior que o limite é cortado em frases;
 * frase maior que o limite, no espaço mais próximo.
 */
public final class Chunker {

    public static final int DEFAULT_MAX_CHARS = 6000;

    private Chunker() {
    }

    public static List<String> split(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        StringBuilder cur = new StringBuilder();
        for (String para : text.split("\n\n")) {
            for (String piece : fit(para.trim(), maxChars)) {
                if (piece.isEmpty()) continue;
                if (!cur.isEmpty() && cur.length() + 2 + piece.length() > maxChars) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                if (!cur.isEmpty()) cur.append("\n\n");
                cur.append(piece);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    /** Um parágrafo em pedaços que cabem no limite. */
    private static List<String> fit(String para, int max) {
        if (para.length() <= max) return List.of(para);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sentence : para.split("(?<=[.!?])\\s+")) {
            for (String part : hardSplit(sentence, max)) {
                if (!cur.isEmpty() && cur.length() + 1 + part.length() > max) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                if (!cur.isEmpty()) cur.append(' ');
                cur.append(part);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private static List<String> hardSplit(String s, int max) {
        List<String> out = new ArrayList<>();
        while (s.length() > max) {
            int cut = s.lastIndexOf(' ', max);
            if (cut <= max / 2) cut = max;
            out.add(s.substring(0, cut).trim());
            s = s.substring(cut).trim();
        }
        if (!s.isEmpty()) out.add(s);
        return out;
    }
}
