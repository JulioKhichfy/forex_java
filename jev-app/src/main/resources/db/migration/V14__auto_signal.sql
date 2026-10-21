-- Sinal automático (pedido do operador, 21/10/2026): o sinal da regra do mercado (só ouro) vira ordem sozinho, com TODAS
-- as travas de risco. Uma linha por sinal visto: enviado, recusado pelas travas ou atrasado.
CREATE TABLE auto_signal_entry (
    id             BIGSERIAL PRIMARY KEY,
    market         TEXT        NOT NULL,
    prediction_id  BIGINT      NOT NULL UNIQUE REFERENCES prediction (id),
    symbol         TEXT        NOT NULL,
    side           TEXT,                                  -- BUY | SELL
    p_side         DOUBLE PRECISION,                      -- probabilidade do lado do sinal
    order_id       BIGINT REFERENCES order_request (id),  -- a ordem gravada para o EA
    status         TEXT        NOT NULL,                  -- SENDING | SENT | REFUSED | LATE
    message        TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_auto_signal_entry_market ON auto_signal_entry (market, created_at DESC);
