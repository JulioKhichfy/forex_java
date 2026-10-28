-- Página "Principal de hoje" (pedido do operador, 28/10/2026): os eventos de importância alta das próximas horas e, em
-- cada um, um plano — operar automaticamente quando sair o resultado (pelo modelo B depois do evento ou pelo dado) ou
-- operar agora no lado do consenso. Ordens sem as travas de risco por ordem (como o Cassino); stop e alvo em US$.
CREATE TABLE event_plan (
    id                  BIGSERIAL PRIMARY KEY,
    currency            TEXT             NOT NULL,
    event_at            TIMESTAMPTZ      NOT NULL,
    event_code          TEXT             NOT NULL,              -- o evento principal do horário (o que decide no modo dado)
    mode                TEXT             NOT NULL,              -- MODEL | SURPRISE | NOW
    symbols             TEXT             NOT NULL,              -- separados por vírgula
    lot                 DOUBLE PRECISION,                       -- null = o lote mínimo de cada símbolo
    stop_usd            DOUBLE PRECISION NOT NULL,
    take_usd            DOUBLE PRECISION NOT NULL,
    close_after_minutes INT,                                    -- null = só alvo e stop
    currency_side       INT,                                    -- NOW: +1 = a favor da moeda, −1 = contra
    status              TEXT             NOT NULL,              -- ARMED | DONE | EXPIRED | CANCELLED | FAILED
    message             TEXT,
    created_at          TIMESTAMPTZ      NOT NULL DEFAULT now(),
    done_at             TIMESTAMPTZ
);
CREATE INDEX ix_event_plan_status ON event_plan (status, event_at);

CREATE TABLE event_plan_order (
    id          BIGSERIAL PRIMARY KEY,
    plan_id     BIGINT      NOT NULL REFERENCES event_plan (id),
    symbol      TEXT        NOT NULL,
    side        TEXT,                                           -- BUY | SELL (null = não entrou)
    order_id    BIGINT REFERENCES order_request (id),
    status      TEXT        NOT NULL,                           -- SENT | REFUSED | SKIPPED
    message     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_event_plan_order UNIQUE (plan_id, symbol)
);
CREATE INDEX ix_event_plan_order_order ON event_plan_order (order_id);
