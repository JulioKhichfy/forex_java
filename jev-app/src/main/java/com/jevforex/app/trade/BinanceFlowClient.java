package com.jevforex.app.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Candles de 1 min dos futuros perpétuos da Binance (API pública, sem conta nem chave: GET /fapi/v1/klines) para o filtro
 * de fluxo do Momentum BTC — os mesmos campos do histórico de data.binance.vision usado no estudo (volume e compra
 * agressiva de cada minuto). Só leitura de dado de mercado; nada da conta do operador vai para lá.
 */
@Component
public class BinanceFlowClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;

    public BinanceFlowClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Volume e compra agressiva de cada minuto já fechado em [from, to). */
    public record Minutes(double[] volume, double[] takerBuy) {
    }

    /** Os últimos minutos FECHADOS: início (ms), abertura, fechamento, volume e compra agressiva. */
    public record Recent(long[] openTime, double[] open, double[] close, double[] volume, double[] takerBuy) {
    }

    /** Os últimos {@code limit} minutos (até 1.500 ≈ 25 h), só os já fechados. */
    public Recent recent(String baseUrl, String symbol, int limit) throws Exception {
        URI uri = URI.create(String.format("%s/fapi/v1/klines?symbol=%s&interval=1m&limit=%d", baseUrl, symbol, limit));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "jev-forex").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("Binance respondeu " + r.statusCode());
        JsonNode arr = mapper.readTree(r.body());
        long now = System.currentTimeMillis();
        int n = 0, size = arr.size();
        long[] ot = new long[size];
        double[] o = new double[size], c = new double[size], v = new double[size], tb = new double[size];
        for (JsonNode k : arr) {
            if (k.get(6).asLong() >= now) continue;   // o minuto em formação fica de fora
            ot[n] = k.get(0).asLong();
            o[n] = k.get(1).asDouble();
            c[n] = k.get(4).asDouble();
            v[n] = k.get(5).asDouble();
            tb[n] = k.get(9).asDouble();
            n++;
        }
        return new Recent(java.util.Arrays.copyOf(ot, n), java.util.Arrays.copyOf(o, n), java.util.Arrays.copyOf(c, n),
                java.util.Arrays.copyOf(v, n), java.util.Arrays.copyOf(tb, n));
    }

    public Minutes minutes(String baseUrl, String symbol, Instant from, Instant to) throws Exception {
        URI uri = URI.create(String.format("%s/fapi/v1/klines?symbol=%s&interval=1m&startTime=%d&endTime=%d&limit=100",
                baseUrl, symbol, from.toEpochMilli(), to.toEpochMilli() - 1));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "jev-forex").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("Binance respondeu " + r.statusCode());
        JsonNode arr = mapper.readTree(r.body());
        int n = 0;
        double[] v = new double[arr.size()], tb = new double[arr.size()];
        for (JsonNode k : arr) {
            long open = k.get(0).asLong(), close = k.get(6).asLong();
            if (open < from.toEpochMilli() || close >= to.toEpochMilli()) continue;   // só minutos fechados da hora
            v[n] = k.get(5).asDouble();      // volume (BTC)
            tb[n] = k.get(9).asDouble();     // compra agressiva (BTC)
            n++;
        }
        return new Minutes(java.util.Arrays.copyOf(v, n), java.util.Arrays.copyOf(tb, n));
    }
}
