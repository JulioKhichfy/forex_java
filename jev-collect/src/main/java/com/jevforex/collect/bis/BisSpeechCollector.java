package com.jevforex.collect.bis;

import com.jevforex.collect.rss.FeedProperties;
import com.jevforex.lake.LakeStorage;
import com.jevforex.lake.LocalDiskLakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Baixa os zips anuais de discursos do BIS para o bronze ({@code bronze/bis_speeches}), como vieram.
 * O BIS atualiza o acervo de tempos em tempos: zip com conteúdo novo vira uma nova versão no bronze;
 * conteúdo igual ao já gravado é ignorado (o nome do arquivo é o hash).
 */
@Component
public class BisSpeechCollector {

    private static final Logger log = LoggerFactory.getLogger(BisSpeechCollector.class);
    public static final String SOURCE = "bis_speeches";

    private final BisProperties props;
    private final FeedProperties collect;
    private final LakeStorage lake;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public BisSpeechCollector(BisProperties props, FeedProperties collect, LakeStorage lake) {
        this.props = props;
        this.collect = collect;
        this.lake = lake;
    }

    /** @param stored false = o mesmo conteúdo já estava no bronze */
    public record YearResult(int year, int httpStatus, long bytes, boolean stored, String lakePath) {
    }

    public List<YearResult> backfill(int fromYear) throws InterruptedException {
        List<YearResult> out = new ArrayList<>();
        int to = Year.now().getValue();
        for (int y = Math.max(fromYear, 1996); y <= to; y++) {
            String url = props.url(y);
            try {
                if (!out.isEmpty()) Thread.sleep(collect.politeDelayMillis());
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3))
                        .header("User-Agent", collect.userAgent()).GET().build();
                HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) {
                    log.warn("BIS {}: HTTP {}", y, r.statusCode());
                    out.add(new YearResult(y, r.statusCode(), 0, false, null));
                    continue;
                }
                String sha = LocalDiskLakeStorage.sha256(r.body());
                if (alreadyInBronze(sha)) {
                    out.add(new YearResult(y, 200, r.body().length, false, null));
                    log.info("BIS {}: igual ao já gravado", y);
                    continue;
                }
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("year", y);
                meta.put("url", url);
                meta.put("content_type", "zip");
                meta.put("collector", "bis@0.1");
                LakeStorage.StoredObject so = lake.writeBronze(SOURCE, Instant.now(), "zip", r.body(), meta);
                String path = so.relativePath().toString().replace('\\', '/');
                out.add(new YearResult(y, 200, r.body().length, !so.alreadyExisted(), path));
                log.info("BIS {}: {} KB{}", y, r.body().length / 1024, so.alreadyExisted() ? " (igual ao já gravado)" : "");
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                log.warn("BIS {} falhou: {}", y, e.toString());
                out.add(new YearResult(y, -1, 0, false, null));
            }
        }
        return out;
    }

    /** O mesmo zip (mesmo hash) já foi gravado em qualquer dia? */
    private boolean alreadyInBronze(String sha) {
        Path dir = lake.root().resolve("bronze").resolve(SOURCE);
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> s = Files.walk(dir, 2)) {
            return s.anyMatch(p -> p.getFileName().toString().equals(sha + ".zip"));
        } catch (IOException e) {
            return false;
        }
    }
}
