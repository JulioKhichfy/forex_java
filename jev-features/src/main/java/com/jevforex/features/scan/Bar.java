package com.jevforex.features.scan;

import java.time.LocalDateTime;

/**
 * Barra de um período (M1…D1) em UTC.
 *
 * @param t      abertura da barra
 * @param closed a barra já fechou (o período acabou); a última costuma estar em formação
 */
public record Bar(LocalDateTime t, double open, double high, double low, double close, long volume, boolean closed) {
}
