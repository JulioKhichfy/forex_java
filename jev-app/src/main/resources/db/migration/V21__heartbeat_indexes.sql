-- 26/10/2026: a tabela heartbeat chegou a 226 mil linhas / 1 GB (o EA 2.9 manda 53 cotações a cada 5 s, ~30 KB) e o
-- "último heartbeat" (ORDER BY reported_at DESC NULLS LAST, id DESC LIMIT 1), usado várias vezes em cada conferência de
-- ordem, varria a tabela inteira: cada prévia levava 12–36 s e o "Operar todos" da página Hoje ficava preso.
CREATE INDEX IF NOT EXISTS ix_heartbeat_latest ON heartbeat (component, reported_at DESC NULLS LAST, id DESC);
-- o primeiro saldo do dia/semana (perda do período): WHERE component = 'ea' AND account = ? AND reported_at >= ?
CREATE INDEX IF NOT EXISTS ix_heartbeat_account_time ON heartbeat (component, account, reported_at);
