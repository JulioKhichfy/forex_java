package com.jevforex.app.cli;

import java.util.Set;

/** Comandos de linha de comando disponíveis. */
public final class Commands {

    public static final String HELP = "help";
    public static final String PING = "ping";
    public static final String RISK = "risk";
    public static final String ASK = "ask";
    public static final String COLLECT_ONCE = "collect-once";

    private static final Set<String> ALL = Set.of(HELP, PING, RISK, ASK, COLLECT_ONCE);
    private static final Set<String> NEED_DB = Set.of(ASK, COLLECT_ONCE);

    private Commands() {
    }

    public static boolean isCommand(String arg) {
        return ALL.contains(arg);
    }

    public static boolean needsDatabase(String arg) {
        return NEED_DB.contains(arg);
    }

    public static String usage() {
        return """
                Uso: java -jar jev-app/target/jev-app.jar <comando> [opções]

                  ping                          testa a chave do Jev (GET /v1/models, não gasta créditos)
                  risk [--balance=20]           mostra, por símbolo, o risco do lote mínimo e se o sistema operaria
                  collect-once                  roda uma coleta dos feeds de bancos centrais e sai
                  ask --qset=cb-text-v1 <fonte> [opções]
                       fontes:  --text="..." | --file=caminho.txt | --latest | --doc=<id>
                       opções:  --issuer="Federal Reserve" --currency=USD --previous="resumo anterior"
                                --model=jev-1.13.0 --raw (mostra o JSON completo)
                  ask --qset=headline-v1 --text="manchete" [--recent="m1|m2|m3"]

                Sem comando: modo servidor (coleta agendada + API em http://localhost:8080/api/status)
                """;
    }
}
