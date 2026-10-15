-- Trailing stop (14/10/2026): pedido MODIFY = mover o stop de uma posição aberta para um PREÇO (o alvo fica).
-- action passa a ser OPEN | CLOSE | MODIFY; ticket = a posição; sl_price = o novo stop.
ALTER TABLE order_request ADD COLUMN sl_price DOUBLE PRECISION;
