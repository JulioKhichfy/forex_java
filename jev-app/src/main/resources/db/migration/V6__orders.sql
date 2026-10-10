-- Passo 6c: ordens pelo dashboard (o operador decide e clica; o EA executa com SL/TP no servidor).

-- Cada pedido de ordem, do clique até a execução (ou recusa). Nada é apagado.
CREATE TABLE order_request (
    id              BIGSERIAL PRIMARY KEY,
    market          TEXT             NOT NULL DEFAULT 'fx',
    symbol          TEXT             NOT NULL,              -- canônico (EURUSD); o EA põe o sufixo
    action          TEXT             NOT NULL,              -- OPEN | CLOSE
    side            TEXT,                                   -- BUY | SELL (OPEN)
    volume          DOUBLE PRECISION,
    sl_distance     DOUBLE PRECISION,                       -- distância do stop em PREÇO (OPEN: obrigatório);
    tp_distance     DOUBLE PRECISION,                       --   o EA aplica sobre o preço real da execução
    ref_price       DOUBLE PRECISION,                       -- cotação vista no clique (referência)
    atr             DOUBLE PRECISION,
    risk_usd        DOUBLE PRECISION,                       -- perda se o stop for atingido (sem slippage)
    deviation_pts   INT,
    close_after_utc TIMESTAMPTZ,                            -- saída por tempo (null = só SL/TP)
    ticket          BIGINT,                                 -- CLOSE: posição a fechar; OPEN: posição aberta
    prediction_id   BIGINT REFERENCES prediction (id),      -- previsão que o operador estava vendo (auditoria)
    checks          JSONB            NOT NULL,              -- cada trava: valor, limite, ok
    status          TEXT             NOT NULL,              -- PENDING | SENT | FILLED | REJECTED | EXPIRED | CANCELLED | SHADOW
    message         TEXT,
    fill_price      DOUBLE PRECISION,
    account         BIGINT,
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ      NOT NULL,              -- o EA recusa depois disso (validade de 30 s)
    sent_at         TIMESTAMPTZ,
    done_at         TIMESTAMPTZ
);
CREATE INDEX ix_order_request_pending ON order_request (created_at) WHERE status IN ('PENDING', 'SENT');
CREATE INDEX ix_order_request_created ON order_request (created_at DESC);

-- Posições abertas como o EA reporta (substituídas a cada relatório).
CREATE TABLE ea_position (
    ticket        BIGINT PRIMARY KEY,
    market        TEXT             NOT NULL DEFAULT 'fx',
    account       BIGINT           NOT NULL,
    symbol        TEXT             NOT NULL,                -- canônico
    side          TEXT             NOT NULL,                -- BUY | SELL
    volume        DOUBLE PRECISION NOT NULL,
    open_price    DOUBLE PRECISION NOT NULL,
    sl            DOUBLE PRECISION,
    tp            DOUBLE PRECISION,
    profit        DOUBLE PRECISION,                         -- na moeda da conta, com swap
    magic         BIGINT,
    open_time     TIMESTAMPTZ,
    reported_at   TIMESTAMPTZ      NOT NULL
);

-- Ajustes feitos pelo dashboard (lote padrão, BLOCK…). Limites de risco NÃO ficam aqui: só em trading.risk.
CREATE TABLE app_setting (
    key        TEXT PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
