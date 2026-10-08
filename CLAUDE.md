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
- Todas as tabelas de negócio têm a coluna `market` (`fx` | `metals` | `crypto`).
- Migrações só via Flyway (`jev-app/src/main/resources/db/migration`), nunca alterar uma migração já aplicada.
- `jev-core` não depende de Spring nem de banco.

## Segurança
- **Nunca** escrever a chave do Jev em arquivo versionado, log ou teste. Ela vem de `TYPESAFE_API_KEY`
  ou `JEV_API_KEY_FILE` (o arquivo fica FORA do repositório).
- API e Postgres escutam apenas em 127.0.0.1.

## Módulos
| Módulo | Papel |
|---|---|
| jev-core | domínio puro: Market, Instrument, RiskSettings, PositionSizer (lote mínimo) |
| jev-typesafe | JevClient, QuestionSet/Registry, JevResponse, CbTextSignal |
| jev-lake | LakeStorage em disco local (bronze/silver/gold) |
| jev-collect | coletor RSS dos bancos centrais, RawDocumentRepository |
| jev-app | Spring Boot: CLI, agendador, API `/api/status`, Flyway, application.yml |

## Comandos
```
docker compose up -d                       # Postgres
mvn -q clean package                       # build + testes
java -jar jev-app/target/jev-app.jar ping | risk --balance=20 | collect-once
java -jar jev-app/target/jev-app.jar ask --qset=cb-text-v1 --latest
java -jar jev-app/target/jev-app.jar       # modo servidor
```

## Contexto do operador
Banca pequena (~US$ 20) com lote mínimo 0,01: o sizing deve sempre mostrar o risco real do lote mínimo
e respeitar `min-lot-policy` (SKIP | ALLOW_UP_TO_CAP).

## Roteiro (status)
- [x] Passo 1 — fundação: build, Jev (ping/ask), risco configurável, coletor do Fed, lake, Postgres
- [ ] Passo 2 — MT5: exportadores de calendário e candles (MQL5 Services) + importadores Java + EA em shadow
- [ ] Passo 3 — silver: normalização, UTC, demais bancos centrais, ATR, labels, modelos A e B
- [ ] Passo 4 — Jev em escala: pontuação do histórico, sinais por moeda, features de texto
- [ ] Passo 5 — experimento A/B/C walk-forward e go/no-go
- [ ] Passo 6 — decision engine (gates), API bridge, EA executor, dashboard Angular; shadow → demo
