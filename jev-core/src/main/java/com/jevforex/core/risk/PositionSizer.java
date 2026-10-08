package com.jevforex.core.risk;

import com.jevforex.core.Instrument;

/**
 * Calcula o volume de uma ordem a partir do risco que você aceita.
 *
 * <p>Fluxo:</p>
 * <ol>
 *   <li>alvo = saldo × risco-alvo do mercado (%)</li>
 *   <li>teto = menor entre (saldo × teto global %) e teto global em US$</li>
 *   <li>lotes = alvo ÷ perda por lote no stop, arredondado PARA BAIXO no passo de volume</li>
 *   <li>se lotes &lt; lote mínimo: aplica a {@link RiskSettings.MinLotPolicy}</li>
 * </ol>
 *
 * <p>Com bancas pequenas (ex.: US$ 20), o lote mínimo costuma exigir mais risco que o alvo.
 * Essa classe deixa isso explícito em vez de esconder.</p>
 */
public final class PositionSizer {

    private static final double EPS = 1e-9;

    private PositionSizer() {
    }

    public static SizingResult size(Instrument instrument,
                                    double stopDistance,
                                    double balance,
                                    RiskSettings settings) {
        if (balance <= 0) {
            return SizingResult.skipped(instrument, 0, 0, 0, 0, "saldo <= 0");
        }
        if (stopDistance <= 0) {
            return SizingResult.skipped(instrument, 0, 0, 0, 0, "distância de stop inválida");
        }
        RiskSettings.MarketRisk mr = settings.forMarket(instrument.market());
        if (!mr.enabled()) {
            return SizingResult.skipped(instrument, 0, 0, 0, 0, "mercado " + instrument.market().code() + " desativado");
        }

        double lossPerLot = instrument.lossPerLot(stopDistance);
        double target = balance * mr.riskPerTradePct() / 100.0;
        double cap = settings.global().maxRiskPerTradeMoney(balance);
        double minLotRisk = lossPerLot * instrument.minLot();

        // volume que respeita o alvo, arredondado para baixo
        double ideal = Math.min(target, cap) / lossPerLot;
        double lots = floorToStep(ideal, instrument.lotStep());
        lots = Math.min(lots, instrument.maxLot());

        if (lots + EPS >= instrument.minLot()) {
            double risk = lots * lossPerLot;
            return SizingResult.ok(instrument, lots, risk, risk / balance * 100.0, target, cap, minLotRisk,
                    "volume dentro do alvo");
        }

        // nem o lote mínimo cabe no alvo
        if (settings.global().minLotPolicy() == RiskSettings.MinLotPolicy.ALLOW_UP_TO_CAP
                && minLotRisk <= cap + EPS) {
            return SizingResult.minLotAboveTarget(instrument, instrument.minLot(), minLotRisk,
                    minLotRisk / balance * 100.0, target, cap,
                    String.format("lote mínimo arrisca US$ %.2f (alvo US$ %.2f, teto US$ %.2f)", minLotRisk, target, cap));
        }
        return SizingResult.skipped(instrument, target, cap, minLotRisk, minLotRisk / balance * 100.0,
                String.format("lote mínimo arrisca US$ %.2f, acima do %s US$ %.2f",
                        minLotRisk,
                        settings.global().minLotPolicy() == RiskSettings.MinLotPolicy.SKIP ? "alvo" : "teto",
                        settings.global().minLotPolicy() == RiskSettings.MinLotPolicy.SKIP ? target : cap));
    }

    static double floorToStep(double value, double step) {
        // soma um epsilon para evitar 0.35 virar 0.34 por erro de ponto flutuante
        double n = Math.floor(value / step + EPS);
        return Math.round(n * step * 1e8) / 1e8;
    }
}
