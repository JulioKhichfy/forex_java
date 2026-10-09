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

    record Chunk(String docSha, String textSha, int chunkIdx, String source, String issuer, String currency,
                 String market, String title, String text) {
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
        List<Chunk> chunks = load(sql, lakeRoot, since);
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
        ObjectNode req = mapper.createObjectNode();
        req.put("model", model);
        req.set("state", state);
        req.set("questions", questions);
        String json = req.toString();
        return new Request(c, state, json, LocalDiskLakeStorage.sha256(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Chunk> load(LakeSql sql, Path lakeRoot, LocalDate since) {
        Path docs = lakeRoot.resolve("silver/documents");
        if (!Files.isDirectory(docs)) throw new IllegalStateException("Sem silver/documents. Rode antes: normalize --only=documents");
        return sql.query("""
                SELECT doc_sha, text_sha, chunk_idx, source, issuer, currency, market, title, text
                  FROM read_parquet('%s/**/*.parquet', hive_partitioning = true)
                 WHERE chars > 0 AND doc_type = 'cb_text' AND available_utc >= TIMESTAMP '%s 00:00:00'
                 ORDER BY available_utc, doc_sha, chunk_idx
                """.formatted(LakeSql.slashes(docs), since), rs -> new Chunk(rs.getString(1), rs.getString(2),
                rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9)));
    }
}
