package com.jevforex.collect.rss;

import com.jevforex.collect.RawDocumentRepository;
import com.jevforex.collect.RawDocumentRepository.RawDocument;
import com.jevforex.lake.LakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coleta os feeds RSS/Atom configurados.
 *
 * <p>Para cada item novo: baixa a página do item, grava o bruto no bronze (com first_seen_at)
 * e registra em raw_document. Itens já vistos são ignorados.</p>
 */
@Component
public class FeedCollector {

    private static final Logger log = LoggerFactory.getLogger(FeedCollector.class);

    private final FeedProperties props;
    private final LakeStorage lake;
    private final RawDocumentRepository repo;
    private final HttpClient http;
    private final Map<String, String> etags = new ConcurrentHashMap<>();
    private final Map<String, String> lastModified = new ConcurrentHashMap<>();

    public FeedCollector(FeedProperties props, LakeStorage lake, RawDocumentRepository repo) {
        this.props = props;
        this.lake = lake;
        this.repo = repo;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record RunSummary(int feeds, int itemsSeen, int itemsNew, int errors) {
    }

    /** Uma rodada sobre todos os feeds habilitados. */
    public RunSummary runOnce() {
        int feeds = 0, seen = 0, created = 0, errors = 0;
        for (FeedProperties.Feed feed : props.feeds()) {
            if (!feed.enabled()) continue;
            feeds++;
            try {
                int[] r = collectFeed(feed);
                seen += r[0];
                created += r[1];
            } catch (Exception e) {
                errors++;
                log.warn("Feed {} falhou: {}", feed.id(), e.toString());
            }
        }
        RunSummary s = new RunSummary(feeds, seen, created, errors);
        log.info("Coleta: {} feeds, {} itens vistos, {} novos, {} erros", s.feeds(), s.itemsSeen(), s.itemsNew(), s.errors());
        return s;
    }

    private int[] collectFeed(FeedProperties.Feed feed) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(feed.url()))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", props.userAgent())
                .GET();
        if (etags.containsKey(feed.id())) rb.header("If-None-Match", etags.get(feed.id()));
        if (lastModified.containsKey(feed.id())) rb.header("If-Modified-Since", lastModified.get(feed.id()));

        HttpResponse<byte[]> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() == 304) {
            log.debug("{}: sem novidades (304)", feed.id());
            return new int[]{0, 0};
        }
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " em " + feed.url());
        }
        resp.headers().firstValue("ETag").ifPresent(v -> etags.put(feed.id(), v));
        resp.headers().firstValue("Last-Modified").ifPresent(v -> lastModified.put(feed.id(), v));

        List<RssParser.FeedItem> items = RssParser.parse(resp.body());
        int created = 0;
        for (RssParser.FeedItem item : items) {
            if (item.link() == null || item.link().isBlank()) continue;
            if (repo.exists("cb_web", item.link())) continue;
            if (storeItem(feed, item)) created++;
        }
        return new int[]{items.size(), created};
    }

    private boolean storeItem(FeedProperties.Feed feed, RssParser.FeedItem item) throws Exception {
        Instant firstSeen = Instant.now();      // o momento que vale para treino e backtest
        byte[] content;
        String ext;
        if (props.downloadItemPages()) {
            Thread.sleep(props.politeDelayMillis());
            HttpRequest req = HttpRequest.newBuilder(URI.create(item.link()))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", props.userAgent())
                    .GET().build();
            HttpResponse<byte[]> page = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (page.statusCode() != 200) {
                log.warn("{}: página {} retornou HTTP {}", feed.id(), item.link(), page.statusCode());
                return false;
            }
            content = page.body();
            ext = page.headers().firstValue("Content-Type").orElse("").contains("pdf") ? "pdf" : "html";
        } else {
            content = (item.title() + "\n\n" + (item.summary() == null ? "" : item.summary()))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ext = "txt";
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("feed_id", feed.id());
        meta.put("issuer", feed.issuer());
        meta.put("currency", feed.currency());
        meta.put("market", feed.market());
        meta.put("doc_type", feed.docType());
        meta.put("url", item.link());
        meta.put("title", item.title());
        meta.put("guid", item.guid());
        meta.put("published_at", item.publishedAt() == null ? null : item.publishedAt().toString());
        meta.put("collector", "rss@0.1");

        LakeStorage.StoredObject stored = lake.writeBronze("cb_web", firstSeen, ext, content, meta);
        long id = repo.insert(new RawDocument(0, "cb_web", feed.id(), feed.issuer(), feed.currency(), feed.market(),
                feed.docType(), item.link(), item.title(), stored.sha256(),
                stored.relativePath().toString().replace('\\', '/'), item.publishedAt(), firstSeen));
        if (id > 0) {
            log.info("Novo documento #{} [{}] {}", id, feed.id(), item.title());
        }
        return id > 0;
    }
}
