-- Passo 4c: chamadas do Jev ligadas aos trechos do silver (silver/documents).
-- O cache é o request_sha (state + perguntas + modelo): o mesmo trecho nunca é pago duas vezes.

ALTER TABLE jev_call ADD COLUMN source    TEXT;        -- cb_web | bis_speeches
ALTER TABLE jev_call ADD COLUMN doc_sha   CHAR(64);    -- documento no silver
ALTER TABLE jev_call ADD COLUMN text_sha  CHAR(64);    -- trecho avaliado
ALTER TABLE jev_call ADD COLUMN chunk_idx INT;

CREATE INDEX ix_jev_call_ok ON jev_call (qset_code, model_requested, request_sha) WHERE http_status = 200;
CREATE INDEX ix_jev_call_text ON jev_call (qset_code, text_sha);
