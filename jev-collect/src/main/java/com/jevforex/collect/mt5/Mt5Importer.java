package com.jevforex.collect.mt5;

import com.jevforex.collect.mt5.Mt5Parsers.CalendarValue;
import com.jevforex.collect.mt5.Mt5Parsers.Candle;
import com.jevforex.collect.mt5.Mt5Parsers.FileMeta;
import com.jevforex.collect.mt5.Mt5Parsers.InstrumentSpec;
import com.jevforex.lake.LakeStorage;
import com.jevforex.lake.LocalDiskLakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Importa os arquivos que os programas MQL5 deixam em Common\Files\jev\inbox.
 *
 * <p>Para cada arquivo: valida tudo, grava o bruto no bronze (imutável), registra no Postgres numa
 * transação e só então apaga do inbox. Arquivo com problema vai para jev\error com um .erro.txt.</p>
 */
@Component
public class Mt5Importer {

    private static final Logger log = LoggerFactory.getLogger(Mt5Importer.class);
    private static final String COLLECTOR = "mt5-import@0.2";

    private final Mt5Properties props;
    private final LakeStorage lake;
    private final Mt5Repository repo;

    public Mt5Importer(Mt5Properties props, LakeStorage lake, Mt5Repository repo) {
        this.props = props;
        this.lake = lake;
        this.repo = repo;
    }

    public record ImportSummary(int files, int imported, int duplicates, int errors,
                                Map<Mt5FileKind, Integer> rowsByKind) {
    }

    private enum Outcome { IMPORTED, DUPLICATE }

    /** Uma varredura do inbox. Synchronized: o agendador e um comando nunca importam o mesmo arquivo juntos. */
    public synchronized ImportSummary importOnce() {
        Path inbox = props.inbox();
        Map<Mt5FileKind, Integer> rows = new EnumMap<>(Mt5FileKind.class);
        if (!Files.isDirectory(inbox)) {
            log.debug("Inbox do MT5 ainda não existe: {}", inbox);
            return new ImportSummary(0, 0, 0, 0, rows);
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(inbox)) {
            // o nome traz o horário da exportação: ordem alfabética = ordem cronológica dentro de cada tipo
            files = s.filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.warn("Não consegui listar {}: {}", inbox, e.toString());
            return new ImportSummary(0, 0, 0, 1, rows);
        }

        int imported = 0, duplicates = 0, errors = 0;
        for (Path f : files) {
            try {
                Outcome o = importFile(f, rows);
                if (o == Outcome.IMPORTED) imported++;
                else duplicates++;
            } catch (Exception e) {
                errors++;
                quarantine(f, e);
            }
        }
        if (!files.isEmpty()) {
            log.info("MT5: {} arquivos, {} importados, {} repetidos, {} com erro {}", files.size(), imported,
                    duplicates, errors, rows);
        }
        return new ImportSummary(files.size(), imported, duplicates, errors, rows);
    }

    private Outcome importFile(Path f, Map<Mt5FileKind, Integer> rowsByKind) throws IOException {
        Instant seen = Instant.now();
        String name = f.getFileName().toString();
        Mt5FileKind kind = Mt5FileKind.fromFileName(name)
                .orElseThrow(() -> new IllegalArgumentException("tipo de arquivo desconhecido: " + name));
        byte[] content = Files.readAllBytes(f);
        String sha = LocalDiskLakeStorage.sha256(content);
        if (repo.fileExists(sha)) {
            Files.delete(f);
            return Outcome.DUPLICATE;
        }

        Mt5Csv csv = Mt5Csv.parse(content);
        FileMeta meta = FileMeta.of(csv);
        String rejected = props.rejectAccount(meta.login(), meta.server());
        if (rejected != null) throw new IllegalArgumentException("arquivo recusado: " + rejected);

        // valida e converte ANTES de gravar qualquer coisa
        Parsed parsed = switch (kind) {
            case CALENDAR -> {
                List<CalendarValue> v = Mt5Parsers.calendar(csv, meta);
                yield new Parsed("fx", v.size(), min(v, CalendarValue::scheduledAt), max(v, CalendarValue::scheduledAt),
                        id -> repo.insertCalendarValues(id, v));
            }
            case CALEVENTS -> {
                var d = Mt5Parsers.calendarEvents(csv);
                yield new Parsed("fx", d.size(), null, null, id -> repo.upsertCalendarEvents(d, meta.seenUtc()));
            }
            case CANDLES -> {
                List<Candle> c = Mt5Parsers.candles(csv, meta, props.symbols());
                var summary = Mt5Parsers.summarize(c);
                // arquivo de um só mercado leva esse mercado; o arquivo ao vivo pode misturar (fica fx)
                List<String> markets = summary.stream().map(Mt5Parsers.CandleSummary::market).distinct().toList();
                String market = markets.size() == 1 ? markets.get(0) : "fx";
                yield new Parsed(market, c.size(), min(c, Candle::timeUtc), max(c, Candle::timeUtc), id -> {
                    repo.upsertCandleStatus(summary);
                    return c.size();
                });
            }
            case SYMBOLS -> {
                List<InstrumentSpec> s = Mt5Parsers.symbols(csv, meta, props.symbols());
                yield new Parsed("fx", s.size(), null, null, id -> {
                    repo.insertSpecs(id, s);
                    return s.size();
                });
            }
        };

        Map<String, Object> lakeMeta = new LinkedHashMap<>();
        lakeMeta.put("file_name", name);
        lakeMeta.put("kind", kind.prefix());
        lakeMeta.put("rows", parsed.rows());
        lakeMeta.putAll(csv.meta());           // exporter, server, login, offset_s, origin, seen_utc
        lakeMeta.put("collector", COLLECTOR);
        LakeStorage.StoredObject stored = lake.writeBronze(kind.lakeSource(), seen, "csv", content, lakeMeta);
        String lakePath = stored.relativePath().toString().replace('\\', '/');

        int written = repo.inTransaction(() -> {
            long fileId = repo.insertFile(kind, parsed.market(), name, sha, lakePath, parsed.rows(), parsed.minTime(),
                    parsed.maxTime(), seen);
            return parsed.save().apply(fileId);
        });
        rowsByKind.merge(kind, written, Integer::sum);
        Files.delete(f);
        log.debug("{} importado: {} linhas ({} gravadas) → {}", name, parsed.rows(), written, lakePath);
        return Outcome.IMPORTED;
    }

    private record Parsed(String market, int rows, Instant minTime, Instant maxTime, Function<Long, Integer> save) {
    }

    private void quarantine(Path f, Exception e) {
        String name = f.getFileName().toString();
        log.warn("MT5: arquivo {} recusado: {}", name, e.getMessage());
        try {
            Files.createDirectories(props.errorDir());
            Files.move(f, props.errorDir().resolve(name), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(props.errorDir().resolve(name + ".erro.txt"),
                    Instant.now() + "\n" + e + (e.getCause() == null ? "" : "\ncausa: " + e.getCause()) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException io) {
            log.warn("Não consegui mover {} para {}: {}", name, props.errorDir(), io.toString());
        }
    }

    private static <T> Instant min(List<T> list, Function<T, Instant> f) {
        return list.stream().map(f).min(Comparator.naturalOrder()).orElse(null);
    }

    private static <T> Instant max(List<T> list, Function<T, Instant> f) {
        return list.stream().map(f).max(Comparator.naturalOrder()).orElse(null);
    }
}
