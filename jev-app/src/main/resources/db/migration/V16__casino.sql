-- Cassino (pedido do operador, 23/10/2026; substitui a alta frequência): por símbolo, alvo e stop em US$, lote, lado e
-- número de tentativas escolhidos pelo operador; ao ligar, a ordem sai na hora e se repete até bater o alvo ou acabar as
-- tentativas. Lucro/perda global por rodada (opcional) fecha tudo e recomeça, até o número de rodadas.
-- Uma linha por tentativa (ordem enviada ou recusada pelas travas).
CREATE TABLE casino_entry (
    id                  BIGSERIAL PRIMARY KEY,
    session_id          BIGINT           NOT NULL,              -- quando o Cassino foi ligado (epoch ms): agrupa as rodadas
    round               INT              NOT NULL,
    market              TEXT             NOT NULL,
    symbol              TEXT             NOT NULL,
    attempt             INT              NOT NULL,
    side                TEXT             NOT NULL,              -- BUY | SELL
    lot                 DOUBLE PRECISION,
    take_usd            DOUBLE PRECISION NOT NULL,
    stop_usd            DOUBLE PRECISION NOT NULL,
    close_after_minutes INT,                                    -- null = sem saída por tempo
    order_id            BIGINT REFERENCES order_request (id),
    status              TEXT             NOT NULL,              -- SENT | REFUSED | TARGET | CLOSED | FAILED
    result_usd          DOUBLE PRECISION,
    message             TEXT,
    created_at          TIMESTAMPTZ      NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ
);
CREATE INDEX ix_casino_entry_session ON casino_entry (session_id, round, symbol, id DESC);
CREATE INDEX ix_casino_entry_order ON casino_entry (order_id);
