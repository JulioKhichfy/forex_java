package com.jevforex.normalize;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Teste de horário de verão sobre os dados reais (documento mestre, capítulo 8).
 *
 * <p>O forex abre a semana no domingo às 17:00 de Nova York: 21:00 UTC no horário de verão americano e
 * 22:00 UTC no padrão. Se a conversão para UTC estiver certa, a primeira barra de cada semana cai numa
 * hora UTC que acompanha (ou é fixa, se a corretora não segue Nova York) — nunca um padrão confuso.</p>
 */
public final class WeeklyOpenCheck {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private WeeklyOpenCheck() {
    }

    /**
     * @param summerHours       hora UTC da abertura → semanas, no horário de verão americano
     * @param winterHours       o mesmo no horário padrão
     * @param verdict           leitura em português do conjunto
     * @param yearsOutOfPattern anos cuja hora de abertura dominante difere da do conjunto (ex.: dados antigos
     *                          gravados em outro fuso) → descrição
     */
    public record Result(Map<Integer, Long> summerHours, Map<Integer, Long> winterHours, int holidayReopens,
                         String verdict, Map<Integer, String> yearsOutOfPattern) {
    }

    /** @param opens primeira barra depois de cada fechamento de 36 h ou mais (UTC) */
    public static Result evaluate(List<LocalDateTime> opens) {
        Map<Integer, Long> summer = new TreeMap<>();
        Map<Integer, Long> winter = new TreeMap<>();
        Map<Integer, Map<Integer, Long>> summerByYear = new TreeMap<>();
        Map<Integer, Map<Integer, Long>> winterByYear = new TreeMap<>();
        int holidays = 0;
        for (LocalDateTime open : opens) {
            DayOfWeek d = open.getDayOfWeek();
            if (d != DayOfWeek.SUNDAY && d != DayOfWeek.MONDAY) {
                holidays++;   // reabertura depois de feriado no meio da semana: não é a abertura semanal
                continue;
            }
            boolean dst = NEW_YORK.getRules().isDaylightSavings(open.toInstant(ZoneOffset.UTC));
            (dst ? summer : winter).merge(open.getHour(), 1L, Long::sum);
            (dst ? summerByYear : winterByYear).computeIfAbsent(open.getYear(), y -> new TreeMap<>())
                    .merge(open.getHour(), 1L, Long::sum);
        }

        // cada ano tem de repetir o padrão do conjunto (na estação que tiver dados)
        Map<Integer, String> outOfPattern = new TreeMap<>();
        if (!summer.isEmpty() && !winter.isEmpty()) {
            int s = dominant(summer), w = dominant(winter);
            java.util.Set<Integer> years = new java.util.TreeSet<>(summerByYear.keySet());
            years.addAll(winterByYear.keySet());
            for (int y : years) {
                Map<Integer, Long> ys = summerByYear.getOrDefault(y, Map.of());
                Map<Integer, Long> yw = winterByYear.getOrDefault(y, Map.of());
                boolean ok = (ys.isEmpty() || dominant(ys) == s) && (yw.isEmpty() || dominant(yw) == w);
                if (!ok) outOfPattern.put(y, "verão " + (ys.isEmpty() ? "-" : dominant(ys) + "h")
                        + ", padrão " + (yw.isEmpty() ? "-" : dominant(yw) + "h") + " UTC");
            }
        }
        String verdict = verdict(summer, winter);
        if (!outOfPattern.isEmpty()) verdict = "ATENÇÃO: anos fora do padrão " + outOfPattern.keySet()
                + " (fuso diferente nos dados?); no conjunto: " + verdict;
        return new Result(summer, winter, holidays, verdict, outOfPattern);
    }

    static String verdict(Map<Integer, Long> summer, Map<Integer, Long> winter) {
        if (summer.isEmpty() || winter.isEmpty()) return "dados insuficientes (precisa de semanas nas duas estações)";
        int s = dominant(summer), w = dominant(winter);
        if (s == 21 && w == 22) return "acompanha Nova York (17:00 NY): conversão para UTC consistente";
        if (s == w) return "abertura em hora UTC fixa (" + s + "h) nas duas estações: corretora sem horário de "
                + "verão; UTC consistente";
        if ((w - s + 24) % 24 == 1) return "acompanha o horário de verão americano (" + s + "h/" + w + "h UTC): "
                + "UTC consistente";
        return "ATENÇÃO: padrão inesperado (verão " + s + "h, padrão " + w + "h UTC): conferir conversão de fuso";
    }

    private static int dominant(Map<Integer, Long> hours) {
        return hours.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }
}
