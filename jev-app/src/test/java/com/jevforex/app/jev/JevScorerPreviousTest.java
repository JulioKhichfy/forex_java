package com.jevforex.app.jev;

import com.jevforex.lake.LakeSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JevScorerPreviousTest {

    private static String row(String doc, int idx, String kind, String utc, String text) {
        return "('" + doc + "', '" + doc + "-" + idx + "', " + idx + ", 'archive', 'Federal Reserve', 'USD', 'fx', "
                + "'t', '" + text + "', " + text.length() + ", '" + kind + "', TIMESTAMP '" + utc + "', 'cb_text', 2026)";
    }

    @Test
    void comparaComADivulgacaoAnterior_documentoPrincipal_posicaoRelativa(@TempDir Path lake) {
        Path docs = lake.resolve("silver/documents");
        docs.getParent().toFile().mkdirs();
        List<List<String>> chunks = List.of(
                // comunicado: página curta e PDF completo no mesmo horário → o PDF é o principal
                List.of(row("p1", 0, "statement", "2026-01-28 19:00:30", "resumo 1")),
                List.of(row("f1", 0, "statement", "2026-01-28 19:00:30", "comunicado completo de janeiro")),
                List.of(row("p2", 0, "statement", "2026-03-18 18:00:30", "resumo 2")),
                List.of(row("f2", 0, "statement", "2026-03-18 18:00:30", "comunicado completo de marco")),
                // atas: 3 trechos e depois 5 trechos
                List.of(row("m1", 0, "minutes", "2026-02-18 19:00:30", "a0"), row("m1", 1, "minutes",
                        "2026-02-18 19:00:30", "a1"), row("m1", 2, "minutes", "2026-02-18 19:00:30", "a2")),
                List.of(row("m2", 0, "minutes", "2026-04-08 18:00:30", "b0"), row("m2", 1, "minutes",
                        "2026-04-08 18:00:30", "b1"), row("m2", 2, "minutes", "2026-04-08 18:00:30", "b2"),
                        row("m2", 3, "minutes", "2026-04-08 18:00:30", "b3"), row("m2", 4, "minutes",
                        "2026-04-08 18:00:30", "b4")),
                // discurso: fora dos tipos pedidos
                List.of(row("s1", 0, "speech", "2026-03-01 23:59:59", "discurso"),
                        row("s2", 0, "speech", "2026-03-02 23:59:59", "discurso 2")));
        String values = chunks.stream().flatMap(List::stream).collect(Collectors.joining(", "));
        try (LakeSql sql = LakeSql.open(lake.resolve("tmp"), "1GB")) {
            sql.execute("COPY (SELECT * FROM (VALUES " + values + ") t(doc_sha, text_sha, chunk_idx, source, issuer, "
                    + "currency, market, title, text, chars, doc_kind, available_utc, doc_type, year)) TO "
                    + LakeSql.literal(docs) + " (FORMAT PARQUET, PARTITION_BY (year))");

            List<JevScorer.Chunk> out = JevScorer.loadWithPrevious(sql, lake, LocalDate.of(2021, 1, 1),
                    List.of("statement", "minutes"));
            Map<String, JevScorer.Chunk> byText = out.stream()
                    .collect(Collectors.toMap(JevScorer.Chunk::textSha, c -> c));

            // primeira divulgação de cada série não tem com o que comparar; páginas-resumo e discursos ficam de fora
            assertEquals(6, out.size());
            assertEquals("comunicado completo de janeiro", byText.get("f2-0").previousText());
            assertEquals("2026-01-28", byText.get("f2-0").previousDate());
            assertFalse(byText.containsKey("p2-0"));
            // 5 trechos contra 3: posição relativa i × (3 − 1) / (5 − 1)
            assertEquals("a0", byText.get("m2-0").previousText());
            assertEquals("a1", byText.get("m2-2").previousText());
            assertEquals("a2", byText.get("m2-4").previousText());
            assertEquals("2026-02-18", byText.get("m2-4").previousDate());

            // corte por data: só divulgações a partir de since (a anterior pode ser mais antiga)
            assertEquals(5, JevScorer.loadWithPrevious(sql, lake, LocalDate.of(2026, 4, 1),
                    List.of("statement", "minutes")).size());
        }
    }
}
