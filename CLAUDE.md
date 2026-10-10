# Jev Forex — contexto para o Claude Code

Sistema de trading orientado a eventos para as 7 majors (EURUSD, GBPUSD, USDJPY, USDCHF, AUDUSD,
USDCAD, NZDUSD), com MetaTrader 5 e o modelo Jev (TypeSafe AI). Extensões futuras: XAUUSD e BTCUSD,
como mercados separados. O documento mestre está em `docs/Jev_Forex_Documento_Mestre_v1.1.pdf`.

## Princípios (não negociáveis)
- **O Jev interpreta, o código decide.** O Jev nunca decide compra/venda; só transforma texto em probabilidades.
- **Point-in-time.** Todo dado tem `first_seen_at`; treino e backtest só usam o que já era conhecido no instante.
- **Bronze imutável.** O bruto no lake nunca é editado; correções = reprocessar.
- **Falhar fechado.** Dado velho, spread alto, Jev fora, dúvida → NO_TRADE.
- **Risco por moeda e global.** Limites por mercado + limites globais da conta (exposição a USD somada).
- Tudo em **UTC** internamente.

## Convenções
- Java 21, Spring Boot 3.3, Maven multi-módulo. Pacote base `com.jevforex`.
- **Comentários, logs e mensagens ao usuário em português.**
- **Perguntas ao Jev em inglês.** Ficam em `jev-typesafe/src/main/resources/question-sets/*.json`.
  A tradução vai em campos `_pt` (removidos antes do envio). Mudou o texto de uma pergunta → **nova versão**
  do conjunto (ex.: `cb-text-v2`), nunca editar a versão em uso.
- Modelo do Jev fixado por versão (`jev.model`), nunca o alias `jev-latest` em treino/produção.
- Score do Jev é indexado a partir de 0: magnitude = `score / (níveis - 1)`.
- **Toda configuração de risco fica em `trading.risk` no `application.yml`.** Nada de números de risco no código.
- Todas as tabelas de negócio têm a coluna `market` (`fx` | `metals` | `crypto` | `indices` | `stocks`).
  Índices e ações: um instrumento numa moeda só (a de lucro na corretora, `currency_profit`); o calendário entra
  pela moeda dele, sem fator USD. Gold, modelos, relatórios e cofre separados por mercado (`--market=`).
- Migrações só via Flyway (`jev-app/src/main/resources/db/migration`), nunca alterar uma migração já aplicada.
- `jev-core` não depende de Spring nem de banco.
- Nome de símbolo **canônico** (EURUSD) em todo lugar; o sufixo da corretora (Exness: `m`) só na fronteira
  com o MT5 (`mt5.symbol-suffix`, `BrokerSymbols`). Símbolo sem o sufixo esperado é recusado.
- O lake fica **fora** do repositório (`LAKE_ROOT`, padrão `~/FOREX_JEV/jev-lake`): `jev-lake/` no repo é código.
- Contrato MT5 → Java: arquivos `<tipo>_<AAAAMMDDTHHMMSSZ>_<etiqueta>.csv` em `Common\Files\jev\inbox`,
  linha `#meta` + cabeçalho + linhas `;`. Colunas em `Mt5FileKind`. Mudou o formato → mude os dois lados
  e os testes de `Mt5ParsersTest`.
- **Execução (passo 6c):** o operador decide e clica no dashboard; o `OrderService` confere todas as travas
  (falhar fechado) e o EA executa com SL/TP no servidor. O EA só envia com TRÊS chaves: input `InpExecute=true`,
  `trading.mode` DEMO ou LIVE e o modo batendo com a conta (LIVE só em conta real). Stop/alvo vão como
  DISTÂNCIA; o EA aplica sobre o preço da execução. O EA só mexe em posições com o magic dele e nunca reenvia.
  Testar a mecânica em DEMO antes de apontar para a conta real (a troca é decisão do usuário).
- Features: mudou o significado de alguma feature ou do label → nova versão `features.fset` (v2), nunca
  reescrever a v1. O mesmo código calcula features no treino e ao vivo (não reimplementar "só para produção").
  Todo cálculo novo precisa respeitar point-in-time e ganhar teste no `FeatureBuilderTest`.
- **Custo do Jev:** chamada em escala só com `jev-score --run` depois de mostrar o plano ao usuário e ele
  aprovar; sempre com `--max-usd`. O cache por `request_sha` evita pagar duas vezes.
- **Cofre:** os últimos `experiment.lockbox-months` meses nunca entram em treino, ajuste ou comparação.
  Abrir o cofre é uma decisão do usuário (passo 5), uma única vez. **Aberto em 10/10/2026** (run
  20261010-133737, modelos A/E/B; trava em `reports/lockbox/OPENED.json`): não há outro teste limpo.

## Segurança
- **Nunca** escrever a chave do Jev em arquivo versionado, log ou teste. Ela vem de `TYPESAFE_API_KEY`
  ou `JEV_API_KEY_FILE` (o arquivo fica FORA do repositório).
- API e Postgres escutam apenas em 127.0.0.1.

## Módulos
| Módulo | Papel |
|---|---|
| jev-core | domínio puro: Market, Instrument, RiskSettings, PositionSizer (lote mínimo), BrokerSymbols |
| jev-typesafe | JevClient, QuestionSet/Registry, JevResponse, CbTextSignal |
| jev-lake | LakeStorage em disco local (bronze/silver/gold), LakeSql (DuckDB embarcado) |
| jev-normalize | bronze → silver em Parquet: Candle/Calendar/DocumentNormalizer, WeeklyOpenCheck, DocumentText (HTML/PDF), Chunker (sem Spring) |
| jev-features | silver → gold: FeatureBuilder (momentos de decisão, features A/B, labels 15/60 min), FeatureConfig (sem Spring) |
| jev-ml | walk-forward: Dataset, Outcomes (stop/alvo/tempo no M1), Gbm (Smile), Metrics, ExperimentRunner (modo cofre), ReportWriter, ModelStore (versões em produção) (sem Spring) |
| jev-collect | coletor RSS dos bancos centrais (+ PDFs anexos, HtmlLinks), RawDocumentRepository; `mt5/`: importador do inbox do MT5 |
| jev-app | Spring Boot: CLI, agendadores, Flyway, application.yml; `live/` previsões ao vivo; `trade/` ordens, travas e saída por tempo; API `/api/status`, `/api/predictions`, `/api/trade`, `/api/models`, `/api/news`, `/api/calendar`, `/api/ea/*` |
| dashboard | Angular 20 (Hoje, Previsões, Operar, Modelos, Saúde); `npx ng build` → servido pelo jev-app em http://127.0.0.1:8080/ |
| mql5 | Services JevCalendarExporter e JevCandleExporter, EA JevExecutor (executor com 3 chaves), `install.ps1` |

## Comandos
```
docker compose up -d                       # Postgres
mvn -q clean package                       # build + testes
java -jar jev-app/target/jev-app.jar ping | risk --balance=20 | collect-once
java -jar jev-app/target/jev-app.jar ask --qset=cb-text-v1 --latest
java -jar jev-app/target/jev-app.jar import-mt5-once | mt5-status | risk --balance=20 --mt5 | backfill-bis | backfill-archives
java -jar jev-app/target/jev-app.jar jev-score [--run --max-usd=1.5] | jev-signals   # sem --run: só o plano e o custo
java -jar jev-app/target/jev-app.jar normalize [--only=candles|calendar|documents]   # silver (sem Postgres)
java -jar jev-app/target/jev-app.jar features                    # gold: features + labels (fset do yml)
java -Xmx8g -jar jev-app/target/jev-app.jar train [--horizon=60]  # walk-forward → reports/walkforward/<run>
java -Xmx8g -jar jev-app/target/jev-app.jar train-champion       # modelos de produção (A, E, B) → models/market=fx/<versão>
java -jar jev-app/target/jev-app.jar promote --version=<v> | predict-now [--at=2026-10-09T15:00] | resolve-predictions
# índices/ações: features --market=indices · train --market=indices [--open-lockbox] · train-champion --market=indices
cd dashboard && npm install && npx ng build   # dashboard (dev: npx ng serve, com proxy para :8080)
mvn -q package -Djar.name=jev-app-dev      # jar de desenvolvimento com o servidor rodando (sem clean)
java -jar jev-app/target/jev-app.jar       # modo servidor
powershell -ExecutionPolicy Bypass -File mql5\install.ps1   # copia e compila os MQL5 no terminal da Exness
```
Maven precisa do JDK 21 no `JAVA_HOME` (a máquina também tem JDK 8).

## Contexto do operador
Banca pequena (~US$ 20) com lote mínimo 0,01: o sizing deve sempre mostrar o risco real do lote mínimo
e respeitar `min-lot-policy` (SKIP | ALLOW_UP_TO_CAP).

## Roteiro (status)
- [x] Passo 1 — fundação: build, Jev (ping/ask), risco configurável, coletor do Fed, lake, Postgres
- [ ] Passo 2 — MT5: exportadores de calendário e candles (MQL5 Services) + importadores Java + EA em shadow
      (rodando na Exness demo: calendário, candles ao vivo e EA em shadow. M1 no servidor só desde
      2021-10-27 para 5 dos 7 pares → período comum de treino começa aí)
- [ ] Passo 3 — silver: normalização, UTC, demais bancos centrais, ATR, labels, modelos A e B
      - [x] 3a silver de candles e calendário (DuckDB/Parquet, point-in-time, checagens; M1 confiável só de 2020 em diante)
      - [x] 3b demais bancos centrais + texto (PDF anexo) + trechos (RBA desligada: 403 para coletor identificado)
      - [x] 3c features (ATR, fator USD, surpresa) e labels 15/60 min (gold fset=v1; comando features)
      - [x] 3d walk-forward, modelos A e B, relatório (comando train; falta o teste de paridade treino × produção,
            que depende do cálculo ao vivo do passo 6)
- [ ] Passo 4 — Jev em escala: pontuação do histórico, sinais por moeda, features de texto
      - [x] 4a acervo de discursos do BIS 2021+ (backfill-bis; silver documents com available_utc)
      - [x] 4b comunicados de decisão e atas de Fed, BCE, BoE, BoJ, BoC (backfill-archives; horário do calendário + 30 s)
      - [x] 4c Jev em escala (jev-score/jev-signals; 12.915 trechos, ~US$ 0,99; gold/jev_answers e currency_signals)
      - [x] 4d grupo C (fset v2) e modelo C no train — resultado: C não supera B (60 min: 6/28 folds; 15 min: 14/28)
      - [x] 4e surpresa de tom (doc_kind no silver, gold/tone_surprises, fset v3, modelo D = B + tom) —
            resultado: D não supera B (60 min: 11/28; 15 min: 12/28); estudo de evento por divulgação sem relação
            com a reação imediata (corr +0,04, p 0,58, n 192)
      - [x] 4f cb-text-v2 (Jev compara com a divulgação anterior; 2.360 trechos, US$ 0,28) — estudo de evento:
            reação imediata zero; +2→+60 min corr −0,13 (p 0,05) que não se repete em 2024–26 → sem sinal robusto.
            Corrigido vazamento: coletiva do BCE só vale no início + 75 min (`ReleaseResolver.extraDelay`)
- [ ] Passo 5 — experimento A/B/C walk-forward e go/no-go
      - [x] 5a operações realistas (`Outcomes`: stop 1,5 ATR, alvo 1,5 × stop, saída por tempo, M1; stop/alvo em
            `trading.risk.exits`) + robustez +5 min e custos × 1,5 — resultado: gate 4 com 1 a 5 operações em 28
            folds, todas no stop; limiares frouxos (0,45/0,15) dão 68–122 operações com E[R] −0,13 a −0,26
      - [ ] 5b limiar do label 0,3/0,7, grade de hiperparâmetros no treino, calibração
      - [ ] 5c relatório go/no-go (critérios do cap. 11) e abertura do cofre (decisão do usuário, uma vez)
- [ ] Passo 6 — ferramenta de apoio à decisão (pedido do operador em 10/10/2026: dashboard, botão de ordem,
      conta real de US$ 50, índices e ações). Cofre aberto antes (5c antecipada).
      - [x] 6a modelo E (só eventos), cofre aberto (A/E/B), ModelStore + train-champion, features ao vivo com o
            mesmo código (teste de paridade), previsões ao vivo (Flyway V5, agendador nos momentos do treino)
      - [x] 6b dashboard Angular (Hoje, Previsões com placar ao vivo, Modelos com cofre e walk-forward, Saúde)
      - [ ] 6c ordens pelo dashboard (Flyway V6, OrderService com as travas, EA executor) — testar em DEMO
      - [x] 6d placar resolvido automaticamente (PredictionResolver), botão "aplicar treinamento" (JobService:
            comandos da CLI em processo separado) com champion × challenger no último mês (challenge.json) e
            promoção pelo dashboard; perfis de perda escolhidos no dashboard (trading.risk.loss-profiles)
      - [ ] 7 índices e ações — código pronto (exportador, mercados, features/modelos/ordens/dashboard por
            mercado); falta o histórico chegar do MT5 e o experimento de cada mercado
