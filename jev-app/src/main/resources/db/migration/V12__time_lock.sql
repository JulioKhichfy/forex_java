-- Prorrogação com lucro na saída por tempo (pedido do operador, 15/10/2026). Em vez de fechar a mercado, a posição com
-- lucro recebe um MODIFY com stop E alvo perto do preço. Dinâmica: a cada nova máxima de lucro, stop e alvo andam e a
-- janela renova; sem nova máxima (ou no teto), fecha a mercado.
ALTER TABLE order_request ADD COLUMN tp_price DOUBLE PRECISION;        -- MODIFY: o novo alvo (null = o alvo fica)
-- na linha OPEN da posição:
ALTER TABLE order_request ADD COLUMN time_lock_started_at TIMESTAMPTZ; -- início da prorrogação (o teto conta daqui)
ALTER TABLE order_request ADD COLUMN time_lock_until TIMESTAMPTZ;      -- fecha a mercado neste instante (renova)
ALTER TABLE order_request ADD COLUMN time_lock_peak_usd DOUBLE PRECISION;   -- maior lucro usado para stop/alvo
ALTER TABLE order_request ADD COLUMN time_lock_peak_at TIMESTAMPTZ;    -- quando foi a última nova máxima
ALTER TABLE order_request ADD COLUMN time_lock_moves INT;              -- quantas vezes stop/alvo andaram depois do início
ALTER TABLE order_request ADD COLUMN time_lock_order_id BIGINT REFERENCES order_request (id);   -- o MODIFY do início
