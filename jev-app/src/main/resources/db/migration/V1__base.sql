-- Passo 1: índice do bronze e auditoria das chamadas ao Jev.
-- Todas as tabelas de negócio têm a coluna market (fx | metals | crypto) desde já.

CREATE TABLE raw_document (
    id            BIGSERIAL PRIMARY KEY,
    source        TEXT        NOT NULL,              -- ex.: cb_web
    feed_id       TEXT,                              -- ex.: fed-speeches
    issuer        TEXT,                              -- ex.: Federal Reserve
    currency      CHAR(3),                           -- moeda afetada
    market        TEXT        NOT NULL DEFAULT 'fx',
    doc_type      TEXT        NOT NULL,              -- cb_text | headline
    url           TEXT        NOT NULL,
    title         TEXT,
    sha256        CHAR(64)    NOT NULL,
    lake_path     TEXT        NOT NULL,              -- caminho relativo no lake
    published_at  TIMESTAMPTZ,                       -- o que a fonte declara
    first_seen_at TIMESTAMPTZ NOT NULL,              -- quando o SEU coletor viu (vale para backtest)
    ingested_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_raw_document UNIQUE (source, url)
);
CREATE INDEX ix_raw_document_seen ON raw_document (doc_type, first_seen_at DESC);

CREATE TABLE jev_call (
    id               BIGSERIAL PRIMARY KEY,
    raw_document_id  BIGINT REFERENCES raw_document (id),
    market           TEXT        NOT NULL DEFAULT 'fx',
    qset_code        TEXT        NOT NULL,           -- ex.: cb-text-v1
    qset_sha         CHAR(64)    NOT NULL,           -- hash das perguntas enviadas
    model_requested  TEXT        NOT NULL,
    model_resolved   TEXT,                           -- versão que respondeu de fato
    request_sha      CHAR(64)    NOT NULL,           -- hash de state + perguntas + modelo (cache)
    request_json     JSONB       NOT NULL,
    response_json    JSONB,
    http_status      INT,
    input_tokens     INT,
    latency_ms       INT,
    error            TEXT,
    called_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_jev_call_request_sha ON jev_call (request_sha);
CREATE INDEX ix_jev_call_doc ON jev_call (raw_document_id);
