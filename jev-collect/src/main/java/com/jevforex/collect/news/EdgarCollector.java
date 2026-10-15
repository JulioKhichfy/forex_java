package com.jevforex.collect.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.jevforex.lake.LakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Documentos da SEC (EDGAR) das ações em uso, para o bronze:
 * <ul>
 *   <li>{@code edgar_submissions}: a lista de documentos de cada empresa (data.sec.gov/submissions), como veio;</li>
 *   <li>{@code edgar_filings}: de cada 8-K, 10-Q, 10-K… novo, o cabeçalho oficial ({@code -index-headers.html}, com o
 *       ACCEPTANCE-DATETIME no horário de Nova York — o campo do JSON não bate com ele) e, nos 8-K, o documento
 *       principal.</li>
 * </ul>
 * A SEC exige um contato no User-Agent ({@code news.edgar.user-agent}); sem ele, nada é pedido.
 */
@Component
public class EdgarCollector {

    private static final Logger log = LoggerFactory.getLogger(EdgarCollector.class);
    public static final String SOURCE_SUBMISSIONS = "edgar_submissions";
    public static final String SOURCE_FILINGS = "edgar_filings";

    private final NewsProperties props;
    private final LakeStorage lake;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private long lastRequestNanos;

    public EdgarCollector(NewsProperties props, LakeStorage lake) {
        this.props = props;
        this.lake = lake;
    }

    /** Um documento da lista da empresa. */
    record Filing(String ticker, long cik, String accession, String form, String items, String filingDate,
                  String acceptanceJson, String primaryDocument) {
        String folder() {
            return "https://www.sec.gov/Archives/edgar/data/" + cik + "/" + accession.replace("-", "") + "/";
        }
    }

    public record Report(int companies, int listed, int fetched, int failed) {
    }

    /**
     * @param history também as páginas antigas da lista (backfill); ao vivo basta a recente (último ano)
     */
    public synchronized Report collect(boolean history) throws InterruptedException {
        NewsProperties.Edgar cfg = props.edgar();
        if (!cfg.configured()) {
            throw new IllegalStateException("A SEC exige um contato no User-Agent: defina EDGAR_USER_AGENT "
                    + "(ex.: \"Seu Nome seu@email.com\") ou news.edgar.user-agent no config/application.yml");
        }
        Set<String> known = knownAccessions();
        int listed = 0, fetched = 0, failed = 0;
        for (var c : cfg.companies().entrySet()) {
            List<Filing> filings = new ArrayList<>();
            try {
                String url = String.format("https://data.sec.gov/submissions/CIK%010d.json", c.getValue());
                byte[] body = get(url);
                store(SOURCE_SUBMISSIONS, "json", body, Map.of("ticker", c.getKey(), "cik", c.getValue(), "url", url));
                JsonNode root = GdeltCollector.JSON.readTree(body);
                filings.addAll(parse(c.getKey(), c.getValue(), root.path("filings").path("recent")));
                if (history) {
                    for (JsonNode f : root.path("filings").path("files")) {
                        String u = "https://data.sec.gov/submissions/" + f.path("name").asText();
                        byte[] older = get(u);
                        store(SOURCE_SUBMISSIONS, "json", older, Map.of("ticker", c.getKey(), "cik", c.getValue(), "url", u));
                        filings.addAll(parse(c.getKey(), c.getValue(), GdeltCollector.JSON.readTree(older)));
                    }
                }
            } catch (IOException e) {
                log.warn("EDGAR {}: lista falhou: {}", c.getKey(), e.getMessage());
                failed++;
                continue;
            }
            for (Filing f : filings) {
                if (!cfg.forms().contains(f.form()) || f.filingDate().compareTo(cfg.from().toString()) < 0) continue;
                listed++;
                if (known.contains(f.accession())) continue;
                try {
                    fetch(f);
                    known.add(f.accession());
                    fetched++;
                } catch (IOException e) {
                    log.warn("EDGAR {} {} {}: {}", f.ticker(), f.form(), f.accession(), e.getMessage());
                    failed++;
                }
            }
        }
        return new Report(cfg.companies().size(), listed, fetched, failed);
    }

    /** Cabeçalho oficial (horário de aceitação) e, nos 8-K, o documento principal. */
    private void fetch(Filing f) throws IOException, InterruptedException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("ticker", f.ticker());
        meta.put("cik", f.cik());
        meta.put("accession", f.accession());
        meta.put("form", f.form());
        meta.put("items", f.items());
        meta.put("filing_date", f.filingDate());
        meta.put("acceptance_json", f.acceptanceJson());
        meta.put("primary_document", f.primaryDocument());
        meta.put("collector", "edgar@0.1");
        String headers = f.folder() + f.accession() + "-index-headers.html";
        Map<String, Object> h = new LinkedHashMap<>(meta);
        h.put("kind", "index-headers");
        h.put("url", headers);
        store(SOURCE_FILINGS, "html", get(headers), h);
        if (f.form().startsWith("8-K") && f.primaryDocument() != null && !f.primaryDocument().isBlank()) {
            String doc = f.folder() + f.primaryDocument();
            Map<String, Object> d = new LinkedHashMap<>(meta);
            d.put("kind", "primary");
            d.put("url", doc);
            store(SOURCE_FILINGS, "html", get(doc), d);
        }
    }

    /** As colunas paralelas da lista (form[i], accessionNumber[i]…) viram um documento por linha. */
    static List<Filing> parse(String ticker, long cik, JsonNode cols) {
        List<Filing> out = new ArrayList<>();
        JsonNode acc = cols.path("accessionNumber");
        for (int i = 0; i < acc.size(); i++) {
            out.add(new Filing(ticker, cik, acc.get(i).asText(), cols.path("form").path(i).asText(),
                    cols.path("items").path(i).asText(""), cols.path("filingDate").path(i).asText(),
                    cols.path("acceptanceDateTime").path(i).asText(null), cols.path("primaryDocument").path(i).asText("")));
        }
        return out;
    }

    /** Documentos já no bronze (pelo .meta.json): não pede de novo. */
    private Set<String> knownAccessions() {
        Set<String> out = new HashSet<>();
        Path dir = lake.root().resolve("bronze").resolve(SOURCE_FILINGS);
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.walk(dir, 2)) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".meta.json")).toList()) {
                JsonNode m = GdeltCollector.JSON.readTree(p.toFile());
                if ("index-headers".equals(m.path("kind").asText())) out.add(m.path("accession").asText());
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out;
    }

    private void store(String source, String ext, byte[] body, Map<String, Object> meta) {
        lake.writeBronze(source, Instant.now(), ext, body, meta);
    }

    private byte[] get(String url) throws IOException, InterruptedException {
        long since = (System.nanoTime() - lastRequestNanos) / 1_000_000;
        if (since < props.edgar().delayMillis()) Thread.sleep(props.edgar().delayMillis() - since);
        lastRequestNanos = System.nanoTime();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", props.edgar().userAgent()).header("Accept-Encoding", "identity").GET().build();
        HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " em " + url);
        String head = new String(r.body(), 0, Math.min(400, r.body().length), java.nio.charset.StandardCharsets.UTF_8);
        if (head.contains("Undeclared Automated Tool")) {
            throw new IOException("a SEC recusou o User-Agent (precisa de nome e e-mail de contato)");
        }
        return r.body();
    }
}
