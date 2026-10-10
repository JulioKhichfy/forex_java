//+------------------------------------------------------------------+
//| JevCandleExporter.mq5 — Jev Forex, passo 2                        |
//|                                                                  |
//| Exporta para Common\Files\jev\inbox:                              |
//|  - especificações dos símbolos (tick size/value, lotes, spread…)  |
//|    na partida e a cada N minutos (symbols_*.csv)                  |
//|  - histórico M1 por símbolo e mês, uma única vez (origin=HISTORY) |
//|  - a cada barra M1 fechada, as barras novas de todos os símbolos  |
//|    num só arquivo (origin=LIVE)                                   |
//|                                                                  |
//| Só barras FECHADAS são exportadas (a barra em formação nunca).    |
//| O estado (última barra exportada por símbolo) fica em jev\state:  |
//| reiniciar o Service retoma de onde parou, sem buracos.            |
//+------------------------------------------------------------------+
#property service
#property copyright   "Jev Forex"
#property version     "1.00"
#property description "Exporta candles M1 e especificações dos símbolos para Common\\Files\\jev\\inbox, em UTC."

#include <Jev/Csv.mqh>
#include <Jev/Symbols.mqh>

input string   InpFxSymbols     = "EURUSD,GBPUSD,USDJPY,USDCHF,AUDUSD,USDCAD,NZDUSD"; // Forex (nomes canônicos)
input string   InpMetalSymbols  = "XAUUSD";                 // Metais (só especificações por enquanto)
input string   InpCryptoSymbols = "BTCUSD";                 // Cripto (só especificações por enquanto)
input string   InpIndexSymbols  = "AUS200,DE30,FR40,HK50,JP225,STOXX50,UK100,US30,US500,USTEC,IN50"; // Índices
input string   InpStockSymbols1 = "AAPL,ABBV,ABT,ADBE,ADP,AMD,AMGN,AMT,AMZN,ATVI,AVGO,BA,BABA,BAC,BIIB,BMY,C,CHTR,CMCSA,CME,COST,CSCO,CSX,CVS,EA,EBAY,EQIX,F,FB,GILD,GOOGL,HD,IBM,INTC,INTU,ISRG,JNJ,JPM,KO,LIN,LLY,LMT,MA,MCD,MDLZ,MMM,MO"; // Ações (1/3)
input string   InpStockSymbols2 = "MRK,MS,MSFT,NFLX,NKE,NVDA,ORCL,PEP,PFE,PG,PM,PYPL,REGN,SBUX,T,TMO,TMUS,TSLA,UNH,UPS,V,VRTX,VZ,WFC,WMT,XOM,AMC,BB,BBBY,BEKE,BIDU,BILI,BRQS,BYND,CAN,EDU,FTNT,FUTU,IQ,JD,LI,NIO,NTES,PDD,RLX,TAL,TIGR,TME"; // Ações (2/3)
input string   InpStockSymbols3 = "TSM,VIPS,XPEV,YUMC,ZTO,META,SPCX"; // Ações (3/3)
input string   InpCandleMarkets = "fx,indices,stocks";      // Mercados com candles exportados (fx,metals,crypto,indices,stocks)
input string   InpSuffix        = "m";                      // Sufixo da corretora (Exness: m → EURUSDm)
input datetime InpHistoryFrom   = D'2016.01.01';            // Histórico M1 a partir de
input datetime InpHistoryFromOther = D'2022.01.01';         // Histórico M1 de índices e ações a partir de
input int      InpSpecMinutes   = 60;                       // Especificações a cada N minutos
input int      InpPollMs        = 1000;                     // Verificação de barra nova (ms)

#define EXPORTER "JevCandleExporter/1.0"
#define CANDLE_HEADER "market;symbol;broker_symbol;time_server;time_utc;open;high;low;close;tick_volume;spread_points;real_volume"

struct JevSym
  {
   string            canonical;
   string            broker;
   string            market;
   int               digits;
   bool              candles;
   bool              ready;     // histórico/recuperação em dia: só então as barras saem como LIVE
   datetime          last;      // última barra exportada (horário do servidor); 0 = histórico ainda não feito
   datetime          histFrom;  // início do próximo trecho de histórico a exportar
   datetime          nextTry;   // próxima tentativa de histórico (quando o MT5 ainda está baixando)
   int               failures;  // tentativas seguidas sem dados
   int               stalls;    // tentativas seguidas em que o histórico do terminal não recuou
   datetime          lastLog;   // último aviso de progresso do download (no máx. 1 por minuto)
   long              histBars;  // barras de histórico exportadas nesta execução
  };

JevSym g_syms[];

//+------------------------------------------------------------------+
void AddSymbols(const string list, const string market, const string &candleMarkets[], const datetime historyFrom)
  {
   string names[];
   int n = JevSplitList(list, names);
   for(int i = 0; i < n; i++)
     {
      string broker = JevBrokerSymbol(names[i], InpSuffix);
      if(!SymbolSelect(broker, true))
        {
         PrintFormat("JevCandleExporter: símbolo %s não existe nesta corretora (confira o sufixo \"%s\")",
                     broker, InpSuffix);
         continue;
        }
      int k = ArraySize(g_syms);
      ArrayResize(g_syms, k + 1);
      g_syms[k].canonical = names[i];
      g_syms[k].broker = broker;
      g_syms[k].market = market;
      g_syms[k].digits = (int)SymbolInfoInteger(broker, SYMBOL_DIGITS);
      g_syms[k].candles = JevListContains(candleMarkets, market);
      g_syms[k].ready = false;
      g_syms[k].last = (datetime)StringToInteger(JevStateRead("candles_last_" + names[i], "0"));
      g_syms[k].histFrom = g_syms[k].last > 0 ? g_syms[k].last + 60 : historyFrom;
      g_syms[k].nextTry = 0;
      g_syms[k].failures = 0;
      g_syms[k].stalls = 0;
      g_syms[k].lastLog = 0;
      g_syms[k].histBars = 0;
     }
  }

string D(const double v)
  {
   return DoubleToString(v, 10);
  }

//+------------------------------------------------------------------+
//| Especificações reais da corretora                                 |
//+------------------------------------------------------------------+
void ExportSpecs()
  {
   JevCsvFile f;
   if(!JevCsvOpen(f, JevFileName("symbols", "specs")))
      return;
   JevCsvHeader(f, JevMeta(EXPORTER, "SNAPSHOT", JevServerOffsetSeconds(), TimeGMT()));
   JevCsvHeader(f, "market;symbol;broker_symbol;digits;point;tick_size;tick_value;tick_value_profit;tick_value_loss;contract_size;volume_min;volume_step;volume_max;currency_base;currency_profit;currency_margin;account_currency;spread_points;stops_level;freeze_level;swap_long;swap_short;trade_mode;bid;ask");
   string account = AccountInfoString(ACCOUNT_CURRENCY);
   for(int i = 0; i < ArraySize(g_syms); i++)
     {
      string b = g_syms[i].broker;
      double tickSize = SymbolInfoDouble(b, SYMBOL_TRADE_TICK_SIZE);
      double tickValue = SymbolInfoDouble(b, SYMBOL_TRADE_TICK_VALUE);
      double vmin = SymbolInfoDouble(b, SYMBOL_VOLUME_MIN);
      double vstep = SymbolInfoDouble(b, SYMBOL_VOLUME_STEP);
      if(tickSize <= 0 || tickValue <= 0 || vmin <= 0 || vstep <= 0)
        {
         PrintFormat("JevCandleExporter: %s ainda sem cotação/especificação; fica para a próxima", b);
         continue;
        }
      string tradeMode = JevEnumText(EnumToString((ENUM_SYMBOL_TRADE_MODE)SymbolInfoInteger(b, SYMBOL_TRADE_MODE)),
                                     "SYMBOL_TRADE_MODE_");
      JevCsvRow(f, StringFormat("%s;%s;%s;%d;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%I64d;%I64d;%I64d;%s;%s;%s;%s;%s",
                                g_syms[i].market, g_syms[i].canonical, b, g_syms[i].digits,
                                D(SymbolInfoDouble(b, SYMBOL_POINT)), D(tickSize), D(tickValue),
                                D(SymbolInfoDouble(b, SYMBOL_TRADE_TICK_VALUE_PROFIT)),
                                D(SymbolInfoDouble(b, SYMBOL_TRADE_TICK_VALUE_LOSS)),
                                D(SymbolInfoDouble(b, SYMBOL_TRADE_CONTRACT_SIZE)),
                                D(vmin), D(vstep), D(SymbolInfoDouble(b, SYMBOL_VOLUME_MAX)),
                                SymbolInfoString(b, SYMBOL_CURRENCY_BASE),
                                SymbolInfoString(b, SYMBOL_CURRENCY_PROFIT),
                                SymbolInfoString(b, SYMBOL_CURRENCY_MARGIN), account,
                                SymbolInfoInteger(b, SYMBOL_SPREAD),
                                SymbolInfoInteger(b, SYMBOL_TRADE_STOPS_LEVEL),
                                SymbolInfoInteger(b, SYMBOL_TRADE_FREEZE_LEVEL),
                                D(SymbolInfoDouble(b, SYMBOL_SWAP_LONG)), D(SymbolInfoDouble(b, SYMBOL_SWAP_SHORT)),
                                tradeMode,
                                DoubleToString(SymbolInfoDouble(b, SYMBOL_BID), g_syms[i].digits),
                                DoubleToString(SymbolInfoDouble(b, SYMBOL_ASK), g_syms[i].digits)));
     }
   long rows = f.rows;
   if(rows == 0)
     {
      JevCsvAbort(f);
      return;
     }
   if(JevCsvCommit(f))
      PrintFormat("JevCandleExporter: especificações → %I64d símbolos", rows);
  }

//+------------------------------------------------------------------+
string CandleLine(const int i, const MqlRates &r, const int offset)
  {
   int dg = g_syms[i].digits;
   return StringFormat("%s;%s;%s;%s;%s;%s;%s;%s;%s;%I64d;%d;%I64d",
                       g_syms[i].market, g_syms[i].canonical, g_syms[i].broker,
                       JevIsoServer(r.time), JevIsoUtc(r.time - offset),
                       DoubleToString(r.open, dg), DoubleToString(r.high, dg),
                       DoubleToString(r.low, dg), DoubleToString(r.close, dg),
                       r.tick_volume, r.spread, r.real_volume);
  }

//+------------------------------------------------------------------+
//| Força o terminal a carregar barras M1 mais antigas que as que já  |
//| tem, pedindo blocos a partir da barra mais antiga (técnica da     |
//| documentação do MQL5, "Organizing Data Access": pedir por data    |
//| muito antiga só devolve 4401 e não dispara o download).           |
//| Trabalha no máximo budgetMs; devolve true se o histórico recuou.  |
//+------------------------------------------------------------------+
bool PullOlderBars(const string sym, const datetime target, const uint budgetMs)
  {
   datetime before = (datetime)SeriesInfoInteger(sym, PERIOD_M1, SERIES_FIRSTDATE);
   ulong start = GetTickCount64();
   datetime times[];
   while(!IsStopped() && GetTickCount64() - start < budgetMs)
     {
      if(!SeriesInfoInteger(sym, PERIOD_M1, SERIES_SYNCHRONIZED))
        {
         Sleep(5);
         continue;
        }
      int bars = Bars(sym, PERIOD_M1);
      if(CopyTime(sym, PERIOD_M1, bars, 100, times) > 0)
        {
         if(times[0] <= target)
            break;
        }
      else
         Sleep(10);
     }
   datetime after = (datetime)SeriesInfoInteger(sym, PERIOD_M1, SERIES_FIRSTDATE);
   return after > 0 && (before == 0 || after < before);
  }

void SaveLast(const int i, const datetime t)
  {
   g_syms[i].last = t;
   JevStateWrite("candles_last_" + g_syms[i].canonical, IntegerToString((long)t));
  }

//+------------------------------------------------------------------+
//| UM mês de histórico de UM símbolo (ou recuperação depois de um    |
//| tempo parado). Nunca espera: se o MT5 ainda está baixando o       |
//| histórico (erro 4401), marca nova tentativa e devolve o controle, |
//| para o modo ao vivo dos outros símbolos não atrasar.              |
//| Devolve true se exportou algo (o loop pode seguir sem dormir).    |
//+------------------------------------------------------------------+
bool HistoryStep(const int i)
  {
   string b = g_syms[i].broker;
   datetime now = TimeGMT();
   datetime forming = iTime(b, PERIOD_M1, 0);   // barra em formação: nunca exporta
   if(forming == 0)
     {
      g_syms[i].nextTry = now + 5;
      return false;
     }
   if(g_syms[i].histFrom >= forming)
     {
      g_syms[i].ready = true;
      PrintFormat("JevCandleExporter: %s em dia: %I64d barras de histórico exportadas nesta execução (até %s); "
                  "segue ao vivo", g_syms[i].canonical, g_syms[i].histBars, TimeToString(g_syms[i].last));
      return false;
     }
   datetime serverFirst = (datetime)SeriesInfoInteger(b, PERIOD_M1, SERIES_SERVER_FIRSTDATE);
   if(g_syms[i].last == 0 && serverFirst > g_syms[i].histFrom)
     {
      PrintFormat("JevCandleExporter: %s tem M1 no servidor só a partir de %s", b, TimeToString(serverFirst));
      g_syms[i].histFrom = serverFirst;
     }

   datetime from = g_syms[i].histFrom;
   MqlDateTime d;
   TimeToStruct(from, d);
   int ny = d.mon == 12 ? d.year + 1 : d.year;
   int nm = d.mon == 12 ? 1 : d.mon + 1;
   datetime monthEnd = StringToTime(StringFormat("%d.%02d.01 00:00", ny, nm));
   datetime to = MathMin(monthEnd, forming) - 1;

   //--- o terminal ainda não tem barras tão antigas: puxa mais histórico do servidor antes de pedir o mês
   datetime localFirst = (datetime)SeriesInfoInteger(b, PERIOD_M1, SERIES_FIRSTDATE);
   if(localFirst == 0 || localFirst > from)
     {
      bool moved = PullOlderBars(b, from, 500);
      datetime nowFirst = (datetime)SeriesInfoInteger(b, PERIOD_M1, SERIES_FIRSTDATE);
      g_syms[i].stalls = moved ? 0 : g_syms[i].stalls + 1;
      if(now - g_syms[i].lastLog >= 60)
        {
         PrintFormat("JevCandleExporter: baixando M1 de %s do servidor: o terminal já tem desde %s "
                     "(servidor informa desde %s; %d barras)", b, TimeToString(nowFirst),
                     TimeToString((datetime)SeriesInfoInteger(b, PERIOD_M1, SERIES_SERVER_FIRSTDATE)),
                     Bars(b, PERIOD_M1));
         g_syms[i].lastLog = now;
        }
      //--- primeira carga e o histórico parou de recuar há ~10 min: não há nada mais antigo no servidor
      if(g_syms[i].last == 0 && nowFirst > from && g_syms[i].stalls >= 120)
        {
         PrintFormat("JevCandleExporter: %s não tem M1 antes de %s no servidor; o histórico começa aí",
                     b, TimeToString(nowFirst));
         g_syms[i].histFrom = nowFirst;
         g_syms[i].stalls = 0;
         return true;
        }
      g_syms[i].nextTry = now + (moved ? 0 : 5);
      if(nowFirst == 0 || nowFirst > from)
         return moved;
     }

   MqlRates rates[];
   ResetLastError();
   int n = CopyRates(b, PERIOD_M1, from, to, rates);
   //--- dados ainda incompletos: tenta daqui a pouco
   if(n < 0 || !SeriesInfoInteger(b, PERIOD_M1, SERIES_SYNCHRONIZED))
     {
      int err = GetLastError();
      if(g_syms[i].failures % 60 == 0)
         PrintFormat("JevCandleExporter: aguardando o M1 de %s (%04d-%02d, erro %d)", b, d.year, d.mon, err);
      g_syms[i].failures++;
      g_syms[i].nextTry = now + 5;
      return false;
     }
   g_syms[i].failures = 0;
   if(n > 0)
     {
      int offset = JevServerOffsetSeconds();
      JevCsvFile f;
      if(!JevCsvOpen(f, JevFileName("candles", StringFormat("hist-%s-%04d-%02d", g_syms[i].canonical, d.year, d.mon))))
        {
         g_syms[i].nextTry = now + 5;
         return false;
        }
      JevCsvHeader(f, JevMeta(EXPORTER, "HISTORY", offset, TimeGMT()));
      JevCsvHeader(f, CANDLE_HEADER);
      datetime last = g_syms[i].last;
      for(int k = 0; k < n; k++)
        {
         if(rates[k].time <= g_syms[i].last || rates[k].time >= forming)
            continue;
         JevCsvRow(f, CandleLine(i, rates[k], offset));
         last = rates[k].time;
        }
      if(f.rows == 0)
         JevCsvAbort(f);
      else
        {
         long rows = f.rows;
         if(!JevCsvCommit(f))
           {
            g_syms[i].nextTry = now + 5;
            return false;
           }
         SaveLast(i, last);
         g_syms[i].histBars += rows;
        }
     }
   g_syms[i].histFrom = monthEnd;
   return true;
  }

//--- próximo símbolo atrasado cuja vez chegou (rodízio: um símbolo travado não segura os outros)
int g_historyCursor = 0;
bool HistoryStepAny()
  {
   int total = ArraySize(g_syms);
   datetime now = TimeGMT();
   for(int k = 0; k < total; k++)
     {
      int i = (g_historyCursor + k) % total;
      if(!g_syms[i].candles || g_syms[i].ready || g_syms[i].nextTry > now)
         continue;
      g_historyCursor = (i + 1) % total;
      return HistoryStep(i);
     }
   return false;
  }

//+------------------------------------------------------------------+
//| Barras recém-fechadas de todos os símbolos, num arquivo só        |
//+------------------------------------------------------------------+
void ExportLive()
  {
   JevCsvFile f;
   bool open = false;
   int offset = JevServerOffsetSeconds();
   datetime newLast[];
   ArrayResize(newLast, ArraySize(g_syms));
   for(int i = 0; i < ArraySize(g_syms); i++)
     {
      newLast[i] = 0;
      if(!g_syms[i].candles || !g_syms[i].ready || g_syms[i].last == 0)
         continue;
      datetime forming = iTime(g_syms[i].broker, PERIOD_M1, 0);
      if(forming == 0 || forming <= g_syms[i].last + 60)
         continue;
      MqlRates rates[];
      int n = CopyRates(g_syms[i].broker, PERIOD_M1, g_syms[i].last + 60, forming - 1, rates);
      if(n <= 0)
         continue;
      for(int k = 0; k < n; k++)
        {
         if(rates[k].time <= g_syms[i].last || rates[k].time >= forming)
            continue;
         if(!open)
           {
            if(!JevCsvOpen(f, JevFileName("candles", "live")))
               return;
            JevCsvHeader(f, JevMeta(EXPORTER, "LIVE", offset, TimeGMT()));
            JevCsvHeader(f, CANDLE_HEADER);
            open = true;
           }
         JevCsvRow(f, CandleLine(i, rates[k], offset));
         newLast[i] = rates[k].time;
        }
     }
   if(!open)
      return;
   if(f.rows == 0)
     {
      JevCsvAbort(f);
      return;
     }
   if(!JevCsvCommit(f))
      return;
   for(int i = 0; i < ArraySize(g_syms); i++)
      if(newLast[i] > 0)
         SaveLast(i, newLast[i]);
  }

//+------------------------------------------------------------------+
void OnStart()
  {
   string candleMarkets[];
   JevSplitList(InpCandleMarkets, candleMarkets);
   if(!JevWaitConnected())
      return;
   AddSymbols(InpFxSymbols, "fx", candleMarkets, InpHistoryFrom);
   AddSymbols(InpMetalSymbols, "metals", candleMarkets, InpHistoryFrom);
   AddSymbols(InpCryptoSymbols, "crypto", candleMarkets, InpHistoryFrom);
   AddSymbols(InpIndexSymbols, "indices", candleMarkets, InpHistoryFromOther);
   AddSymbols(InpStockSymbols1, "stocks", candleMarkets, InpHistoryFromOther);
   AddSymbols(InpStockSymbols2, "stocks", candleMarkets, InpHistoryFromOther);
   AddSymbols(InpStockSymbols3, "stocks", candleMarkets, InpHistoryFromOther);
   if(ArraySize(g_syms) == 0)
     {
      Print("JevCandleExporter: nenhum símbolo válido; confira as listas e o sufixo");
      return;
     }
   long maxBars = TerminalInfoInteger(TERMINAL_MAXBARS);
   PrintFormat("JevCandleExporter: conta %I64d em %s; servidor − UTC = %d s; %d símbolos; máx. barras no gráfico %I64d",
               AccountInfoInteger(ACCOUNT_LOGIN), AccountInfoString(ACCOUNT_SERVER), JevServerOffsetSeconds(),
               ArraySize(g_syms), maxBars);
   if(maxBars < 5000000)
      Print("JevCandleExporter: para o histórico M1 completo, ajuste Ferramentas → Opções → Gráficos → "
            "\"Máx. barras no gráfico\" para Ilimitado e reinicie o terminal");

   ExportSpecs();
   datetime lastSpecs = TimeGMT();

   //--- a cada volta: barras novas dos símbolos em dia + no máximo um mês de histórico de um atrasado
   while(!IsStopped())
     {
      ExportLive();
      bool progressed = HistoryStepAny();
      if(InpSpecMinutes > 0 && TimeGMT() - lastSpecs >= InpSpecMinutes * 60)
        {
         ExportSpecs();
         lastSpecs = TimeGMT();
        }
      if(!progressed)          // exportando histórico: segue sem dormir; senão espera o próximo ciclo
         Sleep(InpPollMs);
     }
   Print("JevCandleExporter: parado");
  }
//+------------------------------------------------------------------+
