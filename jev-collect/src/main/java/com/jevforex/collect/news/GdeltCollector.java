package com.jevforex.collect.news;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jevforex.collect.rss.FeedProperties;
import com.jevforex.lake.LakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipInputStream;

/**
 * Manchetes do GDELT (GKG 2.1) para o bronze ({@code bronze/gdelt_gkg}). O GDELT publica um arquivo a cada 15 min
 * com tudo o que leu no período (~6 MB compactado); fica no bronze só o que passa no filtro de {@code news.gdelt}
 * (sites da lista + tema econômico/geopolítico ou palavra-chave no título), com as colunas úteis, em JSON por linha
 * compactado ({@code .jsonl.gz}; a primeira linha só tem o lote).
 * O arquivo original continua público e permanente no GDELT (a URL vai no .meta.json): mudar o filtro = reprocessar
 * a partir de lá.
 *
 * <p>Point-in-time: o horário do lote ({@code batch}) é quando o GDELT publicou as notícias; o silver soma um atraso
 * ({@code available-lag-minutes}).</p>
 */
@Component
public class GdeltCollector {

    private static final Logger log = LoggerFactory.getLogger(GdeltCollector.class);
    public static final String SOURCE = "gdelt_gkg";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final Pattern TITLE = Pattern.compile("<PAGE_TITLE>(.*?)</PAGE_TITLE>", Pattern.DOTALL);
    /** JSON tolerante (o EDGAR usa; o GDELT às vezes manda caracteres de controle). */
    static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    private final NewsProperties props;
    private final FeedProperties collect;
    private final LakeStorage lake;
    private volatile HttpClient http = newClient();
    private Instant lastLive;

    public GdeltCollector(NewsProperties props, FeedProperties collect, LakeStorage lake) {
        this.props = props;
        this.collect = collect;
        this.lake = lake;
    }

    /** @param missing o GDELT não tem este lote (há buracos); error != null = falhou e não foi gravado */
    public record Batch(Instant ts, int total, int kept, boolean stored, boolean missing, String error) {
    }

    public record Report(int batches, int stored, int missing, int failed, long rowsTotal, long rowsKept) {
        static Report of(List<Batch> b) {
            return new Report(b.size(), (int) b.stream().filter(Batch::stored).count(),
                    (int) b.stream().filter(Batch::missing).count(), (int) b.stream().filter(x -> x.error() != null).count(),
                    b.stream().mapToLong(Batch::total).sum(), b.stream().mapToLong(Batch::kept).sum());
        }
    }

    // ------------------------------------------------------------------ ao vivo

    /** O lote mais recente (lastupdate.txt) e os que faltarem desde a última rodada (até 1 h). */
    public synchronized Report collectRecent() throws InterruptedException {
        Instant latest;
        try {
            String txt = new String(download(props.gdelt().baseUrl() + "lastupdate.txt"), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("/(\\d{14})\\.gkg\\.csv\\.zip").matcher(txt);
            if (!m.find()) throw new IOException("lastupdate.txt sem GKG");
            latest = TS.parse(m.group(1), Instant::from);
        } catch (IOException e) {
            log.warn("GDELT ao vivo: {}", e.getMessage());
            return Report.of(List.of(new Batch(Instant.now(), 0, 0, false, false, e.getMessage())));
        }
        Instant from = lastLive == null ? latest : lastLive.plus(Duration.ofMinutes(15));
        if (from.isBefore(latest.minus(Duration.ofHours(1)))) from = latest.minus(Duration.ofHours(1));
        List<Batch> out = new ArrayList<>();
        for (Instant t = from; !t.isAfter(latest); t = t.plus(Duration.ofMinutes(15))) out.add(process(t, "live"));
        if (out.stream().noneMatch(b -> b.error() != null)) lastLive = latest;
        return Report.of(out);
    }

    // ------------------------------------------------------------------ histórico

    /**
     * Histórico de {@code from} até {@code to} (exclusive), dia a dia, com {@code parallel} downloads. Os dias
     * concluídos ficam em {@code <lake>/state/gdelt-gkg-days.json}: rodar de novo continua de onde parou.
     */
    public Report backfill(LocalDate from, LocalDate to) throws InterruptedException {
        Set<String> done = readDone();
        List<Batch> all = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(props.gdelt().parallel());
        try {
            for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
                if (done.contains(d.toString())) continue;
                List<Future<Batch>> fs = new ArrayList<>();
                Instant start = d.atStartOfDay().toInstant(ZoneOffset.UTC);
                for (int i = 0; i < 96; i++) {
                    Instant t = start.plus(Duration.ofMinutes(15L * i));
                    if (t.isAfter(Instant.now())) break;
                    fs.add(pool.submit(() -> process(t, "backfill")));
                }
                List<Batch> day = new ArrayList<>();
                for (Future<Batch> f : fs) {
                    try {
                        day.add(f.get());
                    } catch (java.util.concurrent.ExecutionException e) {
                        day.add(new Batch(start, 0, 0, false, false, String.valueOf(e.getCause())));
                    }
                }
                all.addAll(day);
                Report r = Report.of(day);
                if (r.failed() == 0 && fs.size() == 96) {
                    done.add(d.toString());
                    writeDone(done);
                }
                log.info("GDELT {}: {} lotes, {} sem arquivo, {} com erro · {} de {} notícias guardadas",
                        d, r.batches(), r.missing(), r.failed(), r.rowsKept(), r.rowsTotal());
            }
        } finally {
            pool.shutdownNow();
        }
        return Report.of(all);
    }

    // ------------------------------------------------------------------ um lote

    Batch process(Instant ts, String mode) throws InterruptedException {
        String url = props.gdelt().baseUrl() + TS.format(ts) + ".gkg.csv.zip";
        Filter filter = Filter.of(props.gdelt());
        StringBuilder out = new StringBuilder("{\"batch\": \"").append(TS.format(ts)).append("\"}\n");
        int total = 0, kept = 0;
        // o zip vai para um arquivo temporário e é lido de lá: guardar ~10 MB por download na memória (byte[]) com
        // downloads em paralelo fragmentava o heap e derrubava a JVM (falta de memória no backfill de 14/10)
        Path tmp = null;
        try {
            Path dir = lake.root().resolve("tmp").resolve("gdelt");
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, TS.format(ts), ".zip");
            downloadTo(url, tmp);
            try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(Files.newInputStream(tmp), 1 << 16))) {
                if (z.getNextEntry() == null) throw new IOException("zip vazio");
                BufferedReader r = new BufferedReader(new InputStreamReader(z, StandardCharsets.UTF_8), 1 << 16);
                for (String line; (line = r.readLine()) != null; ) {
                    total++;
                    String row = filter.apply(line);
                    if (row != null) {
                        out.append(row).append('\n');
                        kept++;
                    }
                }
            }
        } catch (Missing e) {
            return new Batch(ts, 0, 0, false, true, null);
        } catch (IOException e) {
            log.warn("GDELT {}: {}", TS.format(ts), e.getMessage());
            return new Batch(ts, 0, 0, false, false, e.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // fica no tmp do lake
                }
            }
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("batch", ts.toString());
        meta.put("url", url);
        meta.put("mode", mode);
        meta.put("rows_total", total);
        meta.put("rows_kept", kept);
        meta.put("filter", filter.describe());
        meta.put("collector", "gdelt-gkg@0.1");
        LakeStorage.StoredObject so = lake.writeBronze(SOURCE, Instant.now(), "jsonl.gz", gzip(out.toString()), meta);
        return new Batch(ts, total, kept, !so.alreadyExisted(), false, null);
    }

    /** O filtro do bronze: site da lista e (site especializado, tema do GDELT ou palavra no título). */
    record Filter(Set<String> domains, Set<String> always, List<String> prefixes, List<String> keywords) {
        static Filter of(NewsProperties.Gdelt g) {
            return new Filter(new HashSet<>(g.domains()), new HashSet<>(g.alwaysDomains()), g.themePrefixes(),
                    g.titleKeywords());
        }

        String describe() {
            return "domains=" + new TreeSet<>(domains) + " always=" + new TreeSet<>(always) + " themes=" + prefixes
                    + " keywords=" + keywords;
        }

        /** Uma linha do GKG → a linha guardada (JSON) ou null. */
        String apply(String line) {
            String[] c = line.split("\t", -1);
            if (c.length < 27) return null;
            String domain = c[3].toLowerCase();
            if (!domains.contains(domain) && !always.contains(domain)) return null;
            Matcher m = TITLE.matcher(c[26]);
            String title = m.find() ? unescape(m.group(1)) : "";
            String themes = c[7];
            boolean keep = always.contains(domain);
            if (!keep && !themes.isEmpty()) {
                for (String t : themes.split(";")) {
                    for (String p : prefixes) if (t.startsWith(p)) keep = true;
                    if (keep) break;
                }
            }
            if (!keep && !title.isEmpty()) {
                String lower = title.toLowerCase();
                for (String k : keywords) if (lower.contains(k)) keep = true;
            }
            if (!keep) return null;
            String[] tone = c[15].split(",", -1);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("record_id", c[0]);
            row.put("batch", c[1]);
            row.put("domain", domain);
            row.put("url", c[4]);
            row.put("themes", themes);
            row.put("persons", c[11]);
            row.put("organizations", c[13]);
            row.put("tone", number(tone, 0));
            row.put("positive", number(tone, 1));
            row.put("negative", number(tone, 2));
            row.put("polarity", number(tone, 3));
            row.put("title", title);
            try {
                return JSON.writeValueAsString(row);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return null;
            }
        }

        private static Double number(String[] a, int i) {
            try {
                return i < a.length && !a[i].isBlank() ? Double.parseDouble(a[i]) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static final Pattern NUMERIC = Pattern.compile("&#(x[0-9a-fA-F]+|\\d+);");

        static String unescape(String s) {
            Matcher m = NUMERIC.matcher(s);
            StringBuilder b = new StringBuilder();
            while (m.find()) {
                String n = m.group(1);
                int cp;
                try {
                    cp = n.startsWith("x") ? Integer.parseInt(n.substring(1), 16) : Integer.parseInt(n);
                } catch (NumberFormatException e) {
                    cp = -1;
                }
                m.appendReplacement(b, Matcher.quoteReplacement(Character.isValidCodePoint(cp)
                        ? new String(Character.toChars(cp)) : m.group()));
            }
            m.appendTail(b);
            return b.toString().replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
                    .replace("&lt;", "<").replace("&gt;", ">").trim();
        }
    }

    // ------------------------------------------------------------------ rede e arquivos

    /** O GDELT não tem o arquivo (404): há buracos de 15 min no histórico. */
    static final class Missing extends IOException {
        Missing(String m) {
            super(m);
        }
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** Arquivo pequeno (lastupdate.txt) na memória. */
    private byte[] download(String url) throws IOException, InterruptedException {
        Path tmp = Files.createTempFile("gdelt", ".txt");
        try {
            downloadTo(url, tmp);
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** GET direto para um arquivo, com novas tentativas; 404 = {@link Missing}. */
    private void downloadTo(String url, Path file) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= props.gdelt().retries() + 1; attempt++) {
            if (attempt > 1) Thread.sleep(5_000L * attempt);
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120))
                        .header("User-Agent", collect.userAgent()).GET().build();
                HttpResponse<Path> r = http.send(req, HttpResponse.BodyHandlers.ofFile(file));
                if (r.statusCode() == 200) return;
                if (r.statusCode() == 404) throw new Missing("HTTP 404");
                last = new IOException("HTTP " + r.statusCode());
            } catch (Missing e) {
                throw e;
            } catch (IOException e) {
                last = e;
                // cliente HTTP morto (ex.: "selector manager closed"): os pedidos seguintes ficariam parados para sempre
                if (String.valueOf(e.getMessage()).contains("selector manager closed")) {
                    synchronized (this) {
                        http = newClient();
                    }
                }
            }
        }
        throw last;
    }
    /** GZIP determinístico (o Java grava mtime 0): o mesmo conteúdo = o mesmo hash no bronze. */
    static byte[] gzip(String s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(b)) {
            g.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return b.toByteArray();
    }

    private Path doneFile() {
        return lake.root().resolve("state").resolve("gdelt-gkg-days.json");
    }

    private Set<String> readDone() {
        try {
            Path f = doneFile();
            Set<String> s = new TreeSet<>();
            if (Files.exists(f)) JSON.readTree(f.toFile()).forEach(n -> s.add(n.asText()));
            return s;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private synchronized void writeDone(Set<String> done) {
        try {
            Files.createDirectories(doneFile().getParent());
            JSON.writeValue(doneFile().toFile(), new TreeSet<>(done));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

}
