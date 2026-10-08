package com.jevforex.app.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jevforex.app.config.RiskConfig.InstrumentCatalog;
import com.jevforex.app.config.TradingProperties;
import com.jevforex.app.persistence.JevCallRepository;
import com.jevforex.collect.HtmlText;
import com.jevforex.collect.RawDocumentRepository;
import com.jevforex.collect.rss.FeedCollector;
import com.jevforex.core.Instrument;
import com.jevforex.core.risk.PositionSizer;
import com.jevforex.core.risk.RiskSettings;
import com.jevforex.core.risk.SizingResult;
import com.jevforex.lake.LakeStorage;
import com.jevforex.lake.LocalDiskLakeStorage;
import com.jevforex.typesafe.JevApiException;
import com.jevforex.typesafe.JevClient;
import com.jevforex.typesafe.model.JevResponse;
import com.jevforex.typesafe.questions.QuestionSet;
import com.jevforex.typesafe.questions.QuestionSetRegistry;
import com.jevforex.typesafe.signal.CbTextSignal;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Executa o comando passado na linha de comando (perfil "cli"). */
@Component
@Profile("cli")
public class CliRunner implements ApplicationRunner, ExitCodeGenerator {

    /** ~1.500 tokens de texto por chamada (documento mestre, cap. 9). */
    private static final int MAX_TEXT_CHARS = 6000;
    /** Preço verificado em outubro de 2026: US$ 0,042 por milhão de tokens de entrada. */
    private static final double USD_PER_MILLION_INPUT_TOKENS = 0.042;

    private final JevClient jev;
    private final QuestionSetRegistry questionSets;
    private final JevCallRepository jevCalls;
    private final RawDocumentRepository documents;
    private final FeedCollector collector;
    private final LakeStorage lake;
    private final RiskSettings risk;
    private final InstrumentCatalog catalog;
    private final TradingProperties trading;
    private final ObjectMapper mapper;
    private int exitCode = 0;

    public CliRunner(JevClient jev, QuestionSetRegistry questionSets, JevCallRepository jevCalls,
                     RawDocumentRepository documents, FeedCollector collector, LakeStorage lake,
                     RiskSettings risk, InstrumentCatalog catalog, TradingProperties trading, ObjectMapper mapper) {
        this.jev = jev;
        this.questionSets = questionSets;
        this.jevCalls = jevCalls;
        this.documents = documents;
        this.collector = collector;
        this.lake = lake;
        this.risk = risk;
        this.catalog = catalog;
        this.trading = trading;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> plain = args.getNonOptionArgs();
        String cmd = plain.isEmpty() ? Commands.HELP : plain.get(0);
        try {
            switch (cmd) {
                case Commands.PING -> ping();
                case Commands.RISK -> risk(args);
                case Commands.ASK -> ask(args);
                case Commands.COLLECT_ONCE -> collectOnce();
                default -> System.out.println(Commands.usage());
            }
        } catch (JevApiException e) {
            System.err.printf("%nErro do Jev: HTTP %d — %s%nRequest id: %s%nResposta: %s%n",
                    e.status(), e.hint(), e.requestId(), e.body());
            exitCode = 2;
        } catch (Exception e) {
            System.err.println("\nErro: " + e.getMessage());
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    // ------------------------------------------------------------------ ping

    private void ping() {
        System.out.println("Chave: " + jev.maskedKey() + "   modelo configurado: " + jev.defaultModel());
        JsonNode models = jev.listModels();
        System.out.println("Chave OK. Modelos disponíveis:");
        for (JsonNode m : models.path("models")) {
            System.out.printf("  - %-14s %s (lançado em %s)%n",
                    m.path("name").asText(), m.path("description").asText(""), m.path("release_date").asText("?"));
        }
    }

    // ------------------------------------------------------------------ risk

    private void risk(ApplicationArguments args) {
        double balance = optDouble(args, "balance", trading.account().referenceBalanceUsd());
        RiskSettings.Global g = risk.global();
        System.out.printf(Locale.ROOT, "%nSaldo: US$ %.2f   modo: %s   política de lote mínimo: %s%n",
                balance, trading.mode(), g.minLotPolicy());
        System.out.printf(Locale.ROOT, "Teto por trade: %.1f%% ou US$ %.2f (vale o menor) = US$ %.2f%n",
                g.maxRiskPerTradePct(), g.maxRiskPerTradeUsd(), g.maxRiskPerTradeMoney(balance));
        System.out.printf(Locale.ROOT, "Perda máx. dia/semana: %.1f%% / %.1f%%   posições: %d   exposição USD: %.1f%%%n%n",
                g.maxDailyLossPct(), g.maxWeeklyLossPct(), g.maxPositions(), g.maxUsdNetExposurePct());

        System.out.printf("%-8s %-7s %10s %12s %9s %9s %7s  %s%n",
                "Símbolo", "Mercado", "Stop", "Lote mín.", "Risco US$", "Risco %", "Lotes", "Decisão");
        for (Instrument i : catalog.bySymbol().values()) {
            SizingResult r = PositionSizer.size(i, i.typicalStop(), balance, risk);
            double minRisk = r.minLotRiskUsd();
            System.out.printf(Locale.ROOT, "%-8s %-7s %10s %12.2f %9.2f %8.1f%% %7.2f  %s — %s%n",
                    i.symbol(), i.market().code(), trimNum(i.typicalStop()), i.minLot(),
                    r.tradable() ? r.riskUsd() : minRisk,
                    r.tradable() ? r.riskPct() : minRisk / balance * 100,
                    r.lots(), r.status(), r.reason());
        }
        System.out.println("""

                Stop = distância típica em preço (provisória; a partir do passo 3 vem do ATR).
                Ajuste os limites em trading.risk no application.yml.""");
    }

    // ------------------------------------------------------------------ ask

    private void ask(ApplicationArguments args) throws Exception {
        String qsetCode = opt(args, "qset", "cb-text-v1");
        QuestionSet qs = questionSets.get(qsetCode);
        String model = opt(args, "model", jev.defaultModel());

        Long docId = null;
        String issuer = opt(args, "issuer", "Federal Reserve");
        String currency = opt(args, "currency", "USD");
        String market = "fx";
        String title = null;
        String text;

        if (args.containsOption("latest") || args.containsOption("doc")) {
            RawDocumentRepository.RawDocument d = args.containsOption("latest")
                    ? documents.latest(qs.docType()).orElseThrow(() ->
                    new IllegalStateException("Nenhum documento do tipo " + qs.docType() + ". Rode antes: collect-once"))
                    : documents.byId(Long.parseLong(opt(args, "doc", "0"))).orElseThrow(() ->
                    new IllegalStateException("Documento não encontrado"));
            docId = d.id();
            issuer = d.issuer();
            currency = d.currency();
            market = d.market();
            title = d.title();
            String html = new String(lake.read(Path.of(d.lakePath())), StandardCharsets.UTF_8);
            text = HtmlText.extract(html, MAX_TEXT_CHARS);
            System.out.printf("Documento #%d: %s%n  %s%n  publicado: %s   visto: %s%n",
                    d.id(), d.title(), d.url(), d.publishedAt(), d.firstSeenAt());
        } else if (args.containsOption("file")) {
            text = Files.readString(Path.of(opt(args, "file", "")));
        } else if (args.containsOption("text")) {
            text = opt(args, "text", "");
        } else {
            throw new IllegalArgumentException("Informe a fonte: --text, --file, --latest ou --doc");
        }
        if (text.isBlank()) throw new IllegalArgumentException("Texto vazio");
        if (text.length() > MAX_TEXT_CHARS) text = text.substring(0, MAX_TEXT_CHARS);

        // Monta o state (o contexto que o Jev avalia). Em inglês, como as perguntas.
        ObjectNode state = mapper.createObjectNode();
        if ("headline".equals(qs.docType())) {
            state.put("headline", text);
            ArrayNode recent = state.putArray("recent_headlines");
            for (String h : opt(args, "recent", "").split("\\|")) {
                if (!h.isBlank()) recent.add(h.trim());
            }
        } else {
            state.put("issuer", issuer);
            state.put("currency", currency);
            if (title != null) state.put("title", title);
            state.put("text", text);
            String previous = opt(args, "previous", null);
            if (previous != null) state.put("previous_communication_summary", previous);
        }

        JsonNode questions = qs.apiQuestions();
        ObjectNode req = mapper.createObjectNode();
        req.put("model", model);
        req.set("state", state);
        req.set("questions", questions);
        String requestSha = LocalDiskLakeStorage.sha256(req.toString().getBytes(StandardCharsets.UTF_8));

        JevResponse resp;
        try {
            resp = jev.systemOne(model, state, questions);
        } catch (JevApiException e) {
            jevCalls.insert(new JevCallRepository.JevCall(docId, market, qs.code(), qs.sha256(), model, null,
                    requestSha, req.toString(), null, e.status(), null, null, e.getMessage()));
            throw e;
        }
        long callId = jevCalls.insert(new JevCallRepository.JevCall(docId, market, qs.code(), qs.sha256(), model,
                resp.model(), requestSha, req.toString(), resp.raw().toString(), 200, resp.inputTokens(),
                (int) resp.latencyMs(), null));

        printAnswers(qs, resp);
        if ("cb-text-v1".equals(qs.code())) {
            CbTextSignal s = CbTextSignal.from(resp, 0.60);
            System.out.printf(Locale.ROOT,
                    "%nSinal para %s: %+.3f   (stance %+.2f · magnitude %.2f · confiança %.2f · relevância %.2f · peso %.2f)%n",
                    currency, s.signal(), s.stance(), s.magnitude(), s.confidence(), s.marketRelevant(), s.weight());
            System.out.println("  positivo = favorece a moeda (tom mais duro); negativo = enfraquece; peso 0 = confiança < 0,60");
        }
        double cost = resp.inputTokens() * USD_PER_MILLION_INPUT_TOKENS / 1_000_000.0;
        System.out.printf(Locale.ROOT, "%nModelo: %s · %d tokens · ~US$ %.6f · %d ms%s%n",
                resp.model(), resp.inputTokens(), cost, resp.latencyMs(),
                callId > 0 ? " · gravado em jev_call #" + callId : "");
        if (args.containsOption("raw")) {
            System.out.println("\nResposta completa:\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(resp.raw()));
        }
    }

    private void printAnswers(QuestionSet qs, JevResponse resp) {
        System.out.println("\nRespostas do Jev (" + qs.code() + "):");
        var it = resp.answers().fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String id = e.getKey();
            JsonNode a = e.getValue();
            String type = a.path("type").asText();
            String line = switch (type) {
                case "choice" -> String.format(Locale.ROOT, "%s  (confiança %.2f)  %s", a.path("choice").asText(),
                        a.path("confidence").asDouble(), compact(a.path("probabilities")));
                case "score" -> {
                    int idx = (int) Math.round(a.path("score").asDouble());
                    yield String.format(Locale.ROOT, "%.2f de %d  (confiança %.2f)  ≈ \"%s\"",
                            a.path("score").asDouble(), Math.max(0, qs.scoreLevels(id) - 1),
                            a.path("confidence").asDouble(), a.path("legend").path(String.valueOf(idx)).asText());
                }
                case "noul" -> String.format(Locale.ROOT, "P(sim) = %.2f", a.path("noul").asDouble());
                default -> a.toString();
            };
            System.out.printf("  %-20s %-7s %s%n", id, type, line);
            String pt = qs.portuguese(id);
            if (!pt.isBlank()) System.out.println("  " + " ".repeat(29) + "↳ " + pt);
        }
    }

    // ------------------------------------------------------------------ collect

    private void collectOnce() {
        FeedCollector.RunSummary s = collector.runOnce();
        System.out.printf("Coleta concluída: %d feeds, %d itens no feed, %d documentos novos, %d erros.%n",
                s.feeds(), s.itemsSeen(), s.itemsNew(), s.errors());
        System.out.println("Bronze em: " + lake.root().resolve("bronze"));
    }

    // ------------------------------------------------------------------ util

    private static String opt(ApplicationArguments args, String name, String def) {
        List<String> v = args.getOptionValues(name);
        return v == null || v.isEmpty() || v.get(0) == null ? def : v.get(0);
    }

    private static double optDouble(ApplicationArguments args, String name, double def) {
        String v = opt(args, name, null);
        return v == null ? def : Double.parseDouble(v.replace(',', '.'));
    }

    private static String compact(JsonNode probs) {
        StringBuilder sb = new StringBuilder("[");
        probs.fields().forEachRemaining(e -> {
            if (sb.length() > 1) sb.append(", ");
            sb.append(e.getKey()).append(' ').append(String.format(Locale.ROOT, "%.2f", e.getValue().asDouble()));
        });
        return sb.append(']').toString();
    }

    private static String trimNum(double v) {
        return new java.math.BigDecimal(v).round(new java.math.MathContext(6)).stripTrailingZeros().toPlainString();
    }
}
