package com.jevforex.core.risk;

import com.jevforex.core.Instrument;

/**
 * Resultado do dimensionamento de uma ordem.
 *
 * @param status      OK, MIN_LOT_ABOVE_TARGET (opera com lote mínimo acima do alvo) ou SKIPPED
 * @param lots        volume final (0 quando SKIPPED)
 * @param riskUsd     perda em US$ se o stop for atingido
 * @param riskPct     a mesma perda em % do saldo
 * @param targetUsd   risco-alvo do mercado em US$
 * @param capUsd      teto global por trade em US$
 * @param minLotRiskUsd perda em US$ com o lote mínimo
 * @param reason      explicação em português, vai para o log e para o dashboard
 */
public record SizingResult(
        String symbol,
        Status status,
        double lots,
        double riskUsd,
        double riskPct,
        double targetUsd,
        double capUsd,
        double minLotRiskUsd,
        String reason) {

    public enum Status { OK, MIN_LOT_ABOVE_TARGET, SKIPPED }

    public boolean tradable() {
        return status != Status.SKIPPED;
    }

    static SizingResult ok(Instrument i, double lots, double risk, double pct, double target, double cap,
                           double minLotRisk, String reason) {
        return new SizingResult(i.symbol(), Status.OK, lots, risk, pct, target, cap, minLotRisk, reason);
    }

    static SizingResult minLotAboveTarget(Instrument i, double lots, double risk, double pct, double target,
                                          double cap, String reason) {
        return new SizingResult(i.symbol(), Status.MIN_LOT_ABOVE_TARGET, lots, risk, pct, target, cap, risk, reason);
    }

    static SizingResult skipped(Instrument i, double target, double cap, double minLotRisk, double minLotPct,
                                String reason) {
        return new SizingResult(i.symbol(), Status.SKIPPED, 0, 0, minLotPct, target, cap, minLotRisk, reason);
    }
}
