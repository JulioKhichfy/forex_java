package com.jevforex.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Discursos do acervo do BIS (bronze/bis_speeches, um zip por ano com um CSV: url, title, description,
 * date, text, author).
 *
 * <p><b>Point-in-time:</b> o campo date do BIS tem erros (≈10% em 2024, ex.: dezembro no lugar de janeiro).
 * Vale a data de publicação no BIS, que está na URL (…/review/r240109a.htm = 09/01/2024), no fim do dia
 * (23:59:59 UTC): nunca antes de o texto poder ser conhecido. Servem para features diárias, não intradiárias
 * (documento mestre, capítulo 8).</p>
 */
public final class BisSpeeches {

    public static final String SOURCE = "bis_speeches";
    private static final Pattern URL_DATE = Pattern.compile("/r(\\d{2})(\\d{2})(\\d{2})[a-z]*\\.htm");

    /** Emissor reconhecido na descrição ("Speech by Mr X, Governor of the Bank of Canada, at …"). */
    public record Issuer(String name, String currency, List<String> patterns) {
        Issuer(String name, String currency, String... patterns) {
            this(name, currency, List.of(patterns));
        }
    }

    /**
     * Os 8 bancos centrais + os maiores bancos do Eurosistema (presidentes votam no Conselho do BCE).
     * A primeira instituição citada na descrição é a do orador.
     */
    public static final List<Issuer> ISSUERS = List.of(
            new Issuer("Federal Reserve", "USD", "federal reserve"),
            new Issuer("European Central Bank", "EUR", "european central bank"),
            new Issuer("Deutsche Bundesbank", "EUR", "deutsche bundesbank"),
            new Issuer("Banque de France", "EUR", "bank of france", "banque de france"),
            new Issuer("Banca d'Italia", "EUR", "bank of italy", "banca d'italia"),
            new Issuer("Banco de España", "EUR", "bank of spain", "banco de españa"),
            new Issuer("De Nederlandsche Bank", "EUR", "netherlands bank", "de nederlandsche bank"),
            new Issuer("Bank of England", "GBP", "bank of england"),
            new Issuer("Bank of Japan", "JPY", "bank of japan"),
            new Issuer("Swiss National Bank", "CHF", "swiss national bank"),
            new Issuer("Reserve Bank of Australia", "AUD", "reserve bank of australia"),
            new Issuer("Bank of Canada", "CAD", "bank of canada"),
            new Issuer("Reserve Bank of New Zealand", "NZD", "reserve bank of new zealand"));

    /**
     * @param speechDate   data informada pelo BIS (pode estar errada)
     * @param bisDate      data de publicação no BIS (da URL)
     * @param availableUtc quando pode entrar no backtest (fim do dia da publicação)
     * @param downloadedAt quando o zip foi baixado (first_seen do arquivo no bronze)
     */
    public record Speech(String url, String title, String description, LocalDate speechDate, LocalDate bisDate,
                         String author, String text, Issuer issuer, Instant availableUtc, Instant downloadedAt) {
    }

    public record Load(List<Speech> speeches, int zips, int records, int otherInstitutions, int withoutDate) {
    }

    private BisSpeeches() {
    }

    /** Lê a versão mais recente do zip de cada ano e devolve só os discursos dos emissores conhecidos. */
    public static Load read(Path lakeRoot) {
        Path dir = lakeRoot.resolve("bronze").resolve(SOURCE);
        if (!Files.isDirectory(dir)) return new Load(List.of(), 0, 0, 0, 0);
        Map<Integer, JsonNode> latest = new HashMap<>();
        Map<Integer, Path> zipOf = new HashMap<>();
        ObjectMapper json = new ObjectMapper();
        try (Stream<Path> s = Files.walk(dir, 2)) {
            for (Path meta : s.filter(p -> p.getFileName().toString().endsWith(".meta.json")).toList()) {
                JsonNode m = json.readTree(meta.toFile());
                int year = m.path("year").asInt();
                JsonNode prev = latest.get(year);
                if (prev == null || m.path("first_seen_at").asText().compareTo(prev.path("first_seen_at").asText()) > 0) {
                    latest.put(year, m);
                    zipOf.put(year, meta.resolveSibling(meta.getFileName().toString().replace(".meta.json", ".zip")));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<Speech> out = new ArrayList<>();
        int records = 0, other = 0, noDate = 0;
        for (var e : zipOf.entrySet()) {
            Instant downloaded = Instant.parse(latest.get(e.getKey()).path("first_seen_at").asText());
            for (CSVRecord r : records(e.getValue())) {
                records++;
                Optional<Issuer> issuer = issuerOf(r.get("description"));
                if (issuer.isEmpty()) {
                    other++;
                    continue;
                }
                LocalDate bis = bisDate(r.get("url"));
                LocalDate speech = parseDate(r.get("date"));
                LocalDate when = bis != null ? bis : speech;
                if (when == null) {
                    noDate++;
                    continue;
                }
                out.add(new Speech(r.get("url"), r.get("title"), r.get("description"), speech, bis, r.get("author"),
                        r.get("text"), issuer.get(), when.atTime(23, 59, 59).toInstant(ZoneOffset.UTC), downloaded));
            }
        }
        return new Load(out, zipOf.size(), records, other, noDate);
    }

    private static List<CSVRecord> records(Path zip) {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (!entry.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) continue;
                CSVParser p = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get()
                        .parse(new InputStreamReader(in, StandardCharsets.UTF_8));
                return p.getRecords();   // lê tudo antes de fechar o zip
            }
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Zip do BIS ilegível: " + zip, e);
        }
    }

    /** A primeira instituição citada na descrição é a do orador. */
    static Optional<Issuer> issuerOf(String description) {
        if (description == null) return Optional.empty();
        String d = description.toLowerCase(Locale.ROOT);
        Issuer best = null;
        int bestAt = Integer.MAX_VALUE;
        for (Issuer i : ISSUERS) {
            for (String p : i.patterns()) {
                int at = d.indexOf(p);
                if (at >= 0 && at < bestAt) {
                    best = i;
                    bestAt = at;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    static LocalDate bisDate(String url) {
        Matcher m = URL_DATE.matcher(url == null ? "" : url);
        if (!m.find()) return null;
        try {
            return LocalDate.of(2000 + Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        try {
            return s == null || s.length() < 10 ? null : LocalDate.parse(s.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
