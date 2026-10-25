-- Momentum BTC com o filtro de fluxo (passo 12e, 25/10/2026): o desequilíbrio compra × venda agressiva da hora nos futuros
-- da Binance, no sentido do movimento (status FILTERED = fluxo abaixo do mínimo; sem resposta da Binance = REFUSED).
ALTER TABLE momentum_entry ADD COLUMN flow DOUBLE PRECISION;
