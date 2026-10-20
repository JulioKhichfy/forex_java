-- Edição manual de stop e alvo pela página Operar (pedido do operador, 20/10/2026). MANUAL = o operador escolheu os
-- preços: o EA (2.6) aceita afastar o stop (o servidor já conferiu o teto de risco); sem marcação = trailing/prorrogação
-- (o stop só anda a favor).
ALTER TABLE order_request ADD COLUMN modify_mode TEXT;
