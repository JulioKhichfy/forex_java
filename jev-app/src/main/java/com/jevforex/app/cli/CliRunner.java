package com.jevforex.app.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jevforex.app.config.RiskConfig.InstrumentCatalog;
import com.jevforex.app.config.FeatureProperties;
import com.jevforex.app.config.SilverProperties;
import com.jevforex.features.FeatureBuilder;
import com.jevforex.features.FeatureConfig;
import com.jevforex.app.config.TradingProperties;
import com.jevforex.app.mt5.Mt5StatusService;
import com.jevforex.app.persistence.JevCallRepository;
import com.jevforex.collect.RawDocumentRepository;
import com.jevforex.collect.mt5.Mt5Importer;
import com.jevforex.collect.mt5.Mt5Parsers;
import com.jevforex.collect.mt5.Mt5Properties;
import com.jevforex.collect.mt5.Mt5Repository;
import com.jevforex.collect.rss.FeedCollector;
import com.jevforex.core.Instrument;
import com.jevforex.core.Market;
import com.jevforex.core.risk.PositionSizer;
import com.jevforex.core.risk.RiskSettings;
import com.jevforex.core.risk.SizingResult;
import com.jevforex.lake.LakeSql;
import com.jevforex.lake.LakeStorage;
import com.jevforex.lake.LocalDiskLakeStorage;
import com.jevforex.normalize.CalendarNormalizer;
import com.jevforex.normalize.CandleNormalizer;
import com.jevforex.normalize.Chunker;
import com.jevforex.normalize.DocumentNormalizer;
import com.jevforex.normalize.DocumentText;
import com.jevforex.normalize.SymbolSpecNormalizer;
import com.jevforex.normalize.WeeklyOpenCheck;
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
import java.time.Instant;
import java.util.LinkedHashMap;
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
    private final Mt5Importer mt5Importer;
    private final Mt5Repository mt5Repo;
    private final Mt5Properties mt5;
    private final Mt5StatusService mt5Status;
    private final SilverProperties silver;
    private final FeatureProperties featureProps;
    private int exitCode = 0;

    public CliRunner(JevClient jev, QuestionSetRegistry questionSets, JevCallRepository jevCalls,
                     RawDocumentRepository documents, FeedCollector collector, LakeStorage lake,
                     RiskSettings risk, InstrumentCatalog catalog, TradingProperties trading, ObjectMapper mapper,
                     Mt5Importer mt5Importer, Mt5Repository mt5Repo, Mt5Properties mt5,
                     Mt5StatusService mt5Status, SilverProperties silver, FeatureProperties featureProps) {
        this.silver = silver;
        this.featureProps = featureProps;
        this.mt5Importer = mt5Importer;
        this.mt5Repo = mt5Repo;
        this.mt5 = mt5;
        this.mt5Status = mt5Status;
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
                case Commands.IMPORT_MT5_ONCE -> importMt5Once();
                case Commands.MT5_STATUS -> mt5Status();
                case Commands.NORMALIZE -> normalize(args);
                case Commands.FEATURES -> features();
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
        Map<String, Instrument> instruments = catalog.bySymbol();
        String source = "application.yml (valores aproximados; use --mt5 para os da corretora)";
        if (args.containsOption("mt5")) {
            List<Mt5Parsers.InstrumentSpec> specs = mt5Repo.latestSpecs();
            if (specs.isEmpty()) {
                throw new IllegalStateException("Nenhuma especificação do MT5 ainda. Inicie o Service JevCandleExporter "
                        + "no MT5 e rode import-mt5-once.");
            }
            instruments = withBrokerSpecs(instruments, specs);
            Mt5Parsers.InstrumentSpec first = specs.get(0);
            source = "MT5 — " + first.server() + ", exportado em " + specs.stream()
                    .map(Mt5Parsers.InstrumentSpec::seenAt).max(Instant::compareTo).orElseThrow();
            specs.stream().map(Mt5Parsers.InstrumentSpec::accountCurrency).filter(c -> !"USD".equals(c)).findFirst()
                    .ifPresent(c -> System.out.println("ATENÇÃO: a conta está em " + c
                            + ", não em USD. Os valores abaixo estão na moeda da conta."));
        }
        RiskSettings.Global g = risk.global();
        System.out.printf(Locale.ROOT, "%nSaldo: US$ %.2f   modo: %s   política de lote mínimo: %s%n",
                balance, trading.mode(), g.minLotPolicy());
        System.out.println("Instrumentos: " + source);
        System.out.printf(Locale.ROOT, "Teto por trade: %.1f%% ou US$ %.2f (vale o menor) = US$ %.2f%n",
                g.maxRiskPerTradePct(), g.maxRiskPerTradeUsd(), g.maxRiskPerTradeMoney(balance));
        System.out.printf(Locale.ROOT, "Perda máx. dia/semana: %.1f%% / %.1f%%   posições: %d   exposição USD: %.1f%%%n%n",
                g.maxDailyLossPct(), g.maxWeeklyLossPct(), g.maxPositions(), g.maxUsdNetExposurePct());

        System.out.printf("%-8s %-7s %10s %12s %9s %9s %7s  %s%n",
                "Símbolo", "Mercado", "Stop", "Lote mín.", "Risco US$", "Risco %", "Lotes", "Decisão");
        for (Instrument i : instruments.values()) {
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

    /**
     * Troca tick size/value e lotes aproximados pelos da corretora. O stop típico continua do yml.
     * Para risco usa o tick value de PERDA quando o MT5 informa (é o que vale se o stop for atingido).
     */
    private static Map<String, Instrument> withBrokerSpecs(Map<String, Instrument> base,
                                                           List<Mt5Parsers.InstrumentSpec> specs) {
        Map<String, Mt5Parsers.InstrumentSpec> bySymbol = new LinkedHashMap<>();
        specs.forEach(s -> bySymbol.put(s.symbol(), s));
        Map<String, Instrument> out = new LinkedHashMap<>();
        List<String> fromYml = new java.util.ArrayList<>();
        base.forEach((symbol, i) -> {
            Mt5Parsers.InstrumentSpec s = bySymbol.get(symbol);
            if (s == null) {
                out.put(symbol, i);
                fromYml.add(symbol);
                return;
            }
            double tickValue = s.tickValueLoss() != null && s.tickValueLoss() > 0 ? s.tickValueLoss() : s.tickValue();
            out.put(symbol, new Instrument(symbol, Market.fromCode(s.market()), s.tickSize(), tickValue,
                    s.volumeMin(), s.volumeStep(), s.volumeMax(), i.typicalStop()));
        });
        if (!fromYml.isEmpty()) {
            System.out.println("Sem dados do MT5 para " + fromYml + ": usando os valores do application.yml.");
        }
        return out;
    }

    // ------------------------------------------------------------------ mt5

    private void importMt5Once() {
        Mt5Importer.ImportSummary s = mt5Importer.importOnce();
        System.out.printf("Importação MT5: %d arquivos, %d importados, %d repetidos, %d com erro.%n",
                s.files(), s.imported(), s.duplicates(), s.errors());
        s.rowsByKind().forEach((k, n) -> System.out.printf("  %-10s %d linhas gravadas%n", k.prefix(), n));
        if (s.files() == 0) {
            System.out.println("Inbox vazio: " + mt5.inbox() + " (os Services do MT5 estão rodando?)");
        }
        if (s.errors() > 0) {
            System.out.println("Arquivos com erro (e o motivo em .erro.txt) em: " + mt5.errorDir());
        }
        System.out.println("Bronze em: " + lake.root().resolve("bronze"));
    }

    private void mt5Status() {
        Map<String, Object> s = mt5Status.status();
        System.out.printf("%nPasta: %s%nInbox: %s pendentes · %s com erro%n",
                s.get("common_files"), s.get("inbox_pending"), s.get("error_files"));
        if (!(Boolean) s.get("expected_account_configured")) {
            System.out.println("Aviso: mt5.expected-account não configurado (defina MT5_ACCOUNT): qualquer conta é aceita.");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> hb = (Map<String, Object>) s.get("ea_heartbeat");
        if (hb == null) {
            System.out.println("\nEA: nenhum heartbeat ainda (precisa do modo servidor rodando e do JevExecutor num gráfico).");
        } else {
            System.out.printf(Locale.ROOT, "%nEA: último heartbeat há %s s%s · conta %s em %s · %s · %s · equity %s %s · Algo Trading %s%n",
                    hb.get("age_seconds"), Boolean.TRUE.equals(hb.get("stale")) ? " (PARADO)" : "",
                    hb.get("account"), hb.get("server"), hb.get("trade_mode"), hb.get("ea_mode"),
                    hb.get("equity"), hb.get("currency"),
                    Boolean.TRUE.equals(hb.get("algo_enabled")) ? "ligado" : "DESLIGADO");
        }

        List<Map<String, Object>> candles = Mt5StatusService.list(s, "candles");
        System.out.println("\nCandles M1:" + (candles.isEmpty() ? " nenhum importado ainda" : ""));
        for (Map<String, Object> c : candles) {
            System.out.printf("  %-8s %-6s última barra %s  atraso %6s s  spread %4s pts  %8s barras  %s%n",
                    c.get("symbol"), c.get("market"), c.get("last_bar_utc"), c.get("lag_seconds"),
                    c.get("last_spread_points"), c.get("bars_imported"),
                    Boolean.TRUE.equals(c.get("stale")) ? "ATRASADO (mercado fechado?)" : "OK");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> cal = (Map<String, Object>) s.get("calendar");
        System.out.printf("%nCalendário: %s estados de valores (%s vistos ao vivo), %s eventos no dicionário, último visto %s%n",
                cal.get("states_total"), cal.get("states_live"), cal.get("event_defs"), cal.get("last_seen_at"));
        List<Map<String, Object>> next = Mt5StatusService.list(s, "next_high_impact");
        if (!next.isEmpty()) {
            // o nome vem traduzido pelo MT5 (e às vezes errado); o código em inglês é o que identifica o evento
            System.out.println("Próximos eventos de alto impacto (UTC):");
            for (Map<String, Object> e : next) {
                System.out.printf("  %s  %s  %-42s forecast %-8s previous %-8s %s%n",
                        e.get("scheduled_at"), e.get("currency"), e.get("event_code"),
                        e.get("forecast") == null ? "-" : e.get("forecast"),
                        e.get("previous") == null ? "-" : e.get("previous"), e.get("event"));
            }
        }

        List<Map<String, Object>> files = Mt5StatusService.list(s, "files");
        if (!files.isEmpty()) {
            System.out.println("\nArquivos importados:");
            files.forEach(f -> System.out.printf("  %-10s %6s  (último %s)%n", f.get("kind"), f.get("count"),
                    f.get("last_seen_at")));
        }
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
            // HTML (corpo do artigo) ou PDF anexo; só o primeiro trecho (o documento inteiro vai no passo 4)
            List<String> chunks = Chunker.split(DocumentText.extract(lake.read(Path.of(d.lakePath())),
                    d.contentType()), MAX_TEXT_CHARS);
            text = chunks.isEmpty() ? "" : chunks.get(0);
            if (chunks.size() > 1) {
                System.out.printf("(documento com %d trechos; avaliando o primeiro)%n", chunks.size());
            }
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

    // ------------------------------------------------------------------ normalize (silver)

    private void normalize(ApplicationArguments args) {
        String only = opt(args, "only", "all");
        if (!List.of("all", "candles", "calendar", "documents").contains(only)) {
            throw new IllegalArgumentException("--only deve ser candles, calendar ou documents");
        }
        boolean all = only.equals("all");
        try (LakeSql sql = LakeSql.open(lake.root().resolve("tmp").resolve("duckdb"), silver.duckdbMemory())) {
            if (all || only.equals("candles")) {
                printCandles(timed(() -> new CandleNormalizer(lake.root())
                        .run(sql, silver.candles().utcReliableFrom())));
                long specs = new SymbolSpecNormalizer(lake.root()).run(sql);
                System.out.printf("  Especificações dos símbolos → silver\\instrument_specs (%d símbolos)%n", specs);
            }
            if (all || only.equals("calendar")) {
                printCalendar(timed(() -> new CalendarNormalizer(lake.root())
                        .run(sql, silver.calendar().actualLatencySeconds())));
            }
            if (all || only.equals("documents")) {
                printDocuments(timed(() -> new DocumentNormalizer(lake.root()).run(sql, MAX_TEXT_CHARS)));
            }
        }
    }

    // ------------------------------------------------------------------ features (gold)

    private void features() {
        FeatureConfig cfg = featureProps.toConfig();
        Timed<FeatureBuilder.Report> t;
        try (LakeSql sql = LakeSql.open(lake.root().resolve("tmp").resolve("duckdb"), silver.duckdbMemory())) {
            t = timed(() -> new FeatureBuilder(lake.root(), cfg).run(sql));
        }
        FeatureBuilder.Report r = t.value();
        System.out.printf("%nFeatures %s → %s  (%.1f s)%n", r.fset(), r.featuresOut(), t.seconds());
        System.out.printf("  %d momentos de decisão: %d depois de eventos (média/alta importância), %d de controle "
                + "(hora cheia, sessões ativas)%n", r.moments(), r.eventMoments(), r.controlMoments());
        System.out.printf("  Calendário: %d divulgações com actual, %d com surpresa z calculável (σ de %d anteriores, "
                + "mín. %d)%n", r.releases(), r.releasesWithZ(), cfg.sigmaWindow(), cfg.sigmaMinHistory());
        if (!r.symbolsWithoutSpec().isEmpty()) {
            System.out.println("  ATENÇÃO: sem especificação do MT5 para " + r.symbolsWithoutSpec()
                    + " (point estimado pelos dígitos usuais). Rode normalize depois de exportar as especificações.");
        }
        System.out.printf("  %-8s %8s %9s  %-19s  %s%n", "Par", "Eventos", "Controle", "Primeiro", "Último");
        for (FeatureBuilder.SymbolCounts s : r.symbols()) {
            System.out.printf("  %-8s %8d %9d  %-19s  %s%n", s.symbol(), s.eventMoments(), s.controlMoments(),
                    s.firstMoment(), s.lastMoment());
        }
        System.out.printf("%nLabels → %s%n", r.labelsOut());
        for (FeatureBuilder.LabelStats l : r.labels()) {
            System.out.printf(Locale.ROOT, "  %3d min: %7d linhas · ALTA %4.1f%% · QUEDA %4.1f%% · LATERAL %4.1f%% · "
                            + "custo médio por operação (1 spread) %.2f ATR%n", l.horizonMinutes(), l.rows(),
                    100.0 * l.up() / l.rows(), 100.0 * l.down() / l.rows(), 100.0 * l.flat() / l.rows(),
                    l.meanCostAtr());
        }
        System.out.print("  Features vazias (fração):");
        r.nullShare().forEach((k, v) -> System.out.printf(Locale.ROOT, " %s %.1f%%", k, 100 * v));
        System.out.println();
    }

    private void printDocuments(Timed<DocumentNormalizer.Report> t) {
        DocumentNormalizer.Report r = t.value();
        System.out.printf("%nDocumentos dos bancos centrais → %s  (%.1f s)%n", r.output(), t.seconds());
        System.out.printf("  %d documentos, %d trechos de até %d caracteres, %d sem texto aproveitável%n",
                r.docs(), r.chunks(), MAX_TEXT_CHARS, r.emptyDocs());
        for (DocumentNormalizer.IssuerStats s : r.issuers()) {
            System.out.printf("  %-28s %4d docs (%d anexos PDF) · %4d trechos · %6d caracteres em média · %d vazios%n",
                    s.issuer(), s.docs(), s.attachments(), s.chunks(), s.avgChars(), s.emptyDocs());
            System.out.printf("      amostra: %s…%n", s.sample() == null ? "" : s.sample().replace('\n', ' '));
        }
        if (!r.failures().isEmpty()) {
            System.out.println("  Falhas de extração:");
            r.failures().forEach(f -> System.out.println("    " + f));
        }
    }

    private void printCandles(Timed<CandleNormalizer.Report> t) {
        CandleNormalizer.Report r = t.value();
        System.out.printf("%nCandles M1 → %s  (%.1f s)%n", r.output(), t.seconds());
        System.out.printf("  %d arquivos, %d linhas lidas, %d barras gravadas%s%n", r.files(), r.rowsRead(),
                r.rowsWritten(), r.rowsWithoutMeta() > 0 ? ", " + r.rowsWithoutMeta()
                        + " linhas sem .meta.json (ignoradas)" : "");
        if (r.rowsBeforeReliable() > 0) {
            System.out.printf("  %d barras antes de %s deixadas de fora (silver.candles.utc-reliable-from)%n",
                    r.rowsBeforeReliable(), r.utcReliableFrom());
        }
        System.out.printf("  %-8s %-6s %10s %8s  %-19s  %-19s  %s%n", "Símbolo", "Merc.", "Barras", "Repet.",
                "Primeira (UTC)", "Última (UTC)", "Buracos 10min-6h (maior)");
        for (CandleNormalizer.SymbolStats s : r.symbols()) {
            System.out.printf("  %-8s %-6s %10d %8d  %-19s  %-19s  %d (%d min após %s)%n", s.symbol(), s.market(),
                    s.bars(), s.duplicatesDropped(), s.first(), s.last(), s.intradayGaps(), s.maxIntradayGap(),
                    s.maxIntradayGapAfter() == null ? "-" : s.maxIntradayGapAfter());
        }
        WeeklyOpenCheck.Result w = r.weeklyOpen();
        System.out.printf("  Abertura semanal (hora UTC → semanas): verão EUA %s · padrão EUA %s · %d reaberturas de feriado%n",
                w.summerHours(), w.winterHours(), w.holidayReopens());
        System.out.println("  → " + w.verdict());
    }

    private void printCalendar(Timed<CalendarNormalizer.Report> t) {
        CalendarNormalizer.Report r = t.value();
        System.out.printf("%nCalendário → %s  (%.1f s)%n", r.output(), t.seconds());
        System.out.printf("  %d arquivos, %d estados lidos, %d estados distintos gravados %s%n", r.files(),
                r.statesRead(), r.statesWritten(), r.statesByOrigin());
        System.out.printf("  %d valores com actual · %d eventos no dicionário%n", r.valuesWithActual(), r.eventDefs());
        CalendarNormalizer.Latency l = r.liveLatency();
        if (l.samples() == 0) {
            System.out.println("  Latência ao vivo do actual: ainda sem amostras (precisa de divulgações vistas pelo "
                    + "exportador ao vivo)");
        } else {
            System.out.printf(Locale.ROOT, "  Latência ao vivo do actual: mediana %.1f s · p90 %.1f s · máx. %.1f s "
                            + "(%d divulgações; configurado: %d s)%n", l.medianSeconds(), l.p90Seconds(),
                    l.maxSeconds(), l.samples(), silver.calendar().actualLatencySeconds());
        }
    }

    private record Timed<T>(T value, double seconds) {
    }

    private static <T> Timed<T> timed(java.util.function.Supplier<T> work) {
        long t0 = System.nanoTime();
        T v = work.get();
        return new Timed<>(v, (System.nanoTime() - t0) / 1e9);
    }

    // ------------------------------------------------------------------ collect

    private void collectOnce() {
        FeedCollector.RunSummary s = collector.runOnce();
        System.out.printf("Coleta concluída: %d feeds, %d itens no feed, %d documentos novos, %d anexos PDF, %d erros.%n",
                s.feeds(), s.itemsSeen(), s.itemsNew(), s.attachmentsNew(), s.errors());
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
