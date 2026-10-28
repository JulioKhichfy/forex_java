package com.jevforex.app.trade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decisões de juros lidas do comunicado oficial (28/10/2026): o calendário da MetaQuotes parou de trazer os resultados
 * (o próprio site do MQL5 mostra "N/D"). Perto de cada decisão de juros de importância alta do calendário, procura o
 * comunicado no endereço previsível do banco central e lê a taxa. Grava em {@code rate_decision} (fonte separada; o
 * calendar_event continua sendo só o que o MT5 mandou). Até 5 min depois do horário tenta a cada 5 s; depois, a cada
 * minuto, até 6 h.
 * <ul>
 *   <li>USD — Fed: federalreserve.gov/newsevents/pressreleases/monetaryAAAAMMDDa.htm (data de Nova York); o teto da
 *       banda ("3-3/4 to 4 percent" → 4), como o MT5 e o Forex Factory.</li>
 *   <li>CAD — BoC: bankofcanada.ca/AAAA/MM/fad-press-release-AAAA-MM-DD/ ("target for the overnight rate … to 2.5%").</li>
 * </ul>
 */
@Component
@Profile("!cli")
public class CentralBankRates {

    private static final Logger log = LoggerFactory.getLogger(CentralBankRates.class);
    private static final Duration FAST = Duration.ofMinutes(5);
    private static final Duration GIVE_UP = Duration.ofHours(6);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

    /** O que foi lido: a taxa e o trecho do comunicado de onde ela saiu. */
    record Reading(double rate, String snippet) {
    }

    private final ObjectProvider<JdbcClient> jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, Instant> nextTry = new HashMap<>();

    public CentralBankRates(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ endereços e leitura (puros, testáveis)

    static String url(String currency, Instant at) {
        return switch (currency) {
            case "USD" -> "https://www.federalreserve.gov/newsevents/pressreleases/monetary"
                    + LocalDate.ofInstant(at, NEW_YORK).format(DateTimeFormatter.BASIC_ISO_DATE) + "a.htm";
            case "CAD" -> {
                LocalDate d = LocalDate.ofInstant(at, TORONTO);
                yield String.format("https://www.bankofcanada.ca/%d/%02d/fad-press-release-%s/", d.getYear(),
                        d.getMonthValue(), d);
            }
            default -> null;
        };
    }

    static Reading parse(String currency, String html) {
        String t = text(html);
        return switch (currency) {
            case "USD" -> fed(t);
            case "CAD" -> boc(t);
            default -> null;
        };
    }

    private static final Pattern FED = Pattern.compile(
            "target range for the federal funds rate.{0,80}?\\b(\\d+(?:-\\d/\\d)?) to (\\d+(?:-\\d/\\d)?) percent");

    /** "… maintain the target range for the federal funds rate at 3-3/4 to 4 percent" → 4 (o teto da banda). */
    static Reading fed(String t) {
        Matcher m = FED.matcher(t);
        if (!m.find()) return null;
        return new Reading(fraction(m.group(2)), snippet(t, m));
    }

    private static final Pattern BOC = Pattern.compile(
            "target for the overnight rate.{0,80}?\\b(?:at|to) (\\d+(?:\\.\\d+)?)\\s?([¼½¾])?\\s?%");

    /** "… increased its target for the overnight rate by 25 basis points to 2.5%" → 2,5; "at 2¼%" → 2,25. */
    static Reading boc(String t) {
        Matcher m = BOC.matcher(t);
        if (!m.find()) return null;
        double v = Double.parseDouble(m.group(1));
        if (m.group(2) != null) v += switch (m.group(2)) { case "¼" -> 0.25; case "½" -> 0.5; default -> 0.75; };
        return new Reading(v, snippet(t, m));
    }

    /** "3-3/4" → 3,75; "4" → 4. */
    static double fraction(String s) {
        int dash = s.indexOf('-');
        if (dash < 0) return Double.parseDouble(s);
        String[] f = s.substring(dash + 1).split("/");
        return Double.parseDouble(s.substring(0, dash)) + Double.parseDouble(f[0]) / Double.parseDouble(f[1]);
    }

    /** HTML → texto corrido (sem tags, entidades comuns, espaços únicos). */
    static String text(String html) {
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&#160;", " ").replace("&frac14;", "¼").replace("&frac12;", "½")
                .replace("&frac34;", "¾").replace("&amp;", "&").replace(' ', ' ').replaceAll("\\s+", " ");
    }

    private static String snippet(String t, Matcher m) {
        int a = Math.max(Math.max(0, m.start() - 100), t.lastIndexOf('.', m.start()) + 1);
        int b = Math.min(t.length(), m.end() + 40);
        return t.substring(a, b).trim();
    }

    // ------------------------------------------------------------------ o laço

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT5S")
    public void tick() {
        Instant now = Instant.now();
        List<Map<String, Object>> due = jdbc.getObject().sql("""
                        SELECT DISTINCT e.currency, e.scheduled_at, e.event_code
                          FROM calendar_event e
                         WHERE e.importance = 'HIGH' AND e.event_code LIKE '%interest-rate-decision'
                           AND e.currency IN ('USD', 'CAD')
                           AND e.scheduled_at BETWEEN :a AND :b
                           AND NOT EXISTS (SELECT 1 FROM rate_decision r
                                            WHERE r.currency = e.currency AND r.event_at = e.scheduled_at)
                        """).param("a", Timestamp.from(now.minus(GIVE_UP))).param("b", Timestamp.from(now.plusSeconds(20)))
                .query().listOfRows();
        for (Map<String, Object> r : due) {
            String cur = ((String) r.get("currency")).trim();
            Instant at = ((Timestamp) r.get("scheduled_at")).toInstant();
            String key = cur + "@" + at;
            if (nextTry.getOrDefault(key, Instant.EPOCH).isAfter(now)) continue;
            nextTry.put(key, now.plus(now.isBefore(at.plus(FAST)) ? Duration.ofSeconds(5) : Duration.ofMinutes(1)));
            fetch(cur, at, (String) r.get("event_code"));
        }
    }

    private void fetch(String currency, Instant at, String eventCode) {
        String u = url(currency, at);
        if (u == null) return;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0 (compatible; jev-forex)").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return;   // ainda não publicado (404)
            Reading x = parse(currency, r.body());
            if (x == null) {
                log.warn("Juros {}: comunicado em {} sem a taxa reconhecível", currency, u);
                nextTry.put(currency + "@" + at, Instant.now().plus(Duration.ofMinutes(1)));
                return;
            }
            int n = jdbc.getObject().sql("""
                            INSERT INTO rate_decision (currency, event_at, event_code, rate, source_url, snippet)
                            VALUES (:c, :at, :code, :rate, :u, :s) ON CONFLICT (currency, event_at) DO NOTHING
                            """).param("c", currency).param("at", Timestamp.from(at)).param("code", eventCode)
                    .param("rate", x.rate()).param("u", u).param("s", x.snippet()).update();
            if (n > 0) log.info("Juros {} ({}): {}% pelo comunicado oficial — \"{}\"", currency, at, x.rate(), x.snippet());
        } catch (Exception e) {
            log.debug("Juros {}: {} ({})", currency, u, e.toString());
        }
    }
}
