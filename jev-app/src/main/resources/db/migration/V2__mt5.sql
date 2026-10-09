-- Passo 2: dados do MetaTrader 5 (calendário, candles, especificações dos símbolos) e heartbeat do EA.
-- O bruto fica no bronze; aqui ficam o índice dos arquivos e o estado operacional.

-- Cada arquivo importado de Common\Files\jev\inbox (índice do bronze).
CREATE TABLE mt5_file (
    id            BIGSERIAL PRIMARY KEY,
    kind          TEXT        NOT NULL,              -- calendar | calevents | candles | symbols
    market        TEXT        NOT NULL DEFAULT 'fx',
    file_name     TEXT        NOT NULL,              -- nome original no inbox
    sha256        CHAR(64)    NOT NULL,
    lake_path     TEXT        NOT NULL,
    rows          INT         NOT NULL,
    min_time_utc  TIMESTAMPTZ,                       -- menor horário de dado no arquivo
    max_time_utc  TIMESTAMPTZ,
    first_seen_at TIMESTAMPTZ NOT NULL,              -- quando o importador viu o arquivo
    ingested_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_mt5_file_sha UNIQUE (sha256)
);
CREATE INDEX ix_mt5_file_kind ON mt5_file (kind, first_seen_at DESC);

-- Dicionário de eventos do calendário (CalendarEventByCurrency).
CREATE TABLE calendar_event_def (
    mt5_event_id  BIGINT PRIMARY KEY,
    market        TEXT        NOT NULL DEFAULT 'fx',
    event_code    TEXT        NOT NULL,
    name          TEXT        NOT NULL,
    currency      CHAR(3)     NOT NULL,
    country       TEXT,
    importance    TEXT        NOT NULL,              -- NONE | LOW | MODERATE | HIGH
    event_type    TEXT,                              -- EVENT | INDICATOR | HOLIDAY
    sector        TEXT,
    frequency     TEXT,
    time_mode     TEXT,
    unit          TEXT,
    multiplier    TEXT,
    digits        INT,
    source_url    TEXT,
    first_seen_at TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Valores do calendário, point-in-time: cada ESTADO distinto de um valor vira uma linha
-- (forecast antes da divulgação, actual depois, revisões). Nada é sobrescrito.
CREATE TABLE calendar_event (
    id               BIGSERIAL PRIMARY KEY,
    market           TEXT        NOT NULL DEFAULT 'fx',
    mt5_value_id     BIGINT      NOT NULL,
    mt5_event_id     BIGINT      NOT NULL,
    event_code       TEXT        NOT NULL,
    currency         CHAR(3)     NOT NULL,
    country          TEXT,
    importance       TEXT        NOT NULL,
    scheduled_at     TIMESTAMPTZ NOT NULL,           -- UTC (convertido pelo exportador; offset abaixo)
    server_offset_s  INT         NOT NULL,           -- TimeTradeServer() − TimeGMT() usado na conversão
    period           DATE,
    revision         INT,
    actual           NUMERIC,                        -- já dividido por 10^6; NULL = ausente
    forecast         NUMERIC,
    previous         NUMERIC,
    revised_previous NUMERIC,
    impact           TEXT,
    origin           TEXT        NOT NULL,           -- LIVE (visto ao vivo) | SNAPSHOT | HISTORY (exportado depois)
    state_sha        CHAR(64)    NOT NULL,           -- hash dos campos acima: identifica o estado
    first_seen_at    TIMESTAMPTZ NOT NULL,           -- quando o exportador viu ESTE estado pela primeira vez
    mt5_file_id      BIGINT      NOT NULL REFERENCES mt5_file (id),
    CONSTRAINT uq_calendar_event_state UNIQUE (mt5_value_id, state_sha)
);
CREATE INDEX ix_calendar_event_sched ON calendar_event (currency, scheduled_at);
CREATE INDEX ix_calendar_event_seen ON calendar_event (first_seen_at DESC);

-- Especificações reais dos símbolos na corretora (um registro por exportação).
CREATE TABLE instrument_spec (
    id                BIGSERIAL PRIMARY KEY,
    market            TEXT             NOT NULL,
    symbol            TEXT             NOT NULL,      -- canônico (EURUSD)
    broker_symbol     TEXT             NOT NULL,      -- na corretora (EURUSDm)
    digits            INT              NOT NULL,
    point             DOUBLE PRECISION NOT NULL,
    tick_size         DOUBLE PRECISION NOT NULL,
    tick_value        DOUBLE PRECISION NOT NULL,      -- na moeda da conta, por 1,00 lote
    tick_value_profit DOUBLE PRECISION,
    tick_value_loss   DOUBLE PRECISION,
    contract_size     DOUBLE PRECISION NOT NULL,
    volume_min        DOUBLE PRECISION NOT NULL,
    volume_step       DOUBLE PRECISION NOT NULL,
    volume_max        DOUBLE PRECISION NOT NULL,
    currency_base     TEXT,
    currency_profit   TEXT,
    currency_margin   TEXT,
    account_currency  TEXT             NOT NULL,
    spread_points     INT,
    stops_level       INT,
    freeze_level      INT,
    swap_long         DOUBLE PRECISION,
    swap_short        DOUBLE PRECISION,
    trade_mode        TEXT,
    bid               DOUBLE PRECISION,
    ask               DOUBLE PRECISION,
    server            TEXT,
    seen_at           TIMESTAMPTZ      NOT NULL,
    mt5_file_id       BIGINT           NOT NULL REFERENCES mt5_file (id)
);
CREATE INDEX ix_instrument_spec_symbol ON instrument_spec (symbol, seen_at DESC);

-- Última barra M1 importada por símbolo (os candles ficam no lake; aqui só o frescor).
CREATE TABLE candle_status (
    market             TEXT        NOT NULL,
    symbol             TEXT        NOT NULL,
    broker_symbol      TEXT        NOT NULL,
    first_bar_utc      TIMESTAMPTZ NOT NULL,
    last_bar_utc       TIMESTAMPTZ NOT NULL,
    last_close         DOUBLE PRECISION,
    last_spread_points INT,
    bars_imported      BIGINT      NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (market, symbol)
);

-- Saúde do EA e dos coletores (documento mestre, capítulo 7).
CREATE TABLE heartbeat (
    id             BIGSERIAL PRIMARY KEY,
    component      TEXT        NOT NULL,             -- ea
    market         TEXT        NOT NULL DEFAULT 'fx',
    account        BIGINT,
    server         TEXT,
    trade_mode     TEXT,                             -- DEMO | REAL | CONTEST
    ea_mode        TEXT,                             -- SHADOW
    equity         DOUBLE PRECISION,
    balance        DOUBLE PRECISION,
    currency       TEXT,
    open_positions INT,
    algo_enabled   BOOLEAN,
    connected      BOOLEAN,
    reported_at    TIMESTAMPTZ,                      -- relógio do EA (TimeGMT)
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail         JSONB
);
CREATE INDEX ix_heartbeat_component ON heartbeat (component, received_at DESC);
