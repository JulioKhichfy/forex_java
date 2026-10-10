package com.jevforex.core.risk;

import com.jevforex.core.Instrument;
import com.jevforex.core.Market;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionSizerTest {

    // EURUSD: tick 0.00001 vale US$ 1 por lote → 1 pip (0.0001) vale US$ 10 por lote
    private final Instrument eurusd = new Instrument("EURUSD", Market.FX, 0.00001, 1.0, 0.01, 0.01, 100, 0.0014);

    private RiskSettings settings(double fxPct, double capPct, double capUsd, RiskSettings.MinLotPolicy policy) {
        var global = new RiskSettings.Global(capPct, capUsd, 50, 20, 35, 3, 50, policy);
        var fx = new RiskSettings.MarketRisk(true, fxPct, 50, 3, 1.0);
        return new RiskSettings(global, Map.of(Market.FX, fx), new RiskSettings.Exits(1.5, 1.5),
                new RiskSettings.Orders(0.01, 0.05, 60, 20, 30, 30, 90, 10, 2, 2.0, 300));
    }

    @Test
    void contaGrande_volumeDentroDoAlvo() {
        // exemplo do documento: US$ 10.000, 0,5%, stop 14 pips → 0,35 lote, risco US$ 49
        var r = PositionSizer.size(eurusd, 0.0014, 10_000, settings(0.5, 2, 0, RiskSettings.MinLotPolicy.SKIP));
        assertEquals(SizingResult.Status.OK, r.status());
        assertEquals(0.35, r.lots(), 1e-9);
        assertEquals(49.0, r.riskUsd(), 1e-6);
    }

    @Test
    void banca20_skip_naoOpera() {
        // US$ 20, alvo 5% = US$ 1,00; lote mínimo arrisca US$ 1,40 → SKIP
        var r = PositionSizer.size(eurusd, 0.0014, 20, settings(5, 10, 2, RiskSettings.MinLotPolicy.SKIP));
        assertEquals(SizingResult.Status.SKIPPED, r.status());
        assertEquals(1.40, r.minLotRiskUsd(), 1e-6);
    }

    @Test
    void banca20_allowUpToCap_operaComLoteMinimo() {
        // teto 10% = US$ 2,00 ≥ US$ 1,40 → opera 0,01 com 7% de risco
        var r = PositionSizer.size(eurusd, 0.0014, 20, settings(5, 10, 2, RiskSettings.MinLotPolicy.ALLOW_UP_TO_CAP));
        assertEquals(SizingResult.Status.MIN_LOT_ABOVE_TARGET, r.status());
        assertEquals(0.01, r.lots(), 1e-9);
        assertEquals(7.0, r.riskPct(), 1e-6);
    }

    @Test
    void banca20_tetoEmDinheiroMenorQueLoteMinimo_naoOpera() {
        // teto em dinheiro US$ 1,00 vence o teto de 10% → lote mínimo (US$ 1,40) não cabe
        var r = PositionSizer.size(eurusd, 0.0014, 20, settings(5, 10, 1.0, RiskSettings.MinLotPolicy.ALLOW_UP_TO_CAP));
        assertEquals(SizingResult.Status.SKIPPED, r.status());
    }
}
