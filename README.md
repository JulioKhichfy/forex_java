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
