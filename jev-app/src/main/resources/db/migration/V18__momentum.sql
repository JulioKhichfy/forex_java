-- Momentum BTC (passo 12b, 25/10/2026): uma linha por hora de movimento grande vista com a estratégia ligada — ordem
-- enviada (todas as travas), recusada pelas travas, pulada (já havia posição) ou atrasada.
CREATE TABLE momentum_entry (
    id          BIGSERIAL PRIMARY KEY,
    market      TEXT             NOT NULL,
    symbol      TEXT             NOT NULL,
    bar_start   TIMESTAMPTZ      NOT NULL,              -- a hora do sinal (UTC); a entrada é no fim dela
    side        TEXT             NOT NULL,              -- BUY | SELL (a favor do movimento)
    ret         DOUBLE PRECISION NOT NULL,              -- ln(fechamento/abertura) da hora
    threshold   DOUBLE PRECISION NOT NULL,              -- limiar de |ret| (topo das horas anteriores)
    atr         DOUBLE PRECISION NOT NULL,              -- ATR(14) de 1 h
    order_id    BIGINT REFERENCES order_request (id),
    status      TEXT             NOT NULL,              -- SENDING | SENT | REFUSED | SKIPPED | LATE
    message     TEXT,
    created_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT uq_momentum_entry UNIQUE (symbol, bar_start)
);
CREATE INDEX ix_momentum_entry_created ON momentum_entry (created_at DESC);
