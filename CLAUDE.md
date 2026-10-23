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
  A página Risco do dashboard edita esses valores e grava em `config/application.yml` (ao lado do jar, fora do
  git, lido pelo Spring por cima do yml do jar); vale na hora (`RiskSettingsHolder`) e fica auditado em
  `risk_change`. Quem decide ordens lê `RiskSettingsHolder.get()`, nunca um `RiskSettings` guardado.
- Todas as tabelas de negócio têm a coluna `market` (`fx` | `metals` | `crypto` | `energy` | `indices` | `stocks`).
  Índices e ações: um instrumento numa moeda só (a de lucro na corretora, `currency_profit`); o calendário entra
  pela moeda dele, sem fator USD. Gold, modelos, relatórios e cofre separados por mercado (`--market=`).
- Migrações só via Flyway (`jev-app/src/main/resources/db/migration`), nunca alterar uma migração já aplicada.
- `jev-core` não depende de Spring nem de banco.
- Nome de símbolo **canônico** (EURUSD) em todo lugar; o sufixo da corretora (Exness: `m`) só na fronteira
  com o MT5 (`mt5.symbol-suffix`, `BrokerSymbols`). Símbolo sem o sufixo esperado é recusado.
  Conta Standard Cent (21/10/2026, sufixo `c`, moeda USC): saldo, patrimônio, valor do tick e resultados chegam em
  centavos e são convertidos para US$ na fronteira (`AccountCurrency`); todo o resto do sistema fica em US$.
- O lake fica **fora** do repositório (`LAKE_ROOT`, padrão `~/FOREX_JEV/jev-lake`): `jev-lake/` no repo é código.
- Contrato MT5 → Java: arquivos `<tipo>_<AAAAMMDDTHHMMSSZ>_<etiqueta>.csv` em `Common\Files\jev\inbox`,
  linha `#meta` + cabeçalho + linhas `;`. Colunas em `Mt5FileKind`. Mudou o formato → mude os dois lados
  e os testes de `Mt5ParsersTest`.
- **Execução (passo 6c):** o operador decide e clica no dashboard; o `OrderService` confere todas as travas
  (falhar fechado) e o EA executa com SL/TP no servidor. O EA só envia com TRÊS chaves: input `InpExecute=true`,
  `trading.mode` DEMO ou LIVE e o modo batendo com a conta (LIVE só em conta real). Stop/alvo vão como
  DISTÂNCIA; o EA aplica sobre o preço da execução. O EA só mexe em posições com o magic dele e nunca reenvia.
  Testar a mecânica em DEMO antes de apontar para a conta real (a troca é decisão do usuário).
- **Notícias (passo 10a):** GDELT pelos arquivos GKG de 15 min (`data.gdeltproject.org`), nunca pela API de busca (bloqueia
  o IP por minutos com poucos pedidos). O bronze `gdelt_gkg` guarda só o filtrado (`news.gdelt`: sites + temas/palavras)
  em `.jsonl.gz`; o original é público e permanente (URL no meta) — mudar o filtro = reprocessar de lá. Point-in-time:
  lote + `available-lag-minutes` (o carimbo do lote vem ~7 min à frente da publicação: é conservador). EDGAR exige
  contato no User-Agent (`EDGAR_USER_AGENT`, nunca no git); o horário vale pelo `-index-headers` (Nova York → UTC).
- **Roleta** (`RouletteService`, decisão do operador em 14/10/2026): ordens automáticas por mercado que IGNORAM parte das
  travas de risco por ordem (`OrderService.ROULETTE_WAIVED`: teto/alvo de risco, USD, margem, spread, blackout); valem as
  técnicas, o BLOCK, a perda do dia/semana (que para todas) e, desde 19/10, posições, par livre, mercado habilitado e
  risco aberto. Não estender essa exceção a outras ordens sem o operador pedir.
  Roleta com a configuração do Laboratório (21/10, botão na página Laboratório): `RouletteService.Filters` — momento
  (ALL/EVENT/CONTROL/QUARTER), P e margem mínimas, pular LATERAL e stop/alvo próprios (`OrderService.Roulette.stopAtr/targetR`).
  Travas (revisão de 19/10): conta (3.1) e mercado (3.4) separados — "Posições abertas"/"Risco aberto total" contam a
  conta; "Posições no mercado"/"Risco aberto no mercado" só o mercado; "Risco-alvo do mercado" aplica risk-per-trade-pct
  e min-lot-policy; exposição a USD conta ouro/bitcoin/etc. pelo usd-weight. A página Risco mostra o que a roleta ignora.
  Estratégias: `trend` (lado mais provável, pula LATERAL), `both` (as duas: LATERAL → pullback, resto → trend; placar por perna) e `pullback` (teste do operador, 14/10: só em LATERAL,
  contra a banda de Bollinger M15 do radar; E[R] fora da amostra negativo, ~0 no ouro). Placar separado (Flyway V10).
- **Stop em US$ e trailing** (`trading.risk.money`, página "Stop em US$", 14/10): ligado, toda ordem nova sai com stop/alvo em
  dólares por cima dos de ATR (trava "Stop em US$ × spread"). Trailing: `TrailingService` pede MODIFY (Flyway V11,
  `sl_price`) ao EA 2.2; só a favor, zero a zero no início. O placar das previsões continua em ATR.
  Prorrogação com lucro (`money.time-lock-*`, botão na página Operar, 15/10): na saída por tempo, posição com lucro recebe
  MODIFY com stop E alvo (Flyway V12, `tp_price`; EA 2.4); dinâmica: a cada nova máxima (+`step-pct`) stop/alvo andam e a janela de
  `time-lock-minutes` renova; sem nova máxima, no teto (`time-lock-max-minutes`) ou se o EA recusar o início, fecha a mercado.
  CLOSE entregue (SENT) sem resposta do EA deixa de valer depois de vencido (um deixou posição aberta 2,5 h).
  Proteção de lucro por mercado (`money.protect-*`, 16/10): com lucro de `protect-trigger-usd` o stop vai garantir
  `protect-lock-usd` nos `protect-markets` (TrailingService; com o trailing ligado, vale o stop que protege mais).
  Sessão (16/10): o EA 2.5 manda `session_end` em cada cotação; a trava "Sessão até a saída por tempo" (também na roleta)
  recusa entrada cuja saída cai depois do fim da sessão − `orders.session-margin-minutes`. CLOSE/MODIFY recusado: espera 1 min
  ("market closed": 15 min) antes de pedir de novo.
  Edição manual de stop/alvo na página Operar (20/10, `OrderService.editStops`, Flyway V13 `modify_mode`=MANUAL, EA 2.6):
  o stop pode se afastar até o teto por operação; nunca sem stop; lado certo e distância mínima da corretora.
  Posições de fora do sistema (21/10, EA 2.8): o EA reporta todas, marcando as sem o magic dele; entram nas travas
  (`repo.positions()`), mas fechar/editar/trailing/saída por tempo/FLATTEN só nas do sistema (`repo.ownPositions()`).
- **Perfis prontos** (`risk.presets` no application.yml, 15/10): Ultra conservador → Ultra arrojado; um clique na página Risco
  substitui global (inclusive a perda do dia/semana), exits, mercados e campos de orders (`RiskEditor.withPreset`). A página
  Risco tem 3 partes: perfis, stop/alvo em US$ + trailing (`POST /api/risk/money`, só esse bloco) e ajuste fino.
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
| jev-normalize | bronze → silver em Parquet: Candle/Calendar/Document/NewsNormalizer, WeeklyOpenCheck, DocumentText (HTML/PDF), Chunker (sem Spring) |
| jev-features | silver → gold: FeatureBuilder (momentos de decisão, features A/B, labels 15/60 min), FeatureConfig; `scan/`: radar (ScanLoader incremental, Scanner: RSI, Bollinger, volume, ATR, alertas) (sem Spring) |
| jev-ml | walk-forward: Dataset, Outcomes (stop/alvo/tempo no M1), Gbm (Smile), Metrics, ExperimentRunner (modo cofre), ReportWriter, ModelStore (versões em produção), RiskLab (laboratório de configuração) (sem Spring) |
| jev-collect | coletor RSS dos bancos centrais (+ PDFs anexos, HtmlLinks), RawDocumentRepository; `mt5/`: importador do inbox do MT5; `news/`: GDELT (GKG) e SEC EDGAR |
| jev-app | Spring Boot: CLI, agendadores, Flyway, application.yml; `live/` previsões ao vivo; `trade/` ordens, travas e saída por tempo; API `/api/status`, `/api/predictions`, `/api/trade`, `/api/models`, `/api/news`, `/api/calendar`, `/api/scanner`, `/api/roulette`, `/api/ea/*` |
| dashboard | Angular 20 (Hoje, Radar, Previsões, Eventos, Universo, Operar, Roleta russa, Cassino, Stop em US$, Risco, Laboratório, Modelos, Saúde, Ajuda); `npx ng build` → servido pelo jev-app em http://127.0.0.1:8080/. Datas em UTC com Brasília no mouse (`[brt]`). Mudou uma tela → atualize `ajuda-conteudo.ts` |
| mql5 | Services JevCalendarExporter e JevCandleExporter, EA JevExecutor 2.8 (executor com 3 chaves; OPEN, CLOSE, MODIFY com stop e alvo, MODIFY MANUAL; fim da sessão por símbolo; reporta o fechamento de toda posição que abriu, mesmo manual; reporta também as posições de fora, marcadas), `install.ps1` |

## Comandos
```
docker compose up -d                       # Postgres
mvn -q clean package                       # build + testes
java -jar jev-app/target/jev-app.jar ping | risk --balance=20 | collect-once
java -jar jev-app/target/jev-app.jar ask --qset=cb-text-v1 --latest
java -jar jev-app/target/jev-app.jar import-mt5-once | mt5-status | risk --balance=20 --mt5 | backfill-bis | backfill-archives
java -jar jev-app/target/jev-app.jar backfill-news --source=gdelt [--from=2021-10-01] | backfill-news --source=edgar | collect-news-once
java -jar jev-app/target/jev-app.jar jev-score [--run --max-usd=1.5] | jev-signals   # sem --run: só o plano e o custo
java -jar jev-app/target/jev-app.jar normalize [--only=candles|calendar|documents]   # silver (sem Postgres)
java -jar jev-app/target/jev-app.jar features                    # gold: features + labels (fset do yml)
java -Xmx8g -jar jev-app/target/jev-app.jar train [--horizon=60]  # walk-forward → reports/walkforward/<run>
java -Xmx8g -jar jev-app/target/jev-app.jar train-champion       # modelos de produção (A, E, B) → models/market=fx/<versão>
java -jar jev-app/target/jev-app.jar promote --version=<v> | predict-now [--at=2026-10-09T15:00] | resolve-predictions
java -jar jev-app/target/jev-app.jar scan [--market=fx|all] [--json]   # radar no terminal (o mesmo de /api/scanner)
java -Xmx3g -jar jev-app/target/jev-app.jar lab --market=metals [--split=2025-07-01]   # laboratório → reports/lab/market=…/latest.json
# índices/ações: features --market=indices · train --market=indices [--open-lockbox] · train-champion --market=indices
cd dashboard && npm install && npx ng build   # dashboard (dev: npx ng serve, com proxy para :8080)
node tools/capturar-ajuda.mjs                 # capturas da aba Ajuda (servidor no ar; ficam fora do git: repo público)
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
      - [x] 6c ordens pelo dashboard (Flyway V6, OrderService com as travas, EA executor) — mecânica testada em DEMO
            em 12/10/2026: 0,01 EURUSD FILLED com SL/TP no servidor (recalculados sobre o preço real), posição no
            heartbeat, BLOCK recusa entrada e deixa fechar, fechamento FILLED. Modo DEMO/LIVE pela linha de comando
            (--trading.mode=demo), sem mexer no yml
      - [x] 6d placar resolvido automaticamente (PredictionResolver), botão "aplicar treinamento" (JobService:
            comandos da CLI em processo separado) com champion × challenger no último mês (challenge.json) e
            promoção pelo dashboard; perfis de perda (loss-profiles) removidos em 15/10: perda do dia/semana só em global
      - [ ] 7 índices e ações — código pronto (exportador, mercados, features/modelos/ordens/dashboard por
            mercado). Histórico M1 desde 2022 de 113 símbolos (62,5 mi barras) via `tools/mt5_history_backfill.py`
            (pacote MetaTrader5, mesmo contrato do exportador, origin=HISTORY). Walk-forward (cofres fechados):
            índices e ações separam "mexe × parado" (AUC até 0,73/0,76), mas nenhum limiar dá E[R] positivo;
            custo de 1 spread = 0,47 ATR (índices) e 0,9 ATR (ações).
            Decisão do operador (11/10): só 7 ações (AAPL, MSFT, NVDA, META, GOOGL, INTC, SPCX; `experiment.market-symbols`;
            o bronze guarda as demais) e índices; cofres abertos (indices/stocks) e modelos em produção nos 3 mercados
      - [x] 8 ouro (XAUUSD, metals) e bitcoin (BTCUSD, crypto): histórico M1 desde 2022, walk-forward, cofres abertos
            (13/10: ouro 15 min E[R] +0,14 no walk-forward e +0,20 no cofre, PF 1,96, poucas operações; bitcoin misto)
            e modelos em produção;
            placar das operações do operador (EA 2.1 reporta fechamentos, inclusive SL/TP no servidor → ea_deal, V8);
            notícias sem o acervo histórico e com avaliação do Jev pelo dashboard (plano → avaliar com teto US$ 0,10);
            janela ao vivo por mercado (ações: 130 dias — o regime pede 1.920 barras M15)
      - [x] 9a reversão às bandas de Bollinger (pedido do operador, 13/10): BB(20,2) M15, entrada perto da banda,
            alvo na média, stop 1,5 ATR, 8 h; com/sem RSI 30/70 e filtro lateral, 2022–23 × 2024–26, nos 5 mercados —
            resultado: E[R] negativo em todas as 20 combinações (melhor: ouro −0,02, bitcoin+RSI −0,01; stop leva
            52–64%; filtro lateral piora). Decisão: não virou sinal nem botão de ordem
      - [x] 9b ouro operacional (13/10): regra própria em `live.signal-rules.metals` (B, 15 min, P ≥ 0,45, margem
            ≥ 0,15, só em evento — na hora cheia o E[R] é ~0) com as saídas em que foi validada, por mercado
            (`trading.risk.markets.metals`: close-after-minutes 15, stop-atr 1,5, target-r 1,5; ordens, placar e
            treino usam `RiskSettings.exitsFor(mercado)`). A vantagem depende de entrar rápido (+5 min leva o E[R] a ~0): ouro previsto
            primeiro (`live.first-markets`), bronze recente lido uma vez por rodada, momentos perdidos recuperados
            (`live.catch-up-minutes`; o evento das 14:00 se perdia atrás da rodada da hora cheia), normalize
            automático às 21:30 UTC (`live.normalize-cron`; o bronze tem um arquivo por barra). Dashboard: aviso de
            sinal novo (destaque, som, notificação), calendário e notícias só em USD no ouro. Risco: 0,01 lote =
            stop de US$ 10–15; pior queda histórica da regra −3,9 R
      - [x] 9c página Radar (pedido do operador, 14/10): movimentos atípicos por símbolo em M5/M15/H1/H4/D1 — RSI 14
            (Wilder), Bollinger 20,2 (fora das bandas e compressão), volume de ticks × mesmo horário em 20 dias,
            movimento em ATR, barra grande, sequência, cruzamento MME 20/50, rompimento de ontem/20 dias, amplitude ×
            média, gap, spread × típico, força das moedas (forex), próximos eventos com contagem e notícias por moeda.
            Só descritivo (nenhum alerta validado como sinal). `ScanLoader` mantém a conexão e lê do bronze só os
            arquivos novos (6,5 mil arquivos/dia: 1ª leitura ~6 s, depois < 1 s); cache de 30 s por mercado.
            `Bronze.recentCsv` passou a usar `Files.find` (lista o bronze ~2 s mais rápido, também nas previsões)
      - [x] 9d Roleta russa (pedido do operador, 14/10): botão por mercado que, a cada previsão nova (modelo e horizonte
            15/60 escolhidos), envia uma ordem por símbolo no lado mais provável, pulando LATERAL, lote mínimo (ou o
            escolhido), saída por tempo no horizonte; liga até "Parar roleta russa" (estado em app_setting, Flyway V9
            roulette_entry). DEMO e LIVE (LIVE pede digitar LIVE). Antes de construir, o placar ao vivo mostrava E[R] da
            regra negativo no forex (−0,15/−0,27) e nas ações, ~+0,05 nos índices a 60 min pulando LATERAL; a página
            mostra esse simulado e o resultado real ao lado de cada botão
      - [x] 9e Laboratório de configuração (pedido do operador, 19/10): `RiskLab` testa ~5 mil combinações (modelo,
            15/60, momento, P mín., margem mín., pular LATERAL, stop 1/1,5/2 ATR, alvo 1/1,5/2) nas previsões fora da
            amostra (walk-forward + cofre) e ao vivo, saídas re-simuladas no M1 (`Outcomes.createGrid`, uma passada por
            horizonte). Escolhe até 2025-06 e só recomenda o que também for positivo na confirmação (2025-07 + cofre);
            mostra a sobrevivência do ranking. Só recomenda (decisão do operador). Tarefa "laboratorio" (um mercado pela página)
      - [x] 9f três topos (pedido do operador, 20/10): teste em M5/M15, 2022–26, todos os mercados — venda negativa em
            tudo (melhor: rompimento + saída estrutural, ouro M5 −0,07 R), igual ou pior que vender sem padrão. Por
            decisão do operador virou alerta no Radar (`scan/TripleTop`, a mesma definição do teste) com botão Vender que
            abre a página Operar com a venda calculada (todas as travas)
      - [x] 9g modo só sinais (20/10): regra de sinal dos índices em `live.signal-rules.indices` (B, 60 min, P ≥ 0,50,
            margem ≥ 0,10, só evento; saídas em `trading.risk.markets.indices` e nos perfis: stop 1 ATR, alvo 2×, 60 min);
            vigia global de sinais no dashboard (`signal-watch.ts`, GET /api/predictions/signals: faixa, bipe e notificação
            em qualquer página); a página Roleta recomenda parar a roleta e usar os sinais
      - [x] 9h mapa de eventos (20/10): `EventMap` (jev-features) — por divulgação alta/moderada desde 2022 × símbolo
            afetado: movimento em ATR em 15/60 min e lado a partir de +2 min conforme a surpresa (real × previsto);
            comando `events-map`, tarefa "mapa-eventos", página Eventos (próximos eventos × histórico, selo de força
            estatística z ≥ 3,3 por causa das ~1.000 comparações). Descritivo, não regra validada
      - [x] 9i conta cent + sinal automático do ouro (21/10): USC → US$ na fronteira (`AccountCurrency`); `AutoSignalService`
            (Flyway V14 `auto_signal_entry`): o sinal da regra do ouro vira ordem sozinho com TODAS as travas (decisão do
            operador), sinal de até `live.auto-max-age-seconds` (180), só nos `live.auto-markets` ([metals]); painel na
            página Hoje (LIVE pede digitar LIVE)
      - [x] 9j regra LARGA do ouro (decisão do operador, 22/10: mais sinais na conta cent): `live.signal-rules.metals` =
            B 15 min, margem ≥ 0,10, sem P mínima, `skip-flat` (novo), todos os momentos; saídas stop 1 ATR, alvo 2×, 15 min.
            Laboratório: escolha +0,11 R (200), confirmação +0,12 R (190, ~13/mês, pior queda −8,5 R); a regra anterior
            (só evento, P ≥ 0,45, margem ≥ 0,15) tinha +0,32 R mas ~2/mês. Laboratório lista as "frequentes" (E[R] ≥ 0,10)
      - [x] 9k alta frequência (pedido do operador, 22/10): `HighFrequencyService` (Flyway V15 `hf_entry`, página Alta
            frequência): a cada previsão nova, ordem no lado mais provável (pula LATERAL) com ALVO EM US$ (ex.: 0,10),
            stop SÓ DE PROTEÇÃO (3 ATR, escolha do operador) e saída por tempo; ignora todas as travas de risco por ordem
            (`OrderService.HF_WAIVED`); valem as técnicas, "Alvo em US$ × spread" e a perda do dia/semana (para tudo).
            Substituída pelo Cassino em 23/10 (9n); a tabela hf_entry fica como histórico
      - [x] 9l página Universo (pedido do operador, 22/10): um símbolo como planeta, previsões como luas, divulgações como
            asteroides (órbita = tempo, tamanho = importância), notícias como cometas e alertas do radar como estrelas;
            abas Passado (48 h, o que aconteceu + se o modelo acertou), Presente (1 h) e Futuro (7 dias). A cor de evento
            futuro = lado do consenso (previsto × anterior) × mapa de eventos, não previsão do número (GET /api/universe)
      - [x] 9m quartos de hora (pedido do operador, 22/10: "operar a cada 15 min"): momentos :15/:30/:45 (kind QUARTER) no
            ouro e no bitcoin (`features.control-minutes-by-market`), no Laboratório e nos filtros da roleta; ao vivo só nos
            mercados de `live.quarter-markets` (VAZIO). Resultado: 15 min quase sempre LATERAL; ouro 60 min P ≥ 0,50 nos
            quartos +0,09 R (92) / +0,50 R (36), mas 125 de 128 são compras (alta do ouro) e ~4/mês; bitcoin negativo.
            Treinar com os quartos piorou a regra larga em evento → o champion de produção continua só com horas cheias
      - [x] 9n Cassino (pedido do operador, 23/10; substitui a alta frequência): `CasinoService` (Flyway V16 `casino_entry`,
            estado em app_setting `casino`), página Cassino: por símbolo, alvo e stop em US$, lote, compra/venda, tentativas
            e saída por tempo opcional; Ligar abre a ordem NA HORA; fechou sem o alvo → nova tentativa; bateu o alvo ou
            acabaram as tentativas → a linha termina; recusa/falha → para a linha. Global opcional (lucro e/ou perda da
            rodada, fechadas + abertas): atingiu, fecha as posições do Cassino e, com rodadas sobrando, recomeça as linhas
            ligadas com tentativas zeradas (escolha do operador: mesmo depois do lucro). Travas: `OrderService.CASINO_WAIVED`
            (as da alta frequência + ATR); valem as técnicas, alvo e stop além de 2 spreads e a perda do dia/semana. Posições
            do Cassino fora do trailing/proteção/prorrogação (`repo.casinoTickets()`). Backtest por símbolo dos últimos 4
            quartos de hora completos (`CasinoBacktest`, M1 do radar, spread de agora, alvo+stop no mesmo minuto = stop)
- [ ] Passo 10 — notícias além dos bancos centrais (pedido do operador, 14/10)
      - [x] 10a coleta: GDELT GKG (histórico desde 2021-10 + ao vivo a cada 15 min; ~1.800 de ~140 mil notícias/dia
            passam no filtro; ~1 MB/dia no bronze) e SEC EDGAR (8-K/10-Q/10-K das 6 ações; espera EDGAR_USER_AGENT).
            silver/headlines (uma por URL, temas e tom do GDELT, available_utc) e silver/filings
      - [ ] 10b `headline-v2` (dimensões: ativo/moeda, juros, crescimento, risk-on/off, dólar, impacto, novidade — sem
            direção nem tese), plano e custo antes de avaliar (pontuar milhões de títulos não cabe no orçamento: amostra)
      - [ ] 10c estudo de evento (o preço reagiu?) antes de qualquer feature
- [x] Passo 11 — energia (pedido do operador, 15/10): UKOIL, USOIL e XNGUSD como mercado `energy` (Market, importador,
      exportador de candles com o input novo InpCandleMarkets3, EA 2.3 com InpSymbols6, dashboard, risco e perfis).
      Histórico M1 desde 2022 (4,58 mi barras, tools/mt5_history_backfill.py --markets energy). Walk-forward sem
      vantagem (E[R] −0,08 a −0,10 a 60 min; XNGUSD: spread maior que o movimento de 15 min, nunca sai de LATERAL).
      Decisão do operador: produção com os três — cofre aberto em 15/10 (run 20261015-184445-energy: AUC 0,65/0,71,
      gate 4 sem operações; lado mais provável −0,07 a 60 min) e modelo 20261015-190437 em produção;
      trading.risk.markets.energy fica desligado (ligar na página Risco)
