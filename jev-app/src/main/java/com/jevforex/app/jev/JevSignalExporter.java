package com.jevforex.app.jev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevforex.app.persistence.JevCallRepository;
import com.jevforex.lake.LakeSql;
import com.jevforex.typesafe.model.JevResponse;
import com.jevforex.typesafe.signal.CbShiftSignal;
import com.jevforex.typesafe.signal.CbTextSignal;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Respostas do Jev (jev_call) → gold (documento mestre, capítulos 6 e 9):
 *
 * <pre>
 * gold/jev_answers/market=fx/qset=…/model=…/year=…       1 linha por trecho: probabilidades e sinal do trecho
 * gold/currency_signals/market=fx/qset=…/model=…/year=…  1 linha por documento: sinal para a moeda do emissor
 * </pre>
 * Sinal do trecho = stance × (0,5 + 0,5 × magnitude) × peso (CbTextSignal; peso 0 se confiança &lt; 0,60).
 * Sinal do documento = média dos trechos ponderada por market_relevant. Disponível em available_utc do
 * documento (point-in-time do silver).
 */
@Component
public class JevSignalExporter {

    static final double MIN_CONFIDENCE = 0.60;

    private final JevCallRepository calls;
    private final ObjectMapper mapper;

    public JevSignalExporter(JevCallRepository calls, ObjectMapper mapper) {
        this.calls = calls;
        this.mapper = mapper;
    }

    public record CurrencyStats(String currency, long docs, long weighted, double meanSignal, double minSignal,
                                double maxSignal) {
    }

    /** Divulgações por tipo: quantas, quantas com surpresa calculável e a surpresa média em módulo. */
    public record KindStats(String kind, long releases, long withSurprise, double meanAbsSurprise) {
    }

    public record Report(int answers, long chunksMatched, long docs, List<CurrencyStats> currencies,
                         List<KindStats> kinds, Path answersOut, Path signalsOut, Path toneOut) {
    }

    public Report export(LakeSql sql, Path lakeRoot, String qset, String model) throws Exception {
        List<JevCallRepository.Answer> answers = calls.answers(qset, model);
        String part = "market=fx/qset=" + qset + "/model=" + model;
        Path answersOut = lakeRoot.resolve("gold/jev_answers/" + part);
        Path signalsOut = lakeRoot.resolve("gold/currency_signals/" + part);
        if (answers.isEmpty()) {
            return new Report(0, 0, 0, List.of(), List.of(), answersOut, signalsOut,
                    lakeRoot.resolve("gold/tone_surprises/" + part));
        }

        List<Object[]> rows = new ArrayList<>(answers.size());
        for (JevCallRepository.Answer a : answers) {
            JevResponse r = new JevResponse(null, mapper.readTree(a.responseJson()), 0);
            Object[] head = {a.textSha(), a.docSha(), a.chunkIdx(), a.modelResolved(), a.calledAt().toString()};
            Object[] tail;
            var stance = r.choice("policy_stance");
            var relative = r.choice("relative_stance");
            if (stance.isPresent()) {
                CbTextSignal s = CbTextSignal.from(r, MIN_CONFIDENCE);
                tail = new Object[]{stance.get().choice(), stance.get().p("hawkish"), stance.get().p("dovish"),
                        stance.get().p("neutral"), stance.get().p("not_policy"), s.confidence(), s.magnitude(),
                        r.noul("guidance_change").orElse(null),
                        r.score("inflation_focus").map(JevResponse.ScoreAnswer::normalized).orElse(null),
                        s.marketRelevant(), s.stance(), s.weight(), s.signal()};
            } else if (relative.isPresent()) {
                // cb-text-v2: as mesmas colunas, com o sentido da MUDANÇA (hawkish = mais hawkish que antes;
                // neutral = sem mudança; not_policy = não comparável; stance = P(mais hawkish) − P(mais dovish))
                CbShiftSignal s = CbShiftSignal.from(r, MIN_CONFIDENCE);
                tail = new Object[]{relative.get().choice(), relative.get().p("more_hawkish"),
                        relative.get().p("more_dovish"), relative.get().p("unchanged"),
                        relative.get().p("not_comparable"), s.confidence(), s.magnitude(),
                        r.noul("guidance_change").orElse(null), null, s.marketRelevant(), s.shift(), s.weight(),
                        s.signal()};
            } else {
                continue;
            }
            Object[] row = new Object[head.length + tail.length];
            System.arraycopy(head, 0, row, 0, head.length);
            System.arraycopy(tail, 0, row, head.length, tail.length);
            rows.add(row);
        }
        LinkedHashMap<String, String> cols = new LinkedHashMap<>();
        for (String c : List.of("text_sha", "doc_sha")) cols.put(c, "VARCHAR");
        cols.put("chunk_idx", "INTEGER");
        cols.put("model_resolved", "VARCHAR");
        cols.put("called_at", "VARCHAR");
        cols.put("policy_stance", "VARCHAR");
        for (String c : List.of("p_hawkish", "p_dovish", "p_neutral", "p_not_policy", "confidence", "magnitude",
                "guidance_change", "inflation_focus", "market_relevant", "stance", "weight", "signal")) {
            cols.put(c, "DOUBLE");
        }
        sql.createTableFromRows("jev_ans", cols, rows, lakeRoot.resolve("tmp"));

        Path docs = lakeRoot.resolve("silver/documents");
        if (!Files.isDirectory(docs)) throw new IllegalStateException("Sem silver/documents. Rode antes: normalize");
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE ans AS
                SELECT a.*, d.source, d.feed_id, d.issuer, d.currency, d.doc_kind, d.title, d.url, d.available_utc,
                       d.availability_estimated, year(d.available_utc) AS year
                  FROM jev_ans a
                  JOIN (SELECT DISTINCT doc_sha, text_sha, source, feed_id, issuer, currency, doc_kind, title, url,
                               available_utc, availability_estimated
                          FROM read_parquet('%s/**/*.parquet', hive_partitioning = true)) d
                    ON d.text_sha = a.text_sha AND d.doc_sha = a.doc_sha
                """.formatted(LakeSql.slashes(docs)));
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE sig AS
                SELECT doc_sha, any_value(source) AS source, any_value(feed_id) AS feed_id, any_value(issuer) AS issuer,
                       any_value(currency) AS currency, any_value(doc_kind) AS doc_kind, any_value(title) AS title,
                       any_value(url) AS url,
                       min(available_utc) AS available_utc, bool_or(availability_estimated) AS availability_estimated,
                       count(*) AS chunks,
                       coalesce(sum(signal * market_relevant) / nullif(sum(market_relevant), 0), 0) AS signal,
                       coalesce(sum(stance * market_relevant) / nullif(sum(market_relevant), 0), 0) AS stance,
                       max(market_relevant) AS relevance, avg(confidence) AS confidence,
                       max(guidance_change) AS guidance_change, avg(inflation_focus) AS inflation_focus,
                       sum(weight) AS total_weight, year(min(available_utc)) AS year
                  FROM ans GROUP BY doc_sha
                """);
        // surpresa de tom por DIVULGAÇÃO (banco + tipo + horário: página e anexos juntos):
        //   comunicados/atas: tom − tom da divulgação anterior do mesmo banco e tipo;
        //   discursos: tom − média dos 10 discursos anteriores do mesmo banco.
        // Só usa divulgações anteriores (point-in-time).
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE rel AS
                SELECT issuer, currency, doc_kind, available_utc, bool_or(availability_estimated) AS availability_estimated,
                       sum(stance * relevance) / nullif(sum(relevance), 0) AS stance,
                       max(relevance) AS relevance, count(*) AS docs
                  FROM sig WHERE doc_kind IS NOT NULL
                 GROUP BY issuer, currency, doc_kind, available_utc
                """);
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE tone AS
                SELECT *,
                       CASE WHEN doc_kind = 'speech'
                            THEN stance - avg(stance) OVER (PARTITION BY issuer, doc_kind ORDER BY available_utc
                                                            ROWS BETWEEN 10 PRECEDING AND 1 PRECEDING)
                            ELSE stance - lag(stance) OVER (PARTITION BY issuer, doc_kind ORDER BY available_utc) END
                           AS tone_surprise,
                       year(available_utc) AS year
                  FROM rel
                """);
        Path toneOut = lakeRoot.resolve("gold/tone_surprises/" + part);
        write(sql, "SELECT * FROM ans ORDER BY available_utc, doc_sha, chunk_idx", answersOut);
        write(sql, "SELECT * FROM sig ORDER BY available_utc, doc_sha", signalsOut);
        write(sql, "SELECT * FROM tone ORDER BY available_utc, issuer, doc_kind", toneOut);
        List<KindStats> kinds = sql.query("""
                SELECT doc_kind, count(*), count(tone_surprise), avg(abs(tone_surprise))
                  FROM tone GROUP BY doc_kind ORDER BY count(*) DESC
                """, rs -> new KindStats(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getDouble(4)));

        List<CurrencyStats> stats = sql.query("""
                SELECT currency, count(*), count(*) FILTER (WHERE total_weight > 0), avg(signal), min(signal), max(signal)
                  FROM sig GROUP BY currency ORDER BY currency
                """, rs -> new CurrencyStats(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getDouble(4),
                rs.getDouble(5), rs.getDouble(6)));
        return new Report(rows.size(), sql.scalar("SELECT count(*) FROM ans"), sql.scalar("SELECT count(*) FROM sig"),
                stats, kinds, answersOut, signalsOut, toneOut);
    }

    private static void write(LakeSql sql, String select, Path out) {
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        LakeSql.deleteRecursively(tmp);
        out.getParent().toFile().mkdirs();
        sql.execute("COPY (" + select + ") TO " + LakeSql.literal(tmp)
                + " (FORMAT PARQUET, COMPRESSION ZSTD, PARTITION_BY (year))");
        LakeSql.replaceDirectory(tmp, out);
    }
}
