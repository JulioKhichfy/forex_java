-- Baleias no bitcoin (Radar, 25/10/2026): minutos dos futuros da Binance com compra ou venda a mercado muito acima do
-- normal e quase toda de um lado (WhaleRule). Guardados para o Radar e para estudo depois.
CREATE TABLE whale_event (
    id           BIGSERIAL PRIMARY KEY,
    source       TEXT             NOT NULL,              -- ex.: binance:BTCUSDT
    symbol       TEXT             NOT NULL,              -- o símbolo no sistema (BTCUSD)
    minute_utc   TIMESTAMPTZ      NOT NULL,              -- início do minuto
    side         TEXT             NOT NULL,              -- BUY (compra agressiva) | SELL
    volume       DOUBLE PRECISION NOT NULL,              -- o lado dominante, na moeda base
    notional_usd DOUBLE PRECISION NOT NULL,
    multiple     DOUBLE PRECISION NOT NULL,              -- × o volume normal do minuto (mediana 24 h)
    imbalance    DOUBLE PRECISION NOT NULL,
    move_bps     DOUBLE PRECISION NOT NULL,              -- variação do minuto a favor do lado
    continues    BOOLEAN          NOT NULL,              -- o minuto anterior também foi baleia (a mesma onda)
    created_at   TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT uq_whale_event UNIQUE (source, minute_utc)
);
CREATE INDEX ix_whale_event_minute ON whale_event (minute_utc DESC);
