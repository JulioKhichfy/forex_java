# Jev Forex

- **Passo 1 — fundação** (seções 1 a 8): Jev, risco configurável, coletor do Fed, lake, Postgres.
- **Passo 2 — MetaTrader 5** (seção 9): calendário e candles exportados pelo MT5, importados para o lake
  e o Postgres; especificações reais dos símbolos; EA em modo shadow.

## Passo 1 — fundação

O que o passo 1 entrega:

- **Conexão com o Jev** (`ping`) e perguntas tipadas em inglês, com tradução em português nos arquivos (`ask`).
- **Risco configurável** em um só lugar (`trading.risk` no `application.yml`) e um relatório que mostra,
  para a sua banca, o que o lote mínimo realmente arrisca em cada símbolo (`risk`).
- **Coletor dos discursos e comunicados do Fed**, gravando o bruto no data lake em disco local e no Postgres
  com `first_seen_at` (`collect-once` ou modo servidor).
- **API de status** em `http://localhost:8080/api/status`.

```
jev-forex/
├── jev-core/       domínio puro: mercados, instrumentos, sizing com lote mínimo, sufixo da corretora
├── jev-typesafe/   cliente do Jev + question-sets (cb-text-v1, headline-v1)
├── jev-lake/       código do data lake (os DADOS ficam fora do repositório, ver LAKE_ROOT)
├── jev-collect/    coletor RSS dos bancos centrais + importador do MT5
├── jev-app/        Spring Boot: CLI, agendadores, API (status e ponte do EA), Flyway, application.yml
└── mql5/           Services e EA do MetaTrader 5 + install.ps1
```

---

## 1. Pré-requisitos (uma vez)

| Item | Como conferir |
|---|---|
| JDK 21 | `java -version` → 21.x |
| Maven 3.9+ | `mvn -v` |
| Docker Desktop | `docker version` |

No Windows Terminal/PowerShell, rode `chcp 65001` para os acentos aparecerem corretamente.

## 2. A chave do Jev (nunca no código)

> Se a sua chave apareceu em alguma conversa, e-mail ou print, gere uma nova no console da TypeSafe
> (API Keys) e apague a antiga.

Escolha **uma** das formas (PowerShell; abra um terminal novo depois do `setx`):

```powershell
# opção A — variável de ambiente
setx TYPESAFE_API_KEY "cole-a-chave-aqui"

# opção B — apontar para o arquivo que já existe em FOREX_JEV (contém só a chave)
setx JEV_API_KEY_FILE "C:\Users\julio\FOREX_JEV\Api_Key_Jev.txt"
```

O `.gitignore` já impede que `Api_Key_Jev.txt` e `.env` entrem no Git.

## 3. Banco de dados

```powershell
cd C:\Users\julio\FOREX_JEV\jev-forex
docker compose up -d
docker compose ps        # jevforex-postgres deve estar "running"
```

As tabelas são criadas automaticamente (Flyway) na primeira execução que usar o banco.

## 4. Build

```powershell
mvn -q clean package
```

Gera `jev-app\target\jev-app.jar`. Os testes do `jev-core` (sizing com lote mínimo) rodam no build.

## 5. Primeiros comandos

```powershell
# 5.1 testa a chave (não gasta créditos; não precisa do Postgres)
java -jar jev-app\target\jev-app.jar ping

# 5.2 mostra o risco real do lote mínimo para a sua banca (não precisa do Postgres)
java -jar jev-app\target\jev-app.jar risk --balance=20

# 5.3 primeira pergunta ao Jev com um texto seu
java -jar jev-app\target\jev-app.jar ask --qset=cb-text-v1 --text="Inflation progress has stalled and the Committee is prepared to keep rates restrictive for longer."

# 5.4 coleta os discursos e comunicados do Fed (primeira vez: ~1 min, baixa os itens atuais do feed)
java -jar jev-app\target\jev-app.jar collect-once

# 5.5 o Jev avalia o documento mais recente coletado
java -jar jev-app\target\jev-app.jar ask --qset=cb-text-v1 --latest

# 5.6 manchete
java -jar jev-app\target\jev-app.jar ask --qset=headline-v1 --text="ECB's Lagarde signals further rate cuts as euro-area growth stalls"
```

Acrescente `--raw` a qualquer `ask` para ver o JSON completo da resposta.

## 6. Modo servidor

```powershell
java -jar jev-app\target\jev-app.jar
```

- coleta automática a cada 60 s (`collect.interval-seconds`);
- `http://localhost:8080/api/status` mostra modo, documentos, chamadas ao Jev e os limites de risco ativos.

Para parar: `Ctrl+C`. O Postgres continua rodando (`docker compose stop` para parar).

## 7. Onde ficam os dados

- **Data lake:** `C:\Users\julio\FOREX_JEV\jev-lake\bronze\cb_web\date=AAAA-MM-DD\<sha256>.html` + `.meta.json`
  (fora do repositório; mude com a variável `LAKE_ROOT`).
- **Postgres:** tabelas `raw_document` (índice do bronze) e `jev_call` (cada pergunta e resposta do Jev).

```powershell
docker exec -it jevforex-postgres psql -U jev -d jevforex -c "select id, feed_id, title, first_seen_at from raw_document order by id desc limit 10;"
```

## 8. Ajustando o risco

Tudo em `jev-app/src/main/resources/application.yml`, bloco `trading.risk`:

- `global` vale para a conta inteira; `markets.fx / metals / crypto` é o orçamento de cada mercado.
- Onde existe limite em % e em US$, **vale o menor**.
- `min-lot-policy`: `SKIP` não opera quando nem o lote mínimo cabe no risco-alvo;
  `ALLOW_UP_TO_CAP` opera com o lote mínimo se não passar do teto global por trade.

Depois de editar: `mvn -q package` e rode `risk` de novo para ver o efeito.
Para testar sem recompilar, passe o valor na linha de comando, por exemplo:
`java -jar jev-app\target\jev-app.jar risk --balance=20 --trading.risk.global.min-lot-policy=SKIP`.

## 9. Passo 2 — MetaTrader 5 (Exness, conta demo)

Como os dados fluem (documento mestre, capítulo 13):

```
MT5 (Services)                Common\Files\jev               Java (modo servidor)
JevCalendarExporter ──┐       tmp\   (arquivo em construção)
JevCandleExporter  ───┼─────► inbox\ (arquivo pronto)  ──────► bronze (imutável) + Postgres
                      │       state\ (onde cada Service parou) error\ (recusados + motivo)
JevExecutor (EA) ◄────┴──── HTTP 127.0.0.1:8080 /api/ea/signals e /api/ea/heartbeat
```

### 9.1 Uma vez

```powershell
# conta e servidor aceitos (dados de outra conta/terminal são recusados); abra um terminal novo depois
setx MT5_ACCOUNT "seu-login"
setx MT5_SERVER  "Exness-MT5Trial11"

# copia os programas para o terminal da Exness e compila (rode de novo se mudar algum .mq5)
powershell -ExecutionPolicy Bypass -File mql5\install.ps1
```

No MT5 da Exness:

1. **Ferramentas → Opções → Gráficos → Máx. barras no gráfico: Ilimitado** (para o histórico M1 completo).
   Reinicie o terminal.
2. **Ferramentas → Opções → Expert Advisors:** marque *Permitir negociação algorítmica* e
   *Permitir WebRequest para as URLs listadas*, e adicione `http://127.0.0.1:8080`.
3. **Navegador → Serviços → Jev:** botão direito em `JevCalendarExporter` → *Adicionar serviço* → OK.
   Repita com `JevCandleExporter`. Eles iniciam sozinhos e voltam a iniciar com o terminal.
4. Abra um gráfico qualquer (ex.: `EURUSDm` M1) e arraste **Experts → Jev → JevExecutor** para ele.

### 9.2 No dia a dia

```powershell
docker compose up -d
java -jar jev-app\target\jev-app.jar          # modo servidor: importa o inbox a cada 2 s e atende o EA
java -jar jev-app\target\jev-app.jar mt5-status    # (outro terminal) saúde do MT5
java -jar jev-app\target\jev-app.jar risk --balance=20 --mt5   # risco com os valores REAIS da Exness
```

Sem o modo servidor, `import-mt5-once` importa o que estiver no inbox e sai.

- **Primeira carga:** o `JevCandleExporter` exporta o M1 desde 2016 (um arquivo por símbolo e mês) e o
  `JevCalendarExporter` exporta o calendário das 8 moedas desde 2016. Pode levar alguns minutos. Na aba
  *Diário* do MT5 aparecem as mensagens `JevCandleExporter: EURUSD histórico → … barras`.
- **Profundidade do histórico na Exness demo (conferido em 09/10/2026):** o servidor tem M1 de USDJPY, USDCHF,
  AUDUSD, USDCAD e NZDUSD só a partir de **27/10/2021** (Ctrl+U → Barras confirma). EURUSD e GBPUSD vieram
  desde 2016 porque o terminal já os tinha. Quando o histórico para de recuar por ~10 min, o Service começa do
  que existe e avisa no log. Para o modelo único dos 7 pares, o período comum começa em 10/2021.
- **Reinício:** cada Service guarda onde parou em `jev\state` e continua dali, sem buracos.
  Para reexportar um símbolo do zero, apague `Common\Files\jev\state\candles_last_<SÍMBOLO>.txt`.
- **Point-in-time:** cada valor do calendário guarda cada *estado* visto (forecast antes, actual depois) com
  `first_seen_at` e a origem: `LIVE` (visto ao vivo), `SNAPSHOT` ou `HISTORY` (exportado depois do fato,
  não vale como horário de disponibilidade).
- **Fuso:** o MT5 entrega horários do servidor. Os arquivos levam o horário do servidor, o UTC e o
  deslocamento usado (Exness: 0 s).
- **EA:** neste passo o `JevExecutor` é **só shadow**. Ele não contém código de envio de ordens. Busca sinais,
  valida, registra no Diário o que faria e manda heartbeat a cada 10 s.

Tabelas novas: `mt5_file`, `calendar_event`, `calendar_event_def`, `instrument_spec`, `candle_status`, `heartbeat`.
Os candles em si ficam só no lake (`bronze\mt5_candles`); o Postgres guarda o frescor por símbolo.

## 10. Passo 3 — silver (normalização)

```powershell
java -jar jev-app\target\jev-app.jar normalize                  # candles + calendário (~3 min)
java -jar jev-app\target\jev-app.jar normalize --only=calendar  # só o calendário (segundos)
java -jar jev-app\target\jev-app.jar normalize --only=documents # textos dos bancos centrais em trechos
```

Reconstrói o silver inteiro a partir do bronze (DuckDB → Parquet, tudo em UTC) e mostra checagens de qualidade.
Não precisa do Postgres. O silver novo só substitui o anterior quando está completo.

| Silver | Conteúdo |
|---|---|
| `silver\candles_m1\market=fx\symbol=…\year=…\month=…` | 1 barra M1 por minuto UTC (abertura da barra), sem repetidas |
| `silver\calendar_events\market=fx\year=…` | cada estado de cada valor, com `seen_utc`, `origin` e `actual_available_utc` |
| `silver\calendar_event_defs` | dicionário de eventos (o `event_code` em inglês é a chave; o nome vem traduzido pelo MT5) |
| `silver\documents\market=fx\year=…` | 1 linha por **trecho** (até 6.000 caracteres, por parágrafos) de cada comunicado, ata ou discurso, HTML ou PDF anexo |

**Bancos centrais (passo 3b):** Fed, BCE, BoE, BoJ, SNB, BoC e RBNZ, em `collect.feeds`. A **RBA está desligada**:
o servidor dela recusa (HTTP 403) qualquer User-Agent identificado, e o documento mestre pede coletor identificado.
Para cada página nova, o coletor baixa os **PDFs anexos** do corpo do artigo (ex.: a ata do Fed que só existe em
PDF). O anexo vira um documento próprio, ligado à página (`raw_document.parent_id`; `parent_sha` no silver).
Páginas antigas são revisitadas aos poucos (`collect.attachments.backfill-per-run`). O `normalize --only=documents`
mostra, por banco, quantos documentos e trechos saíram e uma amostra do texto, para conferir a extração.

- **Point-in-time do calendário:** `actual_available_utc` é o horário visto ao vivo (`LIVE`) ou, no histórico,
  `scheduled + silver.calendar.actual-latency-seconds` (35 s; `availability_estimated = true`). O comando mostra a
  latência medida ao vivo: use-a para ajustar o valor.
- **Fuso do histórico da Exness:** a checagem de abertura semanal (domingo 17:00 Nova York) mostrou que o M1 de
  EURUSD/GBPUSD de 2016 a 2019 está gravado em outro fuso. Por isso `silver.candles.utc-reliable-from: 2020-01-01`:
  antes disso nada entra no silver (o bronze continua intacto). O calendário está certo em todos os anos.
- Para ler o silver em outras ferramentas (DBeaver com DuckDB, Python):
  `SELECT * FROM read_parquet('C:/Users/julio/FOREX_JEV/jev-lake/silver/candles_m1/**/*.parquet', hive_partitioning = true)`

### Acervo de discursos do BIS (passo 4a)

```powershell
java -jar jev-app\target\jev-app.jar backfill-bis           # zips anuais desde bis.from-year (2021) → bronze
java -jar jev-app\target\jev-app.jar normalize --only=documents
```

- Discursos dos 8 bancos e dos maiores bancos do Eurosistema (Bundesbank, Banque de France, Banca d'Italia,
  Banco de España, DNB), identificados pela descrição; os demais ficam de fora. Uso não comercial permitido.
- **Point-in-time:** `available_utc` = fim do dia da publicação no BIS (data da URL `…/r240109a.htm`), com
  `availability_estimated = true`. O campo `date` do BIS tem erros (~10%) e não é usado. Servem para features
  diárias (cap. 8).
- Discurso que veio pelas duas fontes entra uma vez, com a cópia disponível primeiro.
- Rode `backfill-bis` de tempos em tempos: o BIS atualiza o acervo (conteúdo novo = nova versão no bronze).

### Comunicados de decisão e atas (passo 4b)

```powershell
java -jar jev-app\target\jev-app.jar normalize --only=calendar       # as datas/horários vêm do calendário
java -jar jev-app\target\jev-app.jar backfill-archives               # fed, ecb, boe, boj, boc desde 2021
java -jar jev-app\target\jev-app.jar normalize --only=documents
```

| Banco | Fonte | Horário de divulgação (calendário do MT5) |
|---|---|---|
| Fed | `fomccalendars.htm` (comunicados e atas) | `fomc-meeting-statement`; ata: primeiro `fomc-minutes` depois da reunião |
| BCE | índices anuais: decisões, declaração da coletiva, *accounts* | `ecb-interest-rate-decision`, `…-press-conference`, `…-meeting-accounts` |
| BoE | `/monetary-policy-summary-and-minutes/{ano}/{mês}-{ano}` (datas do calendário) | `boe-interest-rate-decision` |
| BoJ | índices anuais: comunicados e atas (`g{data}.htm`/`.pdf`) | comunicado no dia; ata: depois da reunião SEGUINTE |
| BoC | `/{ano}/{mês}/fad-press-release-{data}/` (datas do calendário) | `boc-interest-rate-decision` |

- `available_utc` = horário oficial + 30 s (cap. 8); sem evento no calendário, fim do dia (estimado). PDFs anexos
  herdam o horário da página; anexo que só repete a página (ex.: a ata do FOMC em PDF) não entra.
- O comando grava em `bronze\cb_archive_index` a lista de URLs com a regra de horário: vale também para os
  comunicados que já tinham vindo pelos feeds na primeira carga.
- SNB, RBNZ e RBA ficam de fora por ora (o BIS traz as declarações do SNB; a RBNZ limita a taxa; a RBA bloqueia).

### O Jev em escala (passo 4c)

```powershell
java -jar jev-app\target\jev-app.jar jev-score                       # PLANO: pendentes, cache, custo estimado
java -jar jev-app\target\jev-app.jar jev-score --run --limit=200     # piloto
java -jar jev-app\target\jev-app.jar jev-score --run --max-usd=1.50  # o resto, com teto de gasto
java -jar jev-app\target\jev-app.jar jev-signals                     # respostas → gold
```

- Avalia cada trecho de `silver\documents` com o conjunto `cb-text-v1` (state: emissor, moeda, título, texto).
- **Cache:** o pedido (state + perguntas + modelo) tem um `request_sha`; o que já foi respondido com sucesso nunca
  é pago de novo — vale também para o que foi perguntado pelo `ask`. Interrompeu? Rode de novo: continua de onde parou.
- **Custo antes:** sem `--run`, só mostra o plano. A estimativa usa a proporção tokens ÷ caracteres medida nas
  chamadas já feitas e o preço `jev.input-price-usd-per-million` (piloto de 200 trechos: real 9% abaixo do estimado).
- Para sozinho no teto (`--max-usd`) ou em erro de chave/saldo (401/402/403). Cada chamada fica em `jev_call`
  (com `doc_sha`, `text_sha`, `chunk_idx`).
- `jev-signals` grava `gold\jev_answers` (por trecho: probabilidades, confiança, sinal) e `gold\currency_signals`
  (por documento: média dos trechos ponderada por `market_relevant`, disponível em `available_utc`).

### 10.1 Features e labels (gold)

```powershell
java -jar jev-app\target\jev-app.jar normalize --only=candles   # também grava silver\instrument_specs (point)
java -jar jev-app\target\jev-app.jar features
```

| Gold | Conteúdo |
|---|---|
| `gold\features\market=fx\fset=v1\year=…` | 1 linha por (momento de decisão, par) |
| `gold\labels\market=fx\fset=v1\horizon=15m\|60m` | resultado depois de 15/60 min, já pagando o spread; `label` = ALTA, QUEDA ou LATERAL |

- **Momentos de decisão** (cap. 11): `EVENT` = 2 min depois de cada divulgação de importância média/alta, nos
  pares da moeda (USD → os 7); `CONTROL` = hora cheia, seg a sex, 0h–20h UTC (sem sexta depois das 19h).
- **Grupo A (preço, custo, fator USD):** `atr`, `atr_bps`, `ret15/60/240_atr`, `dist_sma50_h1_atr`, `rsi14_m15`,
  `day_range_atr`, `regime` (ATR ÷ mediana de 20 dias), `spread_atr`, `spread_rel` (÷ mediana do mesmo horário
  nos 20 dias anteriores), `hour_sin/cos`, `dow`, `sess_tokyo/london/ny`, `usd_factor15/60_bps`, `resid15/60_bps`.
- **Grupo B (calendário):** `surprise_base`, `surprise_quote`, `surprise_diff` (z = polaridade × (actual − forecast)
  ÷ σ das 24 divulgações anteriores, limitado a ±4, peso por importância, decaimento de 60 min),
  `min_since_event`, `min_to_event`.
- **Point-in-time:** em t só entram barras que fecharam até t e divulgações com `actual_available_utc ≤ t`
  (há teste automatizado que injeta um salto na barra que abre em t e exige features idênticas).
- **Labels:** entrada no preço da barra que abre em t + 1 min (o M1 não tem preço no meio do minuto), saída h
  minutos depois; ask = bid + spread da barra × point. y_compra = (bid_saída − ask_entrada) ÷ ATR; ALTA se
  y_compra > 0,5; QUEDA se y_venda > 0,5. `label_available_utc` diz quando o resultado ficou conhecido (embargo no 3d).
- **v1 simplifica:** ATR e RSI com média simples (não Wilder) e média de 50 barras H1 no lugar da EMA50.
  O grupo C (texto do Jev) entra no passo 4.

### 10.2 Experimento A × B (walk-forward)

```powershell
java -Xmx6g -jar jev-app\target\jev-app.jar train              # 60 e 15 min (~30 min)
java -Xmx6g -jar jev-app\target\jev-app.jar train --horizon=60 # só o horizonte principal (~15 min)
```

- **A** = grupo A (preço, custo, sessão, fator USD) + par; **B** = A + calendário (surpresa e proximidade de
  eventos). Treinados e avaliados nas mesmas linhas: a diferença mede só o calendário.
- **Walk-forward** (cap. 11): período comum desde 12/2021; treina 24 meses, testa o mês seguinte, avança 1 mês.
  **Embargo:** só treina com labels conhecidos 1 dia antes do teste. **Cofre:** os últimos 6 meses completos
  ficam fora — serão abertos uma única vez, com o modelo escolhido (passo 5).
- Gradient boosting de 3 classes (Smile, GPL v3) com hiperparâmetros fixos (`experiment.gbm`); a grade fica
  para o passo 5.
- **Relatório:** `<lake>\reports\walkforward\<run>\report.html` (+ `metrics.json`): log loss e AUC fora da
  amostra contra a linha de base, só nos momentos de evento, por fold, operações simuladas com o gate 4
  (R = resultado no horizonte ÷ 1,5 ATR, sem stop/alvo dentro do horizonte), sensibilidade aos limiares e
  importância das features. Previsões em `gold\predictions\…\run=<run>`.

**Desenvolvimento sem parar o servidor:** `mvn -q package -Djar.name=jev-app-dev` gera `jev-app-dev.jar`
ao lado do `jev-app.jar` em uso (sem `clean`, que falharia com o jar travado).

## Problemas comuns

| Sintoma | Verificar |
|---|---|
| `mt5-status` sem candles/calendário | Services rodando? (Navegador → Serviços; aba Diário) |
| arquivos em `Common\Files\jev\error` | o motivo está no `.erro.txt` ao lado (ex.: conta ou sufixo diferente) |
| EA: "URL não está liberada" | Ferramentas → Opções → Expert Advisors → WebRequest → `http://127.0.0.1:8080` |
| EA: "API fora" | Java em modo servidor (`java -jar jev-app\target\jev-app.jar`) |
| histórico M1 curto | *Máx. barras no gráfico* = Ilimitado e reinício do terminal |
| `Chave do Jev não configurada` | terminal aberto antes do `setx`; abra outro |
| `HTTP 401` | chave errada ou revogada |
| `HTTP 402` / sem créditos | saldo no console da TypeSafe |
| `Connection refused` na porta 5432 | `docker compose up -d` |
| acentos estranhos no terminal | `chcp 65001` |
