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
    public static final String FEATURES = "features";
    public static final String TRAIN = "train";
    public static final String BACKFILL_BIS = "backfill-bis";
    public static final String BACKFILL_ARCHIVES = "backfill-archives";
    public static final String JEV_SCORE = "jev-score";
    public static final String JEV_SIGNALS = "jev-signals";
    public static final String TRAIN_CHAMPION = "train-champion";
    public static final String PROMOTE = "promote";
    public static final String PREDICT_NOW = "predict-now";
    public static final String RESOLVE_PREDICTIONS = "resolve-predictions";

    private static final Set<String> ALL = Set.of(HELP, PING, RISK, ASK, COLLECT_ONCE, IMPORT_MT5_ONCE, MT5_STATUS,
            NORMALIZE, FEATURES, TRAIN, BACKFILL_BIS, BACKFILL_ARCHIVES, JEV_SCORE, JEV_SIGNALS, TRAIN_CHAMPION, PROMOTE,
            PREDICT_NOW, RESOLVE_PREDICTIONS);
    private static final Set<String> NEED_DB = Set.of(ASK, COLLECT_ONCE, IMPORT_MT5_ONCE, MT5_STATUS, BACKFILL_ARCHIVES,
            JEV_SCORE, JEV_SIGNALS, PREDICT_NOW, RESOLVE_PREDICTIONS);

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
                  collect-once                  roda uma coleta dos feeds de bancos centrais (e dos PDFs anexos) e sai
                  import-mt5-once               importa os arquivos do MT5 (Common\\Files\\jev\\inbox) e sai
                  backfill-bis [--from=2021]    baixa o acervo de discursos do BIS (zip por ano) para o bronze
                  backfill-archives [--banks=fed,ecb,boe,boj,boc] [--from=2021]
                                                comunicados de decisão e atas dos arquivos dos bancos centrais
                                                (horário de divulgação pelo calendário; rode normalize antes)
                  mt5-status                    saúde do MT5: heartbeat do EA, candles, calendário, próximos eventos
                  normalize [--only=candles|calendar|documents]
                                                reconstrói o silver (Parquet, UTC) a partir do bronze e mostra as
                                                checagens de qualidade (não precisa do Postgres)
                  features                      silver → gold: momentos de decisão, features (fset do yml) e labels
                                                de 15/60 min (não precisa do Postgres; rode normalize antes)
                  train [--horizon=60]          experimento A × B com walk-forward, embargo e cofre; grava
                                                reports\\walkforward\\<run>\\report.html (rode features antes)
                  train --open-lockbox          ABRE O COFRE (uma única vez): avalia os modelos nos meses reservados
                  train-champion                treina os modelos de produção (A, E, B) com os últimos 24 meses;
                                                a primeira versão entra em produção, as seguintes esperam promote
                  promote --version=<versão>    coloca uma versão de models/market=fx em produção
                  predict-now [--at=2026-10-09T15:00]
                                                previsão ao vivo agora (ou num instante UTC) e grava no Postgres
                  resolve-predictions           placar: dá o resultado às previsões com o horizonte vencido
                  ask --qset=cb-text-v1 <fonte> [opções]
                       fontes:  --text="..." | --file=caminho.txt | --latest | --doc=<id>
                       opções:  --issuer="Federal Reserve" --currency=USD --previous="resumo anterior"
                                --model=jev-1.13.0 --raw (mostra o JSON completo)
                  ask --qset=headline-v1 --text="manchete" [--recent="m1|m2|m3"]
                  jev-score [--qset=cb-text-v1] [--since=2021-01-01] [--limit=N]
                                                PLANO: trechos do silver a avaliar, já no cache e custo estimado
                       --run [--max-usd=2] [--concurrency=4]   chama o Jev (para no teto ou em erro de chave/saldo)
                  jev-signals [--qset=cb-text-v1]
                                                respostas → gold/jev_answers (trecho) e gold/currency_signals (documento)

                Sem comando: modo servidor (coleta agendada + API em http://localhost:8080/api/status)
                """;
    }
}
