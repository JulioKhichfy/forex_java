-- Passo 3b: anexos (PDF) dos documentos dos bancos centrais.
-- Ex.: o comunicado do Fed "Minutes of the Board's discount rate meetings" só tem a ata no PDF anexo.

ALTER TABLE raw_document ADD COLUMN parent_id BIGINT REFERENCES raw_document (id);   -- anexo → página de origem
ALTER TABLE raw_document ADD COLUMN content_type TEXT;                               -- html | pdf | txt
ALTER TABLE raw_document ADD COLUMN attachments_checked_at TIMESTAMPTZ;              -- quando os anexos foram procurados
CREATE INDEX ix_raw_document_parent ON raw_document (parent_id);

-- documentos já coletados: o tipo sai da extensão do arquivo no lake
UPDATE raw_document
   SET content_type = CASE WHEN lake_path LIKE '%.pdf' THEN 'pdf'
                           WHEN lake_path LIKE '%.txt' THEN 'txt'
                           ELSE 'html' END;
-- content_type fica opcional: um servidor ainda com o jar anterior continua gravando sem quebrar.
