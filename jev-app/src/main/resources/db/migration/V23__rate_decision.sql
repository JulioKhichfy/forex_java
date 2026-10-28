-- Decisões de juros lidas do comunicado oficial do banco central (28/10/2026: o calendário da MetaQuotes parou de trazer
-- os resultados). Fonte separada do calendar_event (que continua sendo só o que o MT5 mandou); usada pela página
-- "Principal de hoje" quando o MT5 não tem o real da decisão.
CREATE TABLE rate_decision (
    id            BIGSERIAL PRIMARY KEY,
    market        TEXT        NOT NULL DEFAULT 'fx',
    currency      CHAR(3)     NOT NULL,
    event_at      TIMESTAMPTZ NOT NULL,
    event_code    TEXT        NOT NULL,
    rate          NUMERIC     NOT NULL,
    source_url    TEXT        NOT NULL,
    snippet       TEXT,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (currency, event_at)
);
