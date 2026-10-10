package com.jevforex.app.live;

import com.jevforex.app.config.FeatureProperties;
import com.jevforex.features.FeatureConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * Dispara as previsões ao vivo nos MESMOS momentos de decisão do treino (o placar fica comparável ao
 * experimento): hora cheia nas horas de controle (seg a sex, sem sexta ≥ 19h) e evento com peso + atraso do
 * evento. Roda {@code live.delay-seconds} depois do minuto, para a barra que fecha no momento chegar do MT5.
 */
@Component
@Profile("!cli")
@ConditionalOnProperty(prefix = "live", name = "enabled", havingValue = "true")
public class LiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(LiveScheduler.class);
    private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "GBP", "JPY", "CHF", "AUD", "CAD", "NZD");

    private final LivePredictionService service;
    private final FeatureProperties featureProps;
    private final ObjectProvider<JdbcClient> jdbc;

    public LiveScheduler(LivePredictionService service, FeatureProperties featureProps, ObjectProvider<JdbcClient> jdbc) {
        this.service = service;
        this.featureProps = featureProps;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${live.cron:20 * * * * *}", zone = "UTC")
    public void tick() {
        LocalDateTime t = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        String trigger = triggerFor(t);
        if (trigger == null) return;
        try {
            LivePredictionService.Run r = service.predict(t, trigger);
            if (r.note() != null) log.info("Previsão {} {}: {}", trigger, t, r.note());
        } catch (RuntimeException e) {
            // falhar fechado: sem previsão neste momento; o dashboard mostra a última e a idade dela
            log.warn("Previsão {} {} falhou: {}", trigger, t, e.toString());
        }
    }

    /** SCHEDULE, EVENT ou null (não é momento de decisão). */
    String triggerFor(LocalDateTime t) {
        FeatureConfig cfg = featureProps.toConfig();
        DayOfWeek dow = t.getDayOfWeek();
        boolean weekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
        if (!weekday) return null;
        if (t.getMinute() == 0 && cfg.controlHoursUtc().contains(t.getHour())
                && !(dow == DayOfWeek.FRIDAY && t.getHour() >= 19)) {
            return "SCHEDULE";
        }
        List<String> important = cfg.importanceWeights().entrySet().stream().filter(e -> e.getValue() > 0)
                .map(java.util.Map.Entry::getKey).toList();
        if (important.isEmpty()) return null;
        LocalDateTime scheduled = t.minusMinutes(cfg.eventLagMinutes());
        Long n = jdbc.getObject().sql("""
                        SELECT count(*) FROM calendar_event
                         WHERE scheduled_at >= :from AND scheduled_at < :to
                           AND importance IN (:imp) AND currency IN (:cur)
                        """)
                .param("from", Timestamp.from(scheduled.toInstant(ZoneOffset.UTC)))
                .param("to", Timestamp.from(scheduled.plusMinutes(1).toInstant(ZoneOffset.UTC)))
                .param("imp", important)
                .param("cur", List.copyOf(CURRENCIES))
                .query(Long.class).single();
        return n != null && n > 0 ? "EVENT" : null;
    }
}
