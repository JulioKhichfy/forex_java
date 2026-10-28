package com.jevforex.app.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Consenso do calendário do Forex Factory (28/10/2026): o MT5 não traz o previsto das decisões de juros (BoC, Fed,
 * RBNZ…) e, em 28/10, também parou de trazer os resultados. O arquivo público da semana (nfs.faireconomy.media) traz o
 * previsto e o anterior — não o resultado. Usado só para o CONSENSO na página "Principal de hoje"; lido no máximo a cada
 * 30 min (a fonte pede moderação) e guardado em memória; falhou = fica o que já havia.
 */
@Component
public class ForexFactoryCalendar {

    private static final Logger log = LoggerFactory.getLogger(ForexFactoryCalendar.class);
    private static final List<String> URLS = List.of("https://nfs.faireconomy.media/ff_calendar_thisweek.json",
            "https://nfs.faireconomy.media/ff_calendar_nextweek.json");
    private static final Duration TTL = Duration.ofMinutes(30);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper mapper;
    private List<Item> cache = List.of();
    private Instant loadedAt = Instant.EPOCH;
    private Instant lastError = Instant.EPOCH;

    public ForexFactoryCalendar(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param forecast / previous o texto como vem ("2.50%", "-47", "1.2M"); {@link #number} converte
     */
    public record Item(String title, String currency, Instant at, String impact, String forecast, String previous) {
    }

    public synchronized List<Item> items() {
        if (loadedAt.plus(TTL).isAfter(Instant.now()) || lastError.plus(Duration.ofMinutes(10)).isAfter(Instant.now())) {
            return cache;
        }
        List<Item> out = new ArrayList<>();
        try {
            for (String url : URLS) {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "jev-forex").GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) continue;   // a da próxima semana só existe perto do fim de semana
                for (JsonNode n : mapper.readTree(r.body())) {
                    try {
                        out.add(new Item(n.path("title").asText(), n.path("country").asText(),
                                OffsetDateTime.parse(n.path("date").asText()).toInstant(), n.path("impact").asText(),
                                n.path("forecast").asText(""), n.path("previous").asText("")));
                    } catch (RuntimeException ignored) {
                        // linha sem data válida: fica de fora
                    }
                }
            }
            if (!out.isEmpty()) {
                cache = List.copyOf(out);
                loadedAt = Instant.now();
            }
        } catch (Exception e) {
            lastError = Instant.now();
            log.warn("Forex Factory: sem o calendário ({}); fica o anterior", e.toString());
        }
        return cache;
    }

    /** "2.50%" → 2.5; "-47" → −47; "1.2M" → 1.2; "" → null (as unidades só importam para comparar com o anterior). */
    static Double number(String s) {
        if (s == null) return null;
        String t = s.trim().replace("%", "").replace(",", "");
        if (t.isEmpty()) return null;
        char last = Character.toUpperCase(t.charAt(t.length() - 1));
        if ("KMBT".indexOf(last) >= 0) t = t.substring(0, t.length() - 1);
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
