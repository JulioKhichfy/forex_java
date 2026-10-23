-- Cassino (23/10/2026, operador): "vezes" = quantas vezes abrir, aconteça o que acontecer; em cada vez, até N tentativas
-- para bater o alvo. E o "Deixa comigo!": o lado de cada ordem vem da previsão mais recente do modelo B.
ALTER TABLE casino_entry ADD COLUMN cycle INT;                                    -- a vez (1…vezes)
ALTER TABLE casino_entry ADD COLUMN auto_side BOOLEAN NOT NULL DEFAULT false;     -- lado escolhido pelo sistema
