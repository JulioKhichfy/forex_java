package com.jevforex.app.trade;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Compra Pizza (28/10/2026): bloco {@code pizza} do application.yml.
 *
 * @param markets         onde o jogo procura operações
 * @param maxPositions    posições da partida abertas ao mesmo tempo
 * @param strongMinProb   previsão "forte" sem sinal da regra: P mínima do lado
 * @param strongMinMargin … e diferença mínima para o lado oposto
 * @param maxAge15Minutes idade máxima da previsão de 15 min (depois disso o horizonte já passou quase todo)
 * @param maxAge60Minutes idade máxima da previsão de 60 min
 */
@ConfigurationProperties(prefix = "pizza")
public record PizzaProperties(List<String> markets, Integer maxPositions, Double strongMinProb, Double strongMinMargin,
                              Integer maxAge15Minutes, Integer maxAge60Minutes) {

    public PizzaProperties {
        markets = markets == null ? List.of("metals", "indices", "crypto", "fx", "energy") : List.copyOf(markets);
        if (maxPositions == null || maxPositions < 1) maxPositions = 5;
        if (strongMinProb == null) strongMinProb = 0.45;
        if (strongMinMargin == null) strongMinMargin = 0.15;
        if (maxAge15Minutes == null) maxAge15Minutes = 10;
        if (maxAge60Minutes == null) maxAge60Minutes = 30;
    }
}
