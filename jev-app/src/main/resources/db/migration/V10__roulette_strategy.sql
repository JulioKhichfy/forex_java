-- Roleta: estratégia de cada entrada (14/10/2026). trend = lado mais provável, pula LATERAL (a de antes);
-- pullback = só em LATERAL, contra a banda de Bollinger (teste do operador). O placar separa as duas.
ALTER TABLE roulette_entry ADD COLUMN strategy TEXT NOT NULL DEFAULT 'trend';
ALTER TABLE roulette_entry ADD COLUMN pct_b DOUBLE PRECISION;   -- posição do preço no canal de Bollinger (pullback)
