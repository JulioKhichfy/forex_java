-- Fechamentos que o EA reporta (heartbeat): inclusive os que o SERVIDOR da corretora fez por SL/TP.
-- Base do placar das operações do operador (resultado em US$ e em R).
CREATE TABLE ea_deal (
    deal_ticket     BIGINT PRIMARY KEY,
    position_ticket BIGINT           NOT NULL,              -- = ticket da posição (order_request.ticket)
    account         BIGINT           NOT NULL,
    market          TEXT             NOT NULL DEFAULT 'fx',
    symbol          TEXT             NOT NULL,              -- canônico
    side            TEXT             NOT NULL,              -- lado do negócio de SAÍDA (venda fecha compra)
    volume          DOUBLE PRECISION NOT NULL,
    price           DOUBLE PRECISION NOT NULL,
    profit          DOUBLE PRECISION NOT NULL,              -- na moeda da conta, sem swap/comissão
    swap            DOUBLE PRECISION NOT NULL DEFAULT 0,
    commission      DOUBLE PRECISION NOT NULL DEFAULT 0,
    reason          TEXT             NOT NULL,              -- SL | TP | EA | MANUAL | STOP_OUT
    closed_at       TIMESTAMPTZ      NOT NULL,
    first_seen_at   TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE INDEX ix_ea_deal_closed ON ea_deal (closed_at DESC);
CREATE INDEX ix_ea_deal_position ON ea_deal (position_ticket);
