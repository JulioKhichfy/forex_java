package com.jevforex.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

/**
 * Bloco {@code silver} do application.yml: normalização bronze → silver.
 *
 * @param duckdbMemory limite de memória do DuckDB (o excedente vai para disco)
 * @param candles      regras dos candles
 * @param calendar     regras do calendário
 */
@ConfigurationProperties(prefix = "silver")
public record SilverProperties(String duckdbMemory, Candles candles, Calendar calendar) {

    public SilverProperties {
        if (duckdbMemory == null || duckdbMemory.isBlank()) duckdbMemory = "2GB";
        if (candles == null) candles = new Candles(null);
        if (calendar == null) calendar = new Calendar(35);
    }

    /**
     * @param utcReliableFrom barras antes desta data ficam fora do silver: o histórico antigo da corretora pode
     *                        estar em outro fuso (o comando normalize mostra os anos fora do padrão). null = sem corte
     */
    public record Candles(LocalDate utcReliableFrom) {
    }

    /**
     * @param actualLatencySeconds no histórico (sem first_seen_at real), o actual conta como disponível em
     *                             scheduled + este atraso (documento mestre, cap. 8). Ajuste com a latência
     *                             medida ao vivo, que o comando normalize mostra.
     */
    public record Calendar(int actualLatencySeconds) {
        public Calendar {
            if (actualLatencySeconds < 0) throw new IllegalArgumentException("silver.calendar.actual-latency-seconds < 0");
        }
    }
}
