package com.jevforex.app.trade;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Símbolos extras do Cassino (23/10/2026, pedido do operador): exóticos/cruzados e "forex indicators" da Exness, prata e
 * Ethereum. Só o Cassino os mostra; ficam fora dos modelos (experiment.market-symbols).
 */
@ConfigurationProperties(prefix = "casino")
public record CasinoProperties(List<Group> groups) {

    public CasinoProperties {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /**
     * @param market   o mercado do símbolo na corretora (o mesmo que o JevCandleExporter grava na especificação)
     * @param optional a página só mostra se o operador marcar "incluir"
     */
    public record Group(String code, String label, String market, boolean optional, List<String> symbols) {

        public Group {
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
        }
    }
}
