# Jev Forex — passo 1: fundação

O que este passo entrega, já funcionando:

- **Conexão com o Jev** (`ping`) e perguntas tipadas em inglês, com tradução em português nos arquivos (`ask`).
- **Risco configurável** em um só lugar (`trading.risk` no `application.yml`) e um relatório que mostra,
  para a sua banca, o que o lote mínimo realmente arrisca em cada símbolo (`risk`).
- **Coletor dos discursos e comunicados do Fed**, gravando o bruto no data lake em disco local e no Postgres
  com `first_seen_at` (`collect-once` ou modo servidor).
- **API de status** em `http://localhost:8080/api/status`.

```
jev-forex/
├── jev-core/       domínio puro: mercados, instrumentos, sizing com lote mínimo (com testes)
├── jev-typesafe/   cliente do Jev + question-sets (cb-text-v1, headline-v1)
├── jev-lake/       data lake em disco local (bronze/silver/gold)
├── jev-collect/    coletor RSS dos bancos centrais
└── jev-app/        Spring Boot: CLI, agendador, API, Flyway, application.yml
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

- **Data lake:** `.\jev-lake\bronze\cb_web\date=AAAA-MM-DD\<sha256>.html` + `.meta.json`
  (mude com a variável `LAKE_ROOT`).
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

## Problemas comuns

| Sintoma | Verificar |
|---|---|
| `Chave do Jev não configurada` | terminal aberto antes do `setx`; abra outro |
| `HTTP 401` | chave errada ou revogada |
| `HTTP 402` / sem créditos | saldo no console da TypeSafe |
| `Connection refused` na porta 5432 | `docker compose up -d` |
| acentos estranhos no terminal | `chcp 65001` |
