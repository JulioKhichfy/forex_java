package com.jevforex.app.live;

import com.jevforex.app.trade.BinanceFlowClient;
import com.jevforex.features.scan.WhaleRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Baleias no bitcoin (pedido do operador em 25/10/2026): a cada 20 s, os últimos ~25 h de minutos dos futuros da Binance
 * (API pública); cada minuto fechado que passa na {@link WhaleRule} vira um registro em whale_event, que o Radar mostra com
 * alerta sonoro. Só descritivo: não envia ordem nenhuma.
 */
@Service
@Profile("!cli")
public class WhaleService {

    private static final Logger log = LoggerFactory.getLogger(WhaleService.class);
    /** Minutos recentes conferidos a cada volta (cobre voltas perdidas sem reprocessar o dia inteiro). */
    private static final int RECHECK = 10;

    private final BinanceFlowClient binance;
    private final WhaleProperties props;
    private final ObjectProvider<JdbcClient> jdbc;
    private Instant lastError;

    public WhaleService(BinanceFlowClient binance, WhaleProperties props, ObjectProvider<JdbcClient> jdbc) {
        this.binance = binance;
        this.props = props;
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelayString = "PT60S", fixedDelayString = "PT20S")
    public void tick() {
        if (!props.enabled()) return;
        BinanceFlowClient.Recent r;
        try {
            r = binance.recent(props.url(), props.binanceSymbol(), Math.min(1500, props.lookbackMinutes() + RECHECK + 1));
        } catch (Exception e) {
            // um aviso por hora no máximo: a Binance fora não pode encher o log
            if (lastError == null || lastError.isBefore(Instant.now().minus(Duration.ofHours(1)))) {
                log.warn("Baleias: sem os minutos da Binance ({}); tento de novo a cada 20 s", e.toString());
                lastError = Instant.now();
            }
            return;
        }
        int n = r.volume().length;
        for (int i = Math.max(props.lookbackMinutes(), n - RECHECK); i < n; i++) {
            WhaleRule.Whale w = WhaleRule.evaluate(r.volume(), r.takerBuy(), r.open(), r.close(), i,
                    props.lookbackMinutes(), props.multiple(), props.minImbalance());
            if (w == null) continue;
            boolean continues = i > 0 && WhaleRule.evaluate(r.volume(), r.takerBuy(), r.open(), r.close(), i - 1,
                    props.lookbackMinutes(), props.multiple(), props.minImbalance()) != null;
            int added = jdbc.getObject().sql("""
                            INSERT INTO whale_event (source, symbol, minute_utc, side, volume, notional_usd, multiple, imbalance,
                                                     move_bps, continues)
                            VALUES (:src, :sym, :m, :side, :v, :usd, :mult, :imb, :mv, :cont)
                            ON CONFLICT (source, minute_utc) DO NOTHING
                            """)
                    .param("src", "binance:" + props.binanceSymbol()).param("sym", props.symbol())
                    .param("m", Timestamp.from(Instant.ofEpochMilli(r.openTime()[i])), Types.TIMESTAMP)
                    .param("side", w.side()).param("v", w.volume()).param("usd", w.volume() * r.close()[i])
                    .param("mult", w.multiple()).param("imb", w.imbalance()).param("mv", w.moveBps()).param("cont", continues)
                    .update();
            if (added > 0) {
                log.info("Baleia {} no {}: {} {} ({}× o normal, US$ {} mi) às {}", continues ? "(continua)" : "",
                        props.binanceSymbol(), "BUY".equals(w.side()) ? "compra" : "venda",
                        String.format(java.util.Locale.ROOT, "%.0f", w.volume()),
                        String.format(java.util.Locale.ROOT, "%.0f", w.multiple()),
                        String.format(java.util.Locale.ROOT, "%.1f", w.volume() * r.close()[i] / 1e6),
                        Instant.ofEpochMilli(r.openTime()[i]));
            }
        }
    }

    /** As baleias das últimas {@code hours} horas e a configuração (página Radar). */
    public Map<String, Object> recent(int hours) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", props);
        List<Map<String, Object>> rows = jdbc.getObject().sql("""
                        SELECT id, symbol, minute_utc, side, volume, notional_usd, multiple, imbalance, move_bps, continues
                          FROM whale_event WHERE minute_utc >= :from ORDER BY minute_utc DESC LIMIT 200
                        """).param("from", Timestamp.from(Instant.now().minus(Duration.ofHours(Math.min(hours, 168)))))
                .query().listOfRows();
        out.put("events", rows);
        return out;
    }
}
