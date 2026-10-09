package com.jevforex.normalize;

import com.jevforex.lake.LakeSql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Horário de divulgação de um comunicado/ata do arquivo histórico, pelo calendário do MT5 (silver).
 * Disponível = horário oficial + 30 s (documento mestre, capítulo 8).
 *
 * <ul>
 *   <li>{@code same_day}: o evento no dia da referência (ou no dia vizinho, por fuso: BoJ em JST);</li>
 *   <li>{@code first_after}: o primeiro evento a partir do dia seguinte à referência;</li>
 *   <li>{@code after_next:<código>}: o primeiro evento depois da próxima ocorrência de &lt;código&gt;.</li>
 * </ul>
 */
final class ReleaseResolver {

    static final int AVAILABILITY_DELAY_SECONDS = 30;

    private final Map<String, List<LocalDateTime>> byCode;

    private ReleaseResolver(Map<String, List<LocalDateTime>> byCode) {
        this.byCode = byCode;
    }

    static ReleaseResolver load(LakeSql sql, Path lakeRoot) {
        Path calendar = lakeRoot.resolve("silver").resolve(CalendarNormalizer.TABLE);
        Map<String, List<LocalDateTime>> m = new HashMap<>();
        if (Files.isDirectory(calendar)) {
            sql.query("SELECT DISTINCT event_code, CAST(scheduled_utc AS VARCHAR) FROM read_parquet('"
                    + LakeSql.slashes(calendar) + "/**/*.parquet', hive_partitioning = true) ORDER BY 1, 2", rs -> {
                m.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(Bronze.ts(rs.getString(2)));
                return null;
            });
        }
        return new ReleaseResolver(m);
    }

    static ReleaseResolver of(Map<String, List<LocalDateTime>> events) {
        return new ReleaseResolver(events);
    }

    /** @return o instante de disponibilidade, ou vazio se o calendário não tem o evento */
    Optional<Instant> resolve(String eventCode, String anchor, LocalDate ref) {
        List<LocalDateTime> events = byCode.get(eventCode);
        if (events == null || events.isEmpty() || anchor == null || ref == null) return Optional.empty();
        Optional<LocalDateTime> t;
        if (anchor.equals("same_day")) {
            t = events.stream().filter(e -> e.toLocalDate().equals(ref)).findFirst()
                    .or(() -> events.stream().filter(e -> Math.abs(e.toLocalDate().toEpochDay() - ref.toEpochDay()) == 1)
                            .findFirst());
        } else if (anchor.equals("first_after")) {
            LocalDateTime from = ref.plusDays(1).atStartOfDay();
            t = events.stream().filter(e -> !e.isBefore(from)).findFirst();
        } else if (anchor.startsWith("after_next:")) {
            List<LocalDateTime> anchors = byCode.getOrDefault(anchor.substring("after_next:".length()), List.of());
            Optional<LocalDateTime> next = anchors.stream().filter(a -> a.toLocalDate().isAfter(ref)).findFirst();
            t = next.flatMap(n -> events.stream().filter(e -> e.isAfter(n)).findFirst());
        } else {
            t = Optional.empty();
        }
        return t.map(e -> e.toInstant(ZoneOffset.UTC).plusSeconds(AVAILABILITY_DELAY_SECONDS));
    }
}
