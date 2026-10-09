package com.jevforex.collect.rss;

import com.jevforex.collect.HtmlLinks;
import com.jevforex.collect.RawDocumentRepository;
import com.jevforex.collect.RawDocumentRepository.RawDocument;
import com.jevforex.lake.LakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
 * e registra em raw_document. Itens já vistos são ignorados. Depois, baixa os PDFs anexos no corpo
 * da página (cada um vira um documento com parent_id apontando para a página).</p>
 */
@Component
public class FeedCollector {

    private static final Logger log = LoggerFactory.getLogger(FeedCollector.class);
    private static final String SOURCE = "cb_web";
    private static final String COLLECTOR = "rss@0.2";

    private final FeedProperties props;
    private final LakeStorage lake;
    private final RawDocumentRepository repo;
    private final HttpClient http;
    private final Map<String, String> etags = new ConcurrentHashMap<>();
    private final Map<String, String> lastModified = new ConcurrentHashMap<>();
    /** site → até quando não pedir nada (depois de um 429/403). */
    private final Map<String, Instant> pausedUntil = new ConcurrentHashMap<>();
    private static final Duration DEFAULT_PAUSE = Duration.ofMinutes(15);

    /** O site pediu para esperar: o restante fica para a próxima rodada. */
    private static final class SitePaused extends RuntimeException {
        SitePaused() {
            super(null, null, false, false);
        }
    }

    public FeedCollector(FeedProperties props, LakeStorage lake, RawDocumentRepository repo) {
        this.props = props;
        this.lake = lake;
        this.repo = repo;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record RunSummary(int feeds, int itemsSeen, int itemsNew, int attachmentsNew, int errors) {
    }

    /** Uma rodada sobre todos os feeds habilitados, mais os anexos pendentes de páginas antigas. */
    public RunSummary runOnce() {
        int feeds = 0, seen = 0, created = 0, errors = 0;
        int[] attachments = {0};
        for (FeedProperties.Feed feed : props.feeds()) {
            if (!feed.enabled()) continue;
            feeds++;
            try {
                int[] r = collectFeed(feed, attachments);
                seen += r[0];
                created += r[1];
            } catch (Exception e) {
                errors++;
                log.warn("Feed {} falhou: {}", feed.id(), e.toString());
            }
        }
        try {
            attachments[0] += backfillAttachments();
        } catch (Exception e) {
            errors++;
            log.warn("Anexos pendentes falharam: {}", e.toString());
        }
        RunSummary s = new RunSummary(feeds, seen, created, attachments[0], errors);
        log.info("Coleta: {} feeds, {} itens vistos, {} novos, {} anexos, {} erros",
                s.feeds(), s.itemsSeen(), s.itemsNew(), s.attachmentsNew(), s.errors());
        return s;
    }

    private int[] collectFeed(FeedProperties.Feed feed, int[] attachments) throws Exception {
        if (isPaused(URI.create(feed.url()))) {
            log.debug("{}: site em pausa (pediu para esperar)", feed.id());
            return new int[]{0, 0};
        }
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
        if (resp.statusCode() == 429 || resp.statusCode() == 403) {
            pause(URI.create(feed.url()), resp, feed.id());
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
            if (repo.exists(SOURCE, item.link())) continue;
            try {
                if (storeItem(feed, item, attachments)) created++;
            } catch (SitePaused e) {
                break;   // o restante dos itens entra numa próxima rodada
            }
        }
        return new int[]{items.size(), created};
    }

    /** Resultado de {@link #storeDocument}. */
    public enum StoreOutcome { STORED, ALREADY_STORED, FAILED, SITE_PAUSED }

    /**
     * Baixa e guarda um documento que não veio de um feed (ex.: arquivo histórico de comunicados), com as
     * mesmas regras da coleta: intervalo entre pedidos, pausa por site, bronze + raw_document e PDFs anexos.
     *
     * @param extraMeta vai para o .meta.json (ex.: doc_kind, ref_date e a regra do horário de divulgação)
     */
    public StoreOutcome storeDocument(FeedProperties.Feed feed, String url, String title, Instant publishedAt,
                                      Map<String, Object> extraMeta) throws InterruptedException {
        if (repo.exists(SOURCE, url)) return StoreOutcome.ALREADY_STORED;
        try {
            return storeItem(feed, new RssParser.FeedItem(title, url, null, publishedAt, null), new int[1], extraMeta)
                    ? StoreOutcome.STORED : StoreOutcome.FAILED;
        } catch (SitePaused e) {
            return StoreOutcome.SITE_PAUSED;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("{}: {} falhou: {}", feed.id(), url, e.toString());
            return StoreOutcome.FAILED;
        }
    }

    private boolean storeItem(FeedProperties.Feed feed, RssParser.FeedItem item, int[] attachments) throws Exception {
        return storeItem(feed, item, attachments, Map.of());
    }

    private boolean storeItem(FeedProperties.Feed feed, RssParser.FeedItem item, int[] attachments,
                              Map<String, Object> extraMeta) throws Exception {
        Instant firstSeen = Instant.now();      // o momento que vale para treino e backtest
        byte[] content;
        String ext;
        if (props.downloadItemPages()) {
            URI pageUrl = URI.create(item.link());
            if (isPaused(pageUrl)) throw new SitePaused();
            Thread.sleep(props.delayFor(feed));
            HttpRequest req = HttpRequest.newBuilder(pageUrl)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", props.userAgent())
                    .GET().build();
            HttpResponse<byte[]> page = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (page.statusCode() == 429 || page.statusCode() == 403) {
                pause(pageUrl, page, feed.id());
                throw new SitePaused();
            }
            if (page.statusCode() != 200) {
                log.warn("{}: página {} retornou HTTP {}", feed.id(), item.link(), page.statusCode());
                return false;
            }
            content = page.body();
            ext = isPdf(page.headers().firstValue("Content-Type").orElse(""), content) ? "pdf" : "html";
        } else {
            content = (item.title() + "\n\n" + (item.summary() == null ? "" : item.summary()))
                    .getBytes(StandardCharsets.UTF_8);
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
        meta.put("content_type", ext);
        meta.put("collector", COLLECTOR);
        meta.putAll(extraMeta);

        LakeStorage.StoredObject stored = lake.writeBronze(SOURCE, firstSeen, ext, content, meta);
        RawDocument doc = new RawDocument(0, SOURCE, feed.id(), feed.issuer(), feed.currency(), feed.market(),
                feed.docType(), item.link(), item.title(), stored.sha256(),
                stored.relativePath().toString().replace('\\', '/'), item.publishedAt(), firstSeen, null, ext);
        long id = repo.insert(doc);
        if (id <= 0) return false;
        log.info("Novo documento #{} [{}] {}", id, feed.id(), item.title());
        if ("html".equals(ext) && props.attachments().enabled()) {
            attachments[0] += collectAttachments(withId(doc, id), content);
            repo.markAttachmentsChecked(id);
        }
        return true;
    }

    /** Páginas coletadas antes dos anexos existirem (ou numa rodada que falhou): poucas por vez. */
    private int backfillAttachments() {
        if (!props.attachments().enabled() || props.attachments().backfillPerRun() == 0) return 0;
        int stored = 0;
        for (RawDocument d : repo.pendingAttachmentCheck(props.attachments().backfillPerRun())) {
            stored += collectAttachments(d, lake.read(Path.of(d.lakePath())));
            repo.markAttachmentsChecked(d.id());
        }
        return stored;
    }

    /** Baixa os PDFs ligados no corpo da página; devolve quantos foram gravados. */
    private int collectAttachments(RawDocument parent, byte[] html) {
        FeedProperties.Attachments cfg = props.attachments();
        List<HtmlLinks.Link> links = HtmlLinks.pdfLinks(new String(html, StandardCharsets.UTF_8),
                URI.create(parent.url()), cfg.maxPerItem());
        int stored = 0;
        for (HtmlLinks.Link link : links) {
            String url = link.url().toString();
            if (repo.exists(SOURCE, url)) continue;
            if (isPaused(link.url())) return stored;
            try {
                Thread.sleep(delayForFeed(parent.feedId()));
                HttpRequest req = HttpRequest.newBuilder(link.url())
                        .timeout(Duration.ofSeconds(60))
                        .header("User-Agent", props.userAgent())
                        .GET().build();
                HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                byte[] body;
                try (InputStream in = r.body()) {
                    if (r.statusCode() == 429 || r.statusCode() == 403) {
                        pause(link.url(), r, parent.feedId());
                        return stored;
                    }
                    if (r.statusCode() != 200) {
                        log.warn("Anexo {} retornou HTTP {}", url, r.statusCode());
                        continue;
                    }
                    body = readLimited(in, cfg.maxMb() * 1024L * 1024L);
                }
                if (body == null) {
                    log.warn("Anexo {} passa de {} MB; ignorado", url, cfg.maxMb());
                    continue;
                }
                if (!isPdf(r.headers().firstValue("Content-Type").orElse(""), body)) {
                    log.warn("Anexo {} não é PDF; ignorado", url);
                    continue;
                }
                Instant seen = Instant.now();
                String title = parent.title() + " — " + (link.label().isBlank() ? "PDF" : link.label());
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("feed_id", parent.feedId());
                meta.put("issuer", parent.issuer());
                meta.put("currency", parent.currency());
                meta.put("market", parent.market());
                meta.put("doc_type", parent.docType());
                meta.put("url", url);
                meta.put("title", title);
                meta.put("published_at", parent.publishedAt() == null ? null : parent.publishedAt().toString());
                meta.put("content_type", "pdf");
                meta.put("parent_url", parent.url());
                meta.put("parent_sha256", parent.sha256());
                meta.put("link_label", link.label());
                meta.put("collector", COLLECTOR);
                LakeStorage.StoredObject so = lake.writeBronze(SOURCE, seen, "pdf", body, meta);
                long id = repo.insert(new RawDocument(0, SOURCE, parent.feedId(), parent.issuer(), parent.currency(),
                        parent.market(), parent.docType(), url, title, so.sha256(),
                        so.relativePath().toString().replace('\\', '/'), parent.publishedAt(), seen, parent.id(),
                        "pdf"));
                if (id > 0) {
                    stored++;
                    log.info("Anexo #{} do documento #{}: {} ({} KB)", id, parent.id(), link.label(), body.length / 1024);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return stored;
            } catch (Exception e) {
                log.warn("Anexo {} falhou: {}", url, e.toString());
            }
        }
        return stored;
    }

    private boolean isPaused(URI url) {
        Instant until = pausedUntil.get(url.getHost());
        return until != null && Instant.now().isBefore(until);
    }

    /** Respeita o Retry-After (em segundos) quando o site informa; senão, 15 minutos. */
    private void pause(URI url, HttpResponse<?> resp, String feedId) {
        Duration d = resp.headers().firstValue("Retry-After").map(v -> {
            try {
                return Duration.ofSeconds(Math.max(30, Long.parseLong(v.trim())));
            } catch (NumberFormatException e) {
                return DEFAULT_PAUSE;
            }
        }).orElse(DEFAULT_PAUSE);
        pausedUntil.put(url.getHost(), Instant.now().plus(d));
        log.warn("{}: {} respondeu HTTP {}; nada de pedidos a esse site por {} min (o restante fica para depois)",
                feedId, url.getHost(), resp.statusCode(), Math.max(1, d.toMinutes()));
    }

    private long delayForFeed(String feedId) {
        return props.feeds().stream().filter(f -> f.id().equals(feedId)).findFirst()
                .map(props::delayFor).orElse(props.politeDelayMillis());
    }

    private static RawDocument withId(RawDocument d, long id) {
        return new RawDocument(id, d.source(), d.feedId(), d.issuer(), d.currency(), d.market(), d.docType(), d.url(),
                d.title(), d.sha256(), d.lakePath(), d.publishedAt(), d.firstSeenAt(), d.parentId(), d.contentType());
    }

    static boolean isPdf(String contentType, byte[] body) {
        return contentType.toLowerCase().contains("pdf")
                || (body.length >= 4 && body[0] == '%' && body[1] == 'P' && body[2] == 'D' && body[3] == 'F');
    }

    /** Lê até {@code max} bytes; devolve null se passar disso (sem baixar o resto). */
    private static byte[] readLimited(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
