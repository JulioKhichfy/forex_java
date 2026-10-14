-- Roleta (pedido do operador, 14/10/2026): a cada previsão nova de um mercado ligado, uma ordem no lado mais provável
-- de cada símbolo, com saída pelo horizonte escolhido. Uma linha por previsão vista: enviada, pulada ou recusada.
CREATE TABLE roulette_entry (
    id             BIGSERIAL PRIMARY KEY,
    market         TEXT        NOT NULL,
    prediction_id  BIGINT      NOT NULL UNIQUE REFERENCES prediction (id),
    symbol         TEXT        NOT NULL,
    model          TEXT        NOT NULL,
    horizon_min    INT         NOT NULL,
    side           TEXT,                                  -- BUY | SELL (null = pulada)
    p_side         DOUBLE PRECISION,                      -- probabilidade do lado escolhido
    order_id       BIGINT REFERENCES order_request (id),  -- a ordem gravada para o EA
    status         TEXT        NOT NULL,                  -- SENT | SKIPPED | REFUSED
    message        TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_roulette_entry_market ON roulette_entry (market, created_at DESC);
