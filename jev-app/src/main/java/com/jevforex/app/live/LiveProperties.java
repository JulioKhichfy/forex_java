package com.jevforex.app.live;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Bloco {@code live} do application.yml: previsões ao vivo dos modelos de produção (passo 6a).
 *
 * @param enabled            agenda as previsões no modo servidor (nos mesmos momentos do treino)
 * @param lookbackDays       dias de M1 lidos para as features. O regime compara o ATR com a mediana das últimas
 *                           1.920 barras M15: ~20 dias num mercado de 24 h, mas ~105 dias corridos numa ação
 *                           (6,5 h por dia). Janela curta demais = regime vazio = o símbolo fica sem previsão.
 * @param lookbackDaysByMarket a mesma janela por mercado (ex.: stocks: 120); ausente = lookbackDays
 * @param cron               quando conferir se é momento de decisão (padrão: segundo 20 de cada minuto, para a
 *                           barra que fecha no momento chegar do MT5)
 * @param firstMarkets       mercados previstos antes dos outros em cada rodada (o sinal de 15 min do ouro não pode
 *                           esperar forex, índices e ações)
 * @param catchUpMinutes     momentos de decisão perdidos (rodada anterior ainda calculando) são previstos depois,
 *                           com o instante original, se tiverem até esta idade
 * @param signalRules        regra de sinal por mercado; mercado sem regra usa o gate 4 (experiment.decision)
 * @param normalizeCron      normalize automático (reconstrói o silver e encurta o bronze recente que cada previsão
 *                           lê); "-" = desligado
 */
@ConfigurationProperties(prefix = "live")
public record LiveProperties(Boolean enabled, Integer lookbackDays, Map<String, Integer> lookbackDaysByMarket,
                             String cron, List<String> firstMarkets, Integer catchUpMinutes,
                             Map<String, SignalRule> signalRules, String normalizeCron) {

    /**
     * Regra de sinal validada fora da amostra para um mercado (ex.: ouro, modelo B a 15 min). Vale só para as
     * previsões desse modelo e horizonte; as demais do mercado seguem o gate 4.
     *
     * @param model      modelo (A, E, B…)
     * @param horizon    horizonte em minutos
     * @param minProb    P mínima da direção (alta ou queda)
     * @param minMargin  diferença mínima para a direção oposta
     * @param eventsOnly só nos momentos de evento (event + atraso); na hora cheia = NO_TRADE
     * @param note       de onde veio a regra (walk-forward, cofre), para o dashboard
     */
    public record SignalRule(String model, Integer horizon, Double minProb, Double minMargin, Boolean eventsOnly,
                             String note) {
        public SignalRule {
            if (model == null || horizon == null || minProb == null || minMargin == null) {
                throw new IllegalArgumentException("live.signal-rules: model, horizon, min-prob e min-margin são obrigatórios");
            }
            if (eventsOnly == null) eventsOnly = false;
        }
    }

    public LiveProperties {
        if (enabled == null) enabled = false;
        if (lookbackDays == null) lookbackDays = 40;
        if (cron == null || cron.isBlank()) cron = "20 * * * * *";
        if (lookbackDays < 30) throw new IllegalArgumentException("live.lookback-days deve ser >= 30 (regime: 20 dias úteis)");
        lookbackDaysByMarket = lookbackDaysByMarket == null ? Map.of() : Map.copyOf(lookbackDaysByMarket);
        firstMarkets = firstMarkets == null ? List.of() : List.copyOf(firstMarkets);
        if (catchUpMinutes == null) catchUpMinutes = 10;
        signalRules = signalRules == null ? Map.of() : Map.copyOf(signalRules);
        if (normalizeCron == null || normalizeCron.isBlank()) normalizeCron = "-";
    }

    public int lookbackFor(String market) {
        return lookbackDaysByMarket.getOrDefault(market, lookbackDays);
    }

    /** Os mercados na ordem da rodada: primeiro os de {@code firstMarkets}, depois os demais na ordem dada. */
    public List<String> ordered(List<String> markets) {
        List<String> out = new java.util.ArrayList<>();
        for (String m : firstMarkets) if (markets.contains(m)) out.add(m);
        for (String m : markets) if (!out.contains(m)) out.add(m);
        return out;
    }
}
