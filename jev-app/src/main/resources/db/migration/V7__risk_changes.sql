-- Alterações de risco feitas pelo dashboard (página Risco): o antes e o depois de cada gravação.
-- O valor em vigor fica em trading.risk (config/application.yml ao lado do jar); aqui fica só a auditoria.
CREATE TABLE risk_change (
    id         BIGSERIAL PRIMARY KEY,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    source     TEXT        NOT NULL,              -- dashboard | cli
    note       TEXT,
    old_json   JSONB       NOT NULL,
    new_json   JSONB       NOT NULL
);
CREATE INDEX ix_risk_change_at ON risk_change (changed_at DESC);
