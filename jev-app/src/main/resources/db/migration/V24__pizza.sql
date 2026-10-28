-- Compra Pizza (pedido do operador, 28/10/2026): um jogo — o operador define o valor da pizza (lucro da partida) e o stop
-- (perda da partida); o sistema abre as operações em que mais confia até chegar num dos dois.
CREATE TABLE pizza_game (
    id            BIGSERIAL PRIMARY KEY,
    pizza_usd     NUMERIC     NOT NULL,
    stop_usd      NUMERIC     NOT NULL,
    max_positions INT         NOT NULL,
    status        TEXT        NOT NULL,   -- RUNNING | CLOSING | PIZZA | NO_PIZZA | STOPPED
    message       TEXT,
    result_usd    NUMERIC,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at      TIMESTAMPTZ
);

CREATE TABLE pizza_entry (
    id            BIGSERIAL PRIMARY KEY,
    game_id       BIGINT      NOT NULL REFERENCES pizza_game (id),
    market        TEXT        NOT NULL,
    symbol        TEXT        NOT NULL,
    side          TEXT        NOT NULL,
    prediction_id BIGINT,
    tier          TEXT        NOT NULL,   -- SIGNAL (regra do mercado) | STRONG (previsão forte)
    reason        TEXT,
    order_id      BIGINT,
    status        TEXT        NOT NULL,   -- SENT | DONE | REFUSED | FAILED
    result_usd    NUMERIC,
    message       TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    done_at       TIMESTAMPTZ
);
CREATE INDEX ix_pizza_entry_game ON pizza_entry (game_id);
CREATE INDEX ix_pizza_entry_order ON pizza_entry (order_id);
