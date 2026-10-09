package com.jevforex.app.cli;

import java.util.Set;

/** Comandos de linha de comando disponíveis. */
public final class Commands {

    public static final String HELP = "help";
    public static final String PING = "ping";
    public static final String RISK = "risk";
    public static final String ASK = "ask";
    public static final String COLLECT_ONCE = "collect-once";
    public static final String IMPORT_MT5_ONCE = "import-mt5-once";
    public static final String MT5_STATUS = "mt5-status";
    public static final String NORMALIZE = "normalize";

    private static final Set<String> ALL = Set.of(HELP, PING, RISK, ASK, COLLECT_ONCE, IMPORT_MT5_ONCE, MT5_STATUS,
            NORMALIZE);
    private static final Set<String> NEED_DB = Set.of(ASK, COLLECT_ONCE, IMPORT_MT5_ONCE, MT5_STATUS);

    private Commands() {
    }

    public static boolean isCommand(String arg) {
        return ALL.contains(arg);
    }

    /** risk só precisa do banco com --mt5 (lê as especificações reais da corretora). */
    public static boolean needsDatabase(String[] args) {
        if (args.length == 0) return false;
        if (RISK.equals(args[0])) {
            for (String a : args) {
                if (a.equals("--mt5") || a.startsWith("--mt5=")) return true;
            }
            return false;
        }
        return NEED_DB.contains(args[0]);
    }

    public static String usage() {
        return """
                Uso: java -jar jev-app/target/jev-app.jar <comando> [opções]

                  ping                          testa a chave do Jev (GET /v1/models, não gasta créditos)
                  risk [--balance=20] [--mt5]   mostra, por símbolo, o risco do lote mínimo e se o sistema operaria
                                                (--mt5: usa tick value e lotes reais da corretora, vindos do MT5)
                  collect-once                  roda uma coleta dos feeds de bancos centrais e sai
                  import-mt5-once               importa os arquivos do MT5 (Common\\Files\\jev\\inbox) e sai
                  mt5-status                    saúde do MT5: heartbeat do EA, candles, calendário, próximos eventos
                  normalize [--only=candles|calendar]
                                                reconstrói o silver (Parquet, UTC) a partir do bronze e mostra as
                                                checagens de qualidade (não precisa do Postgres)
                  ask --qset=cb-text-v1 <fonte> [opções]
                       fontes:  --text="..." | --file=caminho.txt | --latest | --doc=<id>
                       opções:  --issuer="Federal Reserve" --currency=USD --previous="resumo anterior"
                                --model=jev-1.13.0 --raw (mostra o JSON completo)
                  ask --qset=headline-v1 --text="manchete" [--recent="m1|m2|m3"]

                Sem comando: modo servidor (coleta agendada + API em http://localhost:8080/api/status)
                """;
    }
}
