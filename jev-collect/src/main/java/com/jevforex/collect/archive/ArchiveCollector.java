package com.jevforex.collect.archive;

import com.jevforex.collect.rss.FeedCollector;
import com.jevforex.collect.rss.FeedProperties;
import com.jevforex.lake.LakeSql;
import com.jevforex.lake.LakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backfill de comunicados de decisão e atas a partir dos arquivos históricos dos bancos centrais (passo 4b).
 *
 * <p>Lista os itens de cada banco ({@link ArchiveSources}) e baixa com o mesmo coletor dos feeds (intervalo,
 * pausa por site, bronze + raw_document, PDFs anexos). O horário de divulgação fica para o normalizador,
 * pelo calendário. BoE e BoC não têm índice: a URL sai da data da decisão no calendário do MT5 (silver).</p>
 */
@Component
public class ArchiveCollector {

    private static final Logger log = LoggerFactory.getLogger(ArchiveCollector.class);
    public static final String INDEX_SOURCE = "cb_archive_index";

    private final FeedCollector collector;
    private final FeedProperties collect;
    private final LakeStorage lake;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public ArchiveCollector(FeedCollector collector, FeedProperties collect, LakeStorage lake) {
        this.collector = collector;
        this.collect = collect;
        this.lake = lake;
    }

    public record BankResult(String bank, int listed, Map<FeedCollector.StoreOutcome, Integer> outcomes,
                             List<String> failures) {
    }

    public List<BankResult> backfill(List<String> banks, int fromYear) throws InterruptedException {
        List<BankResult> out = new ArrayList<>();
        for (String id : banks) {
            ArchiveSources.Bank bank = ArchiveSources.BANKS.get(id);
            if (bank == null) throw new IllegalArgumentException("Banco desconhecido: " + id + " (use " + ArchiveSources.BANKS.keySet() + ")");
            List<String> failures = new ArrayList<>();
            List<ArchiveItem> items = list(bank, fromYear, failures);
            log.info("{}: {} comunicados/atas desde {}", id, items.size(), fromYear);
            writeIndex(id, items);
            FeedProperties.Feed feed = new FeedProperties.Feed(id + "-archive", bank.issuer(), bank.currency(), "fx",
                    "cb_text", null, true, 0);
            Map<FeedCollector.StoreOutcome, Integer> outcomes = new EnumMap<>(FeedCollector.StoreOutcome.class);
            for (ArchiveItem item : items) {
                FeedCollector.StoreOutcome o = collector.storeDocument(feed, item.url(), item.title(), null, item.meta());
                outcomes.merge(o, 1, Integer::sum);
                if (o == FeedCollector.StoreOutcome.FAILED) failures.add(item.url());
                if (o == FeedCollector.StoreOutcome.SITE_PAUSED) {
                    failures.add("site pediu para esperar; o restante fica para a próxima execução");
                    break;
                }
            }
            out.add(new BankResult(id, items.size(), outcomes, failures));
        }
        return out;
    }

    private List<ArchiveItem> list(ArchiveSources.Bank bank, int fromYear, List<String> failures)
            throws InterruptedException {
        int now = Year.now().getValue();
        List<ArchiveItem> items = new ArrayList<>();
        switch (bank.id()) {
            case "fed" -> items.addAll(ArchiveSources.parseFed(get(ArchiveSources.FED_INDEX, failures), fromYear));
            case "ecb" -> {
                for (int y = fromYear; y <= now; y++) {
                    items.addAll(ArchiveSources.parseEcb(get(ArchiveSources.ecbDecisionsIndex(y), failures), "mp"));
                    items.addAll(ArchiveSources.parseEcb(get(ArchiveSources.ecbStatementsIndex(y), failures), "is"));
                    items.addAll(ArchiveSources.parseEcb(get(ArchiveSources.ecbAccountsIndex(y), failures), "mg"));
                }
            }
            case "boj" -> {
                for (int y = fromYear; y <= now; y++) {
                    items.addAll(ArchiveSources.parseBojStatements(get(ArchiveSources.bojStatementsIndex(y), failures), y));
                    items.addAll(ArchiveSources.parseBojMinutes(get(ArchiveSources.bojMinutesIndex(y), failures), y));
                }
            }
            case "boe" -> decisionDates("boe-interest-rate-decision", fromYear).forEach(d -> items.add(ArchiveSources.boeItem(d)));
            case "boc" -> decisionDates("boc-interest-rate-decision", fromYear).forEach(d -> items.add(ArchiveSources.bocItem(d)));
            default -> throw new IllegalStateException(bank.id());
        }
        // decisões ainda por vir (o calendário traz as futuras) não têm página
        items.removeIf(i -> i.refDate().isAfter(LocalDate.now()));
        Map<String, ArchiveItem> unique = new LinkedHashMap<>();
        items.forEach(i -> unique.putIfAbsent(i.url(), i));
        return new ArrayList<>(unique.values());
    }

    /**
     * Índice dos itens listados (url → tipo, data, regra do horário) em bronze/cb_archive_index. Vale também
     * para documentos que já tinham vindo por um feed (o .meta.json deles é imutável e não tem a regra).
     */
    private void writeIndex(String bank, List<ArchiveItem> items) {
        if (items.isEmpty()) return;
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ArchiveItem i : items) {
                Map<String, Object> m = new LinkedHashMap<>(i.meta());
                m.put("url", i.url());
                rows.add(m);
            }
            byte[] json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(rows);
            lake.writeBronze(INDEX_SOURCE, java.time.Instant.now(), "json", json, Map.of("bank", bank,
                    "items", items.size(), "collector", "archive@0.1"));
        } catch (Exception e) {
            log.warn("{}: índice do arquivo não gravado: {}", bank, e.toString());
        }
    }

    /** Datas das decisões no silver do calendário (rode normalize antes). */
    private List<LocalDate> decisionDates(String eventCode, int fromYear) {
        Path calendar = lake.root().resolve("silver/calendar_events");
        if (!Files.isDirectory(calendar)) {
            throw new IllegalStateException("Sem silver do calendário em " + calendar + ". Rode antes: normalize --only=calendar");
        }
        try (LakeSql sql = LakeSql.open(lake.root().resolve("tmp").resolve("duckdb"), "1GB")) {
            return sql.query("SELECT DISTINCT CAST(scheduled_utc AS DATE)::VARCHAR AS d FROM read_parquet('"
                    + LakeSql.slashes(calendar) + "/**/*.parquet', hive_partitioning = true) WHERE event_code = '"
                    + eventCode.replace("'", "") + "' AND scheduled_utc >= TIMESTAMP '" + fromYear + "-01-01' ORDER BY d",
                    rs -> LocalDate.parse(rs.getString(1)));
        }
    }

    /** GET educado de uma página de índice; null se falhar (o motivo vai para failures). */
    private String get(String url, List<String> failures) throws InterruptedException {
        Thread.sleep(collect.politeDelayMillis());
        try {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", collect.userAgent()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() == 200) return new String(r.body(), StandardCharsets.UTF_8);
            if (r.statusCode() != 404) failures.add(url + " → HTTP " + r.statusCode());
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            failures.add(url + " → " + e);
        }
        return null;
    }
}
