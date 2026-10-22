-- Alta frequência (pedido do operador, 22/10/2026): a cada previsão nova dos mercados ligados, uma ordem no lado mais
-- provável (pula LATERAL), alvo em US$ (ex.: 0,10), stop largo só de proteção e saída por tempo; sem as travas de risco
-- por ordem. Uma linha por previsão vista: enviada, pulada ou recusada.
CREATE TABLE hf_entry (
    id             BIGSERIAL PRIMARY KEY,
    market         TEXT        NOT NULL,
    prediction_id  BIGINT      NOT NULL UNIQUE REFERENCES prediction (id),
    symbol         TEXT        NOT NULL,
    side           TEXT,
    p_side         DOUBLE PRECISION,
    order_id       BIGINT REFERENCES order_request (id),
    status         TEXT        NOT NULL,                  -- SENDING | SENT | SKIPPED | REFUSED
    message        TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_hf_entry_market ON hf_entry (market, created_at DESC);
