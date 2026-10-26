package com.jevforex.app.trade;

import com.jevforex.core.Instrument;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detalhes das posições abertas para a página Operar (pedido do operador em 15/10/2026): de onde veio (roleta por
 * tendência ou pullback, ou manual), qual previsão, quanto falta para a saída por tempo, stop/alvo em preço e em US$,
 * de onde veio o stop (ATR ou US$), trailing e prorrogação com lucro.
 */
@Service
public class PositionDetails {

    private final TradeRepository repo;
    private final OrderService orders;

    public PositionDetails(TradeRepository repo, OrderService orders) {
        this.repo = repo;
        this.orders = orders;
    }

    /**
     * @param origin        manual | roleta | sinal automático | momentum | cassino | alta frequência | externa (aberta
     *                      fora deste sistema, sem ordem OPEN) — 26/10/2026: antes só roleta × manual
     * @param originNote    detalhe curto da entrada (vez/tentativa do Cassino, hora e fluxo do Momentum…)
     * @param strategy      trend | pullback (só roleta)
     * @param pSide         probabilidade do lado escolhido (roleta) ou do lado da posição (manual)
     * @param closeAfterUtc saída por tempo original
     * @param timeLockUntil fim da prorrogação com lucro (null = não prorrogada)
     * @param stopMode      ATR | USD (o stop de entrada saiu do ATR ou do stop em US$)
     * @param stopAtr       stop de entrada em ATR (distância ÷ ATR)
     * @param targetR       alvo de entrada em múltiplos do stop
     * @param slUsd         resultado se o stop de agora bater (positivo = lucro garantido)
     * @param tpUsd         resultado se o alvo de agora bater
     * @param trailMoves    quantas vezes o trailing moveu o stop
     * @param timeLockPeakUsd maior lucro da prorrogação (base do stop/alvo de agora)
     * @param timeLockMoves quantas vezes stop/alvo andaram por nova máxima
     */
    public record Detail(long ticket, String origin, String strategy, String model, Integer horizonMin, Double pSide,
                         Double pctB, Instant openedAt, Instant closeAfterUtc, Instant timeLockUntil,
                         String timeLockStatus, String timeLockMessage, String stopMode, Double stopAtr, Double targetR,
                         Double riskUsdPlanned, Double slUsd, Double tpUsd, long trailMoves, String trailLast,
                         Instant timeLockStartedAt, Double timeLockPeakUsd, Instant timeLockPeakAt, Integer timeLockMoves,
                         String originNote) {
    }

    public Map<Long, Detail> forOpenPositions() {
        List<TradeRepository.Position> positions = repo.positions();
        Map<Long, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> r : repo.openOrderInfo(positions.stream().map(TradeRepository.Position::ticket).toList())) {
            rows.put(((Number) r.get("ticket")).longValue(), r);
        }
        Map<Long, Detail> out = new LinkedHashMap<>();
        for (TradeRepository.Position p : positions) {
            Map<String, Object> r = rows.get(p.ticket());
            Double slUsd = null, tpUsd = null;
            Instrument inst = orders.instrument(p.symbol());
            if (inst != null) {
                double perPrice = inst.lossPerLot(1.0) * p.volume();
                int sign = "BUY".equals(p.side()) ? 1 : -1;
                if (p.sl() != null && p.sl() > 0) slUsd = sign * (p.sl() - p.openPrice()) * perPrice;
                if (p.tp() != null && p.tp() > 0) tpUsd = sign * (p.tp() - p.openPrice()) * perPrice;
            }
            if (r == null) {
                out.put(p.ticket(), new Detail(p.ticket(), "externa", null, null, null, null, null, p.openTime(), null,
                        null, null, null, null, null, null, null, slUsd, tpUsd, 0, null, null, null, null, null, null));
                continue;
            }
            String strategy = (String) r.get("strategy");
            Double sl = dbl(r, "sl_distance"), tp = dbl(r, "tp_distance"), atr = dbl(r, "atr");
            String checks = (String) r.get("checks");
            boolean usd = checks != null && checks.contains("Stop em US$");
            Double pSide = dbl(r, "p_side");
            if (pSide == null && r.get("p_up") != null) pSide = dbl(r, "BUY".equals(p.side()) ? "p_up" : "p_down");
            String origin = r.get("origin") != null ? (String) r.get("origin") : strategy != null ? "roleta" : "manual";
            out.put(p.ticket(), new Detail(p.ticket(), origin, strategy,
                    (String) r.get("model"), r.get("horizon_min") == null ? null : ((Number) r.get("horizon_min")).intValue(),
                    pSide, dbl(r, "pct_b"), instant(r, "created_at"), instant(r, "close_after_utc"),
                    instant(r, "time_lock_until"), (String) r.get("time_lock_status"), (String) r.get("time_lock_message"),
                    usd ? "USD" : "ATR", sl != null && atr != null && atr > 0 ? sl / atr : null,
                    sl != null && tp != null && sl > 0 ? tp / sl : null, dbl(r, "risk_usd"), slUsd, tpUsd,
                    r.get("trail_moves") == null ? 0 : ((Number) r.get("trail_moves")).longValue(), (String) r.get("trail_last"),
                    instant(r, "time_lock_started_at"), dbl(r, "time_lock_peak_usd"), instant(r, "time_lock_peak_at"),
                    r.get("time_lock_moves") == null ? null : ((Number) r.get("time_lock_moves")).intValue(),
                    (String) r.get("origin_note")));
        }
        return out;
    }

    private static Double dbl(Map<String, Object> r, String k) {
        Object v = r.get(k);
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static Instant instant(Map<String, Object> r, String k) {
        Object v = r.get(k);
        if (v instanceof Timestamp t) return t.toInstant();
        if (v instanceof java.time.OffsetDateTime o) return o.toInstant();
        return v instanceof Instant i ? i : null;
    }
}
