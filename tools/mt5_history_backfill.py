"""
Carga histórica de índices e ações a partir do terminal MT5 que já está aberto (pacote oficial MetaTrader5).

Grava no MESMO contrato do JevCandleExporter (linha #meta + cabeçalho + linhas ';'), com origin=HISTORY,
numa pasta de entrada à parte: <saída>/inbox. Depois importe com o jar novo:

    java -jar jev-app/target/jev-app.jar import-mt5-once --mt5.common-files=<saída>

Uso:
    python tools/mt5_history_backfill.py --out C:/Users/julio/FOREX_JEV/mt5-backfill [--from 2022-01-01]
           [--markets indices,stocks] [--symbols US500,AAPL] [--terminal "C:/Program Files/MetaTrader 5 EXNESS/terminal64.exe"]

Não envia ordens nem altera nada no terminal além de adicionar os símbolos à Observação do Mercado.
"""
import argparse
import datetime as dt
import os
import sys
import time

import MetaTrader5 as mt5

INDICES = "AUS200,DE30,FR40,HK50,JP225,STOXX50,UK100,US30,US500,USTEC,IN50"
STOCKS = "AAPL,MSFT,NVDA,META,GOOGL,INTC,SPCX"   # ações em uso (experiment.market-symbols); o histórico das outras 95 já está no bronze
LISTS = {"indices": INDICES, "stocks": STOCKS, "metals": "XAUUSD", "crypto": "BTCUSD", "energy": "UKOIL,USOIL,XNGUSD"}
EXPORTER = "JevHistoryPy/1.0"
CANDLE_HEADER = ("market;symbol;broker_symbol;time_server;time_utc;open;high;low;close;tick_volume;spread_points;"
                 "real_volume")
SYMBOL_HEADER = ("market;symbol;broker_symbol;digits;point;tick_size;tick_value;tick_value_profit;tick_value_loss;"
                 "contract_size;volume_min;volume_step;volume_max;currency_base;currency_profit;currency_margin;"
                 "account_currency;spread_points;stops_level;freeze_level;swap_long;swap_short;trade_mode;bid;ask")
TRADE_MODES = {0: "DISABLED", 1: "LONGONLY", 2: "SHORTONLY", 3: "CLOSEONLY", 4: "FULL"}


def stamp(t):
    return t.strftime("%Y%m%dT%H%M%SZ")


def iso(t):
    return t.strftime("%Y-%m-%dT%H:%M:%SZ")


def write(inbox, kind, tag, meta, header, lines):
    """Escreve em .tmp e renomeia: o importador nunca vê arquivo pela metade."""
    now = dt.datetime.now(dt.timezone.utc)
    name = f"{kind}_{stamp(now)}_{tag}.csv"
    tmp = os.path.join(inbox, name + ".tmp")
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(meta + "\n" + header + "\n")
        for line in lines:
            f.write(line + "\n")
    os.replace(tmp, os.path.join(inbox, name))
    return name


def num(v, digits=None):
    if v is None:
        return ""
    if digits is not None:
        return f"{v:.{digits}f}"
    return repr(float(v))


def month_starts(start, end):
    m = dt.datetime(start.year, start.month, 1, tzinfo=dt.timezone.utc)
    while m < end:
        nxt = dt.datetime(m.year + (m.month // 12), m.month % 12 + 1, 1, tzinfo=dt.timezone.utc)
        yield m, min(nxt, end)
        m = nxt


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="pasta jev da carga (o inbox fica em <out>/inbox)")
    ap.add_argument("--from", dest="start", default="2022-01-01")
    ap.add_argument("--markets", default="indices,stocks")
    ap.add_argument("--symbols", default="", help="só estes (nomes canônicos)")
    ap.add_argument("--suffix", default="m")
    ap.add_argument("--offset", type=int, default=0, help="servidor − UTC em segundos (Exness: 0)")
    ap.add_argument("--terminal", default=r"C:\Program Files\MetaTrader 5 EXNESS\terminal64.exe")
    ap.add_argument("--specs-only", action="store_true", help="só o arquivo de especificações")
    a = ap.parse_args()

    inbox = os.path.join(a.out, "inbox")
    os.makedirs(inbox, exist_ok=True)
    if not mt5.initialize(path=a.terminal):
        sys.exit(f"Não consegui conectar ao terminal {a.terminal}: {mt5.last_error()}")
    acc = mt5.account_info()
    print(f"Conectado: conta {acc.login} em {acc.server} ({acc.currency})")
    seen = dt.datetime.now(dt.timezone.utc)
    meta = (f"#exporter={EXPORTER};server={acc.server};login={acc.login};offset_s={a.offset};origin=HISTORY;"
            f"seen_utc={iso(seen)}")
    wanted = set(s for s in a.symbols.split(",") if s)
    start = dt.datetime.fromisoformat(a.start).replace(tzinfo=dt.timezone.utc)
    end = (seen - dt.timedelta(minutes=2)).replace(second=0, microsecond=0)   # só barras fechadas

    plan = []
    for market in [m for m in a.markets.split(",") if m]:
        for sym in LISTS[market].split(","):
            if wanted and sym not in wanted:
                continue
            plan.append((market, sym, sym + a.suffix))

    # ---------------------------------------------------------------- especificações (um arquivo)
    spec_lines, ok = [], []
    for market, sym, broker in plan:
        if not mt5.symbol_select(broker, True):
            print(f"  {broker}: não existe nesta corretora — ignorado")
            continue
        i = mt5.symbol_info(broker)
        if i is None:
            print(f"  {broker}: sem informações — ignorado")
            continue
        ok.append((market, sym, broker, i.digits))
        if not (i.trade_tick_size > 0 and i.trade_tick_value > 0 and i.volume_min > 0):
            # o importador recusa o arquivo inteiro com spec zerada (símbolo sem cotação agora): fica de fora
            print(f"  {broker}: especificação zerada (sem cotação agora?) — fora do arquivo de especificações")
            continue
        spec_lines.append(";".join([
            market, sym, broker, str(i.digits), num(i.point), num(i.trade_tick_size), num(i.trade_tick_value),
            num(i.trade_tick_value_profit), num(i.trade_tick_value_loss), num(i.trade_contract_size),
            num(i.volume_min), num(i.volume_step), num(i.volume_max), i.currency_base or "", i.currency_profit or "",
            i.currency_margin or "", acc.currency, str(i.spread), str(i.trade_stops_level),
            str(i.trade_freeze_level), num(i.swap_long), num(i.swap_short), TRADE_MODES.get(i.trade_mode, str(i.trade_mode)),
            num(i.bid, i.digits), num(i.ask, i.digits)]))
    if spec_lines:
        print("Especificações:", write(inbox, "symbols", "pyhist-specs", meta, SYMBOL_HEADER, spec_lines))
    if a.specs_only:
        mt5.shutdown()
        return

    # ---------------------------------------------------------------- candles M1, um arquivo por símbolo e mês
    total = 0
    for market, sym, broker, digits in ok:
        n_sym, first = 0, None
        for m0, m1 in month_starts(start, end):
            rates = None
            for attempt in range(4):   # o terminal baixa o histórico sob demanda: insiste até estabilizar
                r = mt5.copy_rates_range(broker, mt5.TIMEFRAME_M1, m0, m1)
                if r is not None and len(r) > 0 and rates is not None and len(r) == len(rates):
                    break
                rates = r
                time.sleep(0.5 if attempt == 0 else 2)
            if rates is None or len(rates) == 0:
                continue
            lines = []
            for b in rates:
                t = dt.datetime.fromtimestamp(int(b["time"]), dt.timezone.utc)
                if t >= end:
                    continue
                tu = t - dt.timedelta(seconds=a.offset)
                lines.append(";".join([market, sym, broker, t.strftime("%Y-%m-%dT%H:%M:%S"), iso(tu),
                                       num(b["open"], digits), num(b["high"], digits), num(b["low"], digits),
                                       num(b["close"], digits), str(int(b["tick_volume"])), str(int(b["spread"])),
                                       str(int(b["real_volume"]))]))
            if lines:
                write(inbox, "candles", f"pyhist-{sym}-{m0:%Y%m}", meta, CANDLE_HEADER, lines)
                n_sym += len(lines)
                first = first or lines[0].split(";")[4]
        total += n_sym
        print(f"  {market:7s} {sym:8s} {n_sym:>9,} barras desde {first or '—'}")
    mt5.shutdown()
    print(f"Pronto: {len(ok)} símbolos, {total:,} barras em {inbox}")


if __name__ == "__main__":
    main()
