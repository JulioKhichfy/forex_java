package com.jevforex.collect.archive;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Um comunicado ou ata do arquivo histórico de um banco central.
 *
 * <p>O horário de divulgação não vem da página: vem do calendário do MT5, pela regra {@code releaseAnchor}
 * aplicada ao evento {@code releaseEvent} (resolvido no normalizador):</p>
 * <ul>
 *   <li>{@code same_day}: o evento no dia {@code refDate} (decisões, accounts do BCE);</li>
 *   <li>{@code first_after}: o primeiro evento depois de {@code refDate} (ata do FOMC, 3 semanas depois);</li>
 *   <li>{@code after_next:<código>}: o primeiro evento depois da PRÓXIMA ocorrência de outro evento
 *       (ata do BoJ, publicada depois da reunião seguinte).</li>
 * </ul>
 *
 * @param kind    statement | minutes | accounts | press_conference | summary_minutes
 * @param refDate data da reunião ou da publicação, como está na URL
 */
public record ArchiveItem(String url, String title, String kind, LocalDate refDate, String releaseEvent,
                          String releaseAnchor) {

    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("archive", true);
        m.put("doc_kind", kind);
        m.put("ref_date", refDate.toString());
        m.put("release_event", releaseEvent);
        m.put("release_anchor", releaseAnchor);
        return m;
    }
}
