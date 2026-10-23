package com.jevforex.app.live;

import com.jevforex.app.config.ExperimentProperties;
import com.jevforex.core.Market;
import com.jevforex.features.scan.ScanLoader;
import com.jevforex.features.scan.Scanner;
import com.jevforex.lake.LakeSql;
import com.jevforex.lake.LakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Radar de movimentos atípicos (página Radar): indicadores e alertas por símbolo a partir dos candles do lake.
 * Só leitura; o resultado fica 30 s em cache por mercado (a página atualiza a cada minuto) para não disputar CPU com
 * as previsões ao vivo.
 */
@Service
public class ScannerService {

    private static final Logger log = LoggerFactory.getLogger(ScannerService.class);
    private static final Duration TTL = Duration.ofSeconds(30);
    /** D1: Bollinger(20) e a amplitude média de 20 pregões pedem ~30 dias corridos; 45 dá folga para o RSI. */
    private static final int LOOKBACK_DAYS = 45;
    private static final List<String> STRENGTH_WINDOWS = List.of("1h", "4h", "24h");

    private final LakeStorage lake;
    private final ExperimentProperties experimentProps;
    private final Map<String, Snapshot> cache = new HashMap<>();
    /** As barras lidas na última varredura de cada mercado (backtest do Cassino). */
    private final Map<String, Map<String, Scanner.Input>> inputs = new HashMap<>();
    private LakeSql sql;          // aberta entre as leituras: o bronze recente é lido aos poucos (ScanLoader)
    private ScanLoader loader;

    public ScannerService(LakeStorage lake, ExperimentProperties experimentProps) {
        this.lake = lake;
        this.experimentProps = experimentProps;
    }

    /**
     * @param lastBarUtc  última barra M1 do mercado (velha = mercado fechado ou MT5 parado)
     * @param recentFiles arquivos do bronze lidos além do silver
     * @param strength    força das moedas por janela (só forex)
     */
    public record Snapshot(String market, Instant generatedAt, long tookMs, int recentFiles, Instant lastBarUtc,
                           List<Scanner.Result> symbols, Map<String, Map<String, Double>> strength) {
    }

    public synchronized Snapshot scan(String market) {
        String m = Market.fromCode(market).code();
        Snapshot cached = cache.get(m);
        if (cached != null && cached.generatedAt().plus(TTL).isAfter(Instant.now())) return cached;
        long t0 = System.nanoTime();
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        List<Scanner.Result> results = new ArrayList<>();
        int recent;
        try {
            if (loader == null) {
                sql = LakeSql.open(lake.root().resolve("tmp").resolve("duckdb-scan"), "1GB");
                loader = new ScanLoader(sql, lake.root());
            }
            ScanLoader.Loaded l = loader.load(m, experimentProps.symbolsOf(m), at, LOOKBACK_DAYS);
            recent = l.recentFiles();
            log.debug("Radar {} fases (ms): {}", m, l.ms());
            for (Scanner.Input in : l.inputs().values()) results.add(Scanner.scan(in));
            inputs.put(m, l.inputs());
        } catch (RuntimeException e) {
            close();   // a próxima leitura começa do zero (ex.: o normalize trocou o silver no meio da leitura)
            throw e;
        }
        Instant last = results.stream().map(Scanner.Result::lastBarUtc).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        Map<String, Map<String, Double>> strength = "fx".equals(m)
                ? Scanner.currencyStrength(results, STRENGTH_WINDOWS) : Map.of();
        Snapshot s = new Snapshot(m, Instant.now(), (System.nanoTime() - t0) / 1_000_000, recent, last, results,
                strength);
        cache.put(m, s);
        log.debug("Radar {}: {} símbolos, {} alertas, {} ms", m, results.size(),
                results.stream().mapToInt(r -> r.alerts().size()).sum(), s.tookMs());
        return s;
    }

    /**
     * As barras de um símbolo (M1 das últimas ~26 h e o spread de agora). Do radar: a varredura de até 30 s atrás. Fora
     * do radar (símbolos extras do Cassino): lido só ele, 2 dias de M1.
     */
    public synchronized Scanner.Input input(String market, String symbol) {
        String m = Market.fromCode(market).code();
        List<String> universe = experimentProps.symbolsOf(m);
        if (universe == null || universe.contains(symbol)) {
            scan(m);
            Scanner.Input in = inputs.getOrDefault(m, Map.of()).get(symbol);
            if (in != null || universe != null) return in;
        }
        try {
            if (loader == null) {
                sql = LakeSql.open(lake.root().resolve("tmp").resolve("duckdb-scan"), "1GB");
                loader = new ScanLoader(sql, lake.root());
            }
            return loader.load(m, List.of(symbol), LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS), 2)
                    .inputs().get(symbol);
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (sql != null) sql.close();
        sql = null;
        loader = null;
    }
}
