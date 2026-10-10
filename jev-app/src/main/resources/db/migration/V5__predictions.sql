-- Passo 6a: previsões ao vivo dos modelos de produção (dashboard) e o placar de cada uma depois do horizonte.

CREATE TABLE prediction (
    id             BIGSERIAL PRIMARY KEY,
    market         TEXT             NOT NULL DEFAULT 'fx',
    symbol         TEXT             NOT NULL,              -- canônico (EURUSD)
    moment_utc     TIMESTAMPTZ      NOT NULL,              -- momento de decisão (features até aqui)
    kind           TEXT             NOT NULL,              -- EVENT | CONTROL
    trigger        TEXT             NOT NULL,              -- SCHEDULE (hora cheia) | EVENT (evento + 2 min) | MANUAL
    model          TEXT             NOT NULL,              -- A (preço) | E (eventos) | B (eventos + preço)
    horizon_min    INT              NOT NULL,
    model_version  TEXT             NOT NULL,              -- models/market=fx/<versão>
    p_down         DOUBLE PRECISION NOT NULL,
    p_flat         DOUBLE PRECISION NOT NULL,
    p_up           DOUBLE PRECISION NOT NULL,
    signal         TEXT             NOT NULL,              -- BUY | SELL | NO_TRADE (gate 4)
    atr            DOUBLE PRECISION,                       -- ATR(14) M15 no momento (preço)
    close          DOUBLE PRECISION,                       -- último bid fechado
    spread_points  INT,
    data_lag_s     INT,                                    -- segundos entre o momento e a última barra
    features       JSONB            NOT NULL,              -- auditoria: o vetor exato que entrou no modelo
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    -- placar (preenchido depois do horizonte, com a mesma regra de stop/alvo/tempo do experimento)
    resolved_at    TIMESTAMPTZ,
    label          TEXT,                                   -- ALTA | QUEDA | LATERAL
    r_buy          DOUBLE PRECISION,
    r_sell         DOUBLE PRECISION,
    CONSTRAINT uq_prediction UNIQUE (market, symbol, moment_utc, model, horizon_min, model_version, trigger)
);
CREATE INDEX ix_prediction_moment ON prediction (moment_utc DESC);
CREATE INDEX ix_prediction_open ON prediction (moment_utc) WHERE resolved_at IS NULL;
