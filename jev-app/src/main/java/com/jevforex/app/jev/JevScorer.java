package com.jevforex.app.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jevforex.app.persistence.JevCallRepository;
import com.jevforex.lake.LakeSql;
import com.jevforex.lake.LocalDiskLakeStorage;
import com.jevforex.typesafe.JevApiException;
import com.jevforex.typesafe.JevClient;
import com.jevforex.typesafe.model.JevResponse;
import com.jevforex.typesafe.questions.QuestionSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * O Jev em escala (passo 4c): avalia os trechos do silver/documents com um conjunto de perguntas.
 *
 * <ul>
 *   <li>Cache: o pedido (state + perguntas + modelo) tem um request_sha; o que já foi respondido com sucesso
 *       nunca é pago de novo.</li>
 *   <li>Custo estimado ANTES de chamar, pela proporção tokens ÷ caracteres medida nas chamadas já feitas.</li>
 *   <li>Teto de gasto e parada imediata em erro de chave/saldo (401, 402, 403).</li>
 * </ul>
 */
@Component
public class JevScorer {

    /** Tokens por caractere do pedido quando ainda não há chamadas para medir (≈ 4 caracteres/token). */
    private static final double DEFAULT_TOKENS_PER_CHAR = 0.25;

    private final JevClient jev;
    private final JevCallRepository calls;
    private final ObjectMapper mapper;
    private final double usdPerMillion;

    public JevScorer(JevClient jev, JevCallRepository calls, ObjectMapper mapper,
                     @Value("${jev.input-price-usd-per-million:0.042}") double usdPerMillion) {
        this.jev = jev;
        this.calls = calls;
        this.mapper = mapper;
        this.usdPerMillion = usdPerMillion;
    }

    /** previousText/previousDate só nos conjuntos com contexto anterior (input.previous). */
    record Chunk(String docSha, String textSha, int chunkIdx, String source, String issuer, String currency,
                 String market, String title, String text, String previousText, String previousDate) {
    }

    private record Request(Chunk chunk, ObjectNode state, String json, String sha) {
    }

    /** Linha do plano: trechos totais, já respondidos e pendentes. */
    public record Line(int chunks, int cached, int pending, long pendingChars) {
    }

    public record Plan(String qset, String model, LocalDate since, int chunks, int cached, int pending,
                       long estTokens, double estUsd, double tokensPerChar, boolean measured,
                       Map<String, Line> bySourceIssuer) {
    }

    public record RunResult(int done, int errors, long tokens, double usd, String stopReason) {
    }

    public Plan plan(LakeSql sql, Path lakeRoot, QuestionSet qs, String model, LocalDate since, int limit) {
        List<Request> pending = new ArrayList<>();
        return plan(sql, lakeRoot, qs, model, since, limit, pending);
    }

    private Plan plan(LakeSql sql, Path lakeRoot, QuestionSet qs, String model, LocalDate since, int limit,
                      List<Request> pendingOut) {
        QuestionSet.Input input = qs.input();
        List<Chunk> chunks = input.withPrevious() ? loadWithPrevious(sql, lakeRoot, since, input.docKinds())
                : load(sql, lakeRoot, since, input.docKinds());
        Set<String> answered = calls.answeredRequests(qs.code(), model);
        JsonNode questions = qs.apiQuestions();
        double ratio = calls.tokensPerRequestChar();
        boolean measured = !Double.isNaN(ratio);
        if (!measured) ratio = DEFAULT_TOKENS_PER_CHAR;

        Map<String, int[]> counts = new TreeMap<>();
        Map<String, long[]> chars = new TreeMap<>();
        int cached = 0;
        long pendingChars = 0;
        for (Chunk c : chunks) {
            Request r = request(c, model, questions);
            String key = c.source() + " · " + c.issuer();
            int[] k = counts.computeIfAbsent(key, x -> new int[3]);
            k[0]++;
            if (answered.contains(r.sha())) {
                k[1]++;
                cached++;
                continue;
            }
            if (limit > 0 && pendingOut.size() >= limit) continue;
            k[2]++;
            pendingOut.add(r);
            pendingChars += r.json().length();
            chars.computeIfAbsent(key, x -> new long[1])[0] += r.json().length();
        }
        Map<String, Line> by = new TreeMap<>();
        counts.forEach((key, k) -> by.put(key, new Line(k[0], k[1], k[2], chars.getOrDefault(key, new long[1])[0])));
        long tokens = Math.round(pendingChars * ratio);
        return new Plan(qs.code(), model, since, chunks.size(), cached, pendingOut.size(), tokens,
                tokens * usdPerMillion / 1_000_000.0, ratio, measured, by);
    }

    /**
     * Chama o Jev para os trechos pendentes.
     *
     * @param maxUsd teto de gasto desta execução (para antes de passar)
     */
    public RunResult run(LakeSql sql, Path lakeRoot, QuestionSet qs, String model, LocalDate since, int limit,
                         int concurrency, double maxUsd, Consumer<String> progress) throws InterruptedException {
        List<Request> pending = new ArrayList<>();
        Plan p = plan(sql, lakeRoot, qs, model, since, limit, pending);
        progress.accept(String.format(java.util.Locale.ROOT, "%d trechos pendentes (%d já no cache) · estimativa "
                + "%,d tokens · US$ %.4f · teto US$ %.2f", p.pending(), p.cached(), p.estTokens(), p.estUsd(), maxUsd));

        AtomicInteger done = new AtomicInteger(), errors = new AtomicInteger();
        AtomicLong tokens = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<String> reason = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, concurrency));
        for (Request r : pending) {
            pool.submit(() -> {
                if (stop.get()) return;
                double spent = tokens.get() * usdPerMillion / 1_000_000.0;
                if (spent >= maxUsd) {
                    if (stop.compareAndSet(false, true)) reason.set(String.format(java.util.Locale.ROOT,
                            "teto de US$ %.2f atingido", maxUsd));
                    return;
                }
                score(qs, model, r, done, errors, tokens, stop, reason);
                int n = done.get() + errors.get();
                if (n % 100 == 0) {
                    progress.accept(String.format(java.util.Locale.ROOT, "  %d/%d · %,d tokens · US$ %.4f · %d erros",
                            n, pending.size(), tokens.get(), tokens.get() * usdPerMillion / 1_000_000.0, errors.get()));
                }
            });
        }
        pool.shutdown();
        while (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
            // espera; o progresso é impresso pelas próprias tarefas
        }
        return new RunResult(done.get(), errors.get(), tokens.get(), tokens.get() * usdPerMillion / 1_000_000.0,
                reason.get());
    }

    private void score(QuestionSet qs, String model, Request r, AtomicInteger done, AtomicInteger errors,
                       AtomicLong tokens, AtomicBoolean stop, AtomicReference<String> reason) {
        Chunk c = r.chunk();
        try {
            JevResponse resp = jev.systemOne(model, r.state(), qs.apiQuestions());
            tokens.addAndGet(resp.inputTokens());
            calls.insert(new JevCallRepository.JevCall(null, c.market(), qs.code(), qs.sha256(), model, resp.model(),
                    r.sha(), r.json(), resp.raw().toString(), 200, resp.inputTokens(), (int) resp.latencyMs(), null,
                    c.source(), c.docSha(), c.textSha(), c.chunkIdx()));
            done.incrementAndGet();
        } catch (JevApiException e) {
            errors.incrementAndGet();
            calls.insert(new JevCallRepository.JevCall(null, c.market(), qs.code(), qs.sha256(), model, null, r.sha(),
                    r.json(), null, e.status(), null, null, e.getMessage(), c.source(), c.docSha(), c.textSha(),
                    c.chunkIdx()));
            if (e.status() == 401 || e.status() == 402 || e.status() == 403) {
                if (stop.compareAndSet(false, true)) reason.set("HTTP " + e.status() + " — " + e.hint());
            }
        } catch (IllegalStateException e) {
            // configuração (ex.: sem chave): nenhum trecho vai funcionar → para tudo, sem registrar como falha do trecho
            errors.incrementAndGet();
            if (stop.compareAndSet(false, true)) reason.set(e.getMessage());
        } catch (RuntimeException e) {
            // falha fora da API (timeout, rede): registra para saber o motivo; a próxima execução tenta de novo
            if (errors.incrementAndGet() <= 5) log.warn("Trecho {} falhou: {}", c.textSha(), e.toString());
            try {
                calls.insert(new JevCallRepository.JevCall(null, c.market(), qs.code(), qs.sha256(), model, null,
                        r.sha(), r.json(), null, null, null, null, e.toString(), c.source(), c.docSha(), c.textSha(),
                        c.chunkIdx()));
            } catch (RuntimeException ignored) {
                // sem banco não há o que registrar
            }
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JevScorer.class);

    /** O mesmo formato do comando ask: o cache vale entre os dois. */
    private Request request(Chunk c, String model, JsonNode questions) {
        ObjectNode state = mapper.createObjectNode();
        state.put("issuer", c.issuer());
        state.put("currency", c.currency());
        if (c.title() != null) state.put("title", c.title());
        state.put("text", c.text());
        if (c.previousText() != null) {
            state.put("previous_text", c.previousText());
            state.put("previous_date", c.previousDate());
        }
        ObjectNode req = mapper.createObjectNode();
        req.put("model", model);
        req.set("state", state);
        req.set("questions", questions);
        String json = req.toString();
        return new Request(c, state, json, LocalDiskLakeStorage.sha256(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Chunk> load(LakeSql sql, Path lakeRoot, LocalDate since, List<String> kinds) {
        return sql.query("""
                SELECT doc_sha, text_sha, chunk_idx, source, issuer, currency, market, title, text
                  FROM read_parquet('%s/**/*.parquet', hive_partitioning = true)
                 WHERE chars > 0 AND doc_type = 'cb_text' AND available_utc >= TIMESTAMP '%s 00:00:00' %s
                 ORDER BY available_utc, doc_sha, chunk_idx
                """.formatted(docsPath(lakeRoot), since, kindFilter(kinds)), rs -> new Chunk(rs.getString(1),
                rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), null, null));
    }

    /**
     * Trechos da divulgação PRINCIPAL (o documento com mais texto entre página e anexos do mesmo horário: no Fed a
     * página traz só o resumo e o PDF o comunicado inteiro), cada um com o trecho de mesma posição relativa da
     * divulgação anterior do mesmo emissor e tipo. A primeira divulgação de cada série não tem com o que comparar
     * e fica de fora. A anterior sempre tem available_utc menor (point-in-time).
     */
    static List<Chunk> loadWithPrevious(LakeSql sql, Path lakeRoot, LocalDate since, List<String> kinds) {
        return sql.query("""
                WITH c AS (
                    SELECT DISTINCT doc_sha, text_sha, chunk_idx, source, issuer, currency, market, title, text, chars,
                           doc_kind, available_utc
                      FROM read_parquet('%1$s/**/*.parquet', hive_partitioning = true)
                     WHERE chars > 0 AND doc_type = 'cb_text' AND doc_kind IS NOT NULL %2$s),
                d AS (SELECT issuer, doc_kind, available_utc, doc_sha, sum(chars) AS total, count(*) AS n
                        FROM c GROUP BY ALL),
                p AS (SELECT issuer, doc_kind, available_utc, arg_max(doc_sha, total) AS doc_sha, arg_max(n, total) AS n
                        FROM d GROUP BY ALL),
                r AS (SELECT *, lag(doc_sha) OVER w AS prev_sha, lag(n) OVER w AS prev_n,
                             lag(available_utc) OVER w AS prev_utc
                        FROM p WINDOW w AS (PARTITION BY issuer, doc_kind ORDER BY available_utc)),
                cur AS (SELECT c.*, r.prev_sha, r.prev_n, r.prev_utc,
                               row_number() OVER (PARTITION BY c.doc_sha ORDER BY c.chunk_idx) - 1 AS pos, r.n
                          FROM r JOIN c ON c.doc_sha = r.doc_sha AND c.available_utc = r.available_utc
                         WHERE r.prev_sha IS NOT NULL AND r.available_utc >= TIMESTAMP '%3$s 00:00:00'),
                prev AS (SELECT doc_sha, text,
                                row_number() OVER (PARTITION BY doc_sha ORDER BY chunk_idx) - 1 AS pos FROM c)
                SELECT cur.doc_sha, cur.text_sha, cur.chunk_idx, cur.source, cur.issuer, cur.currency, cur.market,
                       cur.title, cur.text, prev.text, strftime(cur.prev_utc, '%%Y-%%m-%%d')
                  FROM cur JOIN prev ON prev.doc_sha = cur.prev_sha
                   AND prev.pos = CASE WHEN cur.n <= 1 OR cur.prev_n <= 1 THEN 0
                                       ELSE CAST(round(cur.pos * (cur.prev_n - 1) / (cur.n - 1)) AS BIGINT) END
                 ORDER BY cur.available_utc, cur.doc_sha, cur.chunk_idx
                """.formatted(docsPath(lakeRoot), kindFilter(kinds), since), rs -> new Chunk(rs.getString(1),
                rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)));
    }

    private static String docsPath(Path lakeRoot) {
        Path docs = lakeRoot.resolve("silver/documents");
        if (!Files.isDirectory(docs)) throw new IllegalStateException("Sem silver/documents. Rode antes: normalize --only=documents");
        return LakeSql.slashes(docs);
    }

    private static String kindFilter(List<String> kinds) {
        if (kinds.isEmpty()) return "";
        for (String k : kinds) {
            if (!k.matches("[a-z_]+")) throw new IllegalArgumentException("input.doc_kinds inválido: " + k);
        }
        return "AND doc_kind IN (" + String.join(", ", kinds.stream().map(k -> "'" + k + "'").toList()) + ")";
    }
}
