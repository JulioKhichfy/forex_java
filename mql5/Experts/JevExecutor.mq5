//+------------------------------------------------------------------+
//| JevExecutor.mq5 — Jev Forex, passo 6c: executor                   |
//|                                                                  |
//| Executa as ordens que VOCÊ aprovou no dashboard (o Java confere  |
//| todas as travas antes). Este EA:                                 |
//|  - busca ordens e comandos em GET /api/ea/signals a cada segundo  |
//|  - abre a posição já com SL e TP no servidor da corretora         |
//|    (distâncias aplicadas sobre o preço real da execução)          |
//|  - fecha posições pedidas (botão, saída por tempo) e obedece      |
//|    BLOCK (sem entradas novas) e FLATTEN (fecha tudo deste EA)     |
//|  - reporta cada execução (POST /api/ea/execution) e envia         |
//|    heartbeat com saldo, margem, cotações e posições               |
//|                                                                  |
//| TRÊS chaves para enviar ordens: InpExecute = true, o sistema em   |
//| DEMO ou LIVE, e o modo batendo com a conta (LIVE só em conta      |
//| real, DEMO só em demo). Faltou uma: registra e recusa.           |
//| Só mexe em posições com o magic deste EA. Nunca tenta de novo     |
//| sozinho: falhou, reporta como recusada.                           |
//|                                                                  |
//| Anexe a UM gráfico qualquer (ex.: EURUSDm M1) e marque            |
//| "Permitir Algo Trading". Libere a URL: Ferramentas → Opções →     |
//| Expert Advisors → "Permitir WebRequest…" → http://127.0.0.1:8080  |
//+------------------------------------------------------------------+
#property copyright   "Jev Forex"
#property version     "2.00"
#property description "Jev Forex — executor das ordens aprovadas no dashboard, com SL/TP no servidor."

#include <Trade/Trade.mqh>
#include <Jev/Csv.mqh>
#include <Jev/Http.mqh>
#include <Jev/Symbols.mqh>

input string InpApi              = "http://127.0.0.1:8080"; // API local (use 127.0.0.1, não localhost)
input long   InpMagic            = 770001;                  // Magic number do mercado fx
input string InpSuffix           = "m";                     // Sufixo da corretora (Exness: m)
input bool   InpExecute          = false;                   // ENVIAR ORDENS (false = só registra e recusa)
input double InpMaxLot           = 0.05;                    // Teto de lote do próprio EA, peça o Java o que pedir
input string InpSymbols          = "EURUSD,GBPUSD,USDJPY,USDCHF,AUDUSD,USDCAD,NZDUSD,AUS200,DE30,FR40,HK50,JP225,STOXX50,UK100,US30,US500,USTEC,IN50"; // Cotações no heartbeat: forex e índices
input string InpSymbols2         = "AAPL,ABBV,ABT,ADBE,ADP,AMD,AMGN,AMT,AMZN,AVGO,BA,BABA,BAC,BIIB,BMY,C,CHTR,CMCSA,CME,COST,CSCO,CSX,CVS,EA,EBAY,EQIX,F,GILD,GOOGL,HD,IBM,INTC,INTU,ISRG,JNJ,JPM,KO,LIN,LLY,LMT,MA,MCD,MDLZ,MMM,MO,MRK,MS"; // Cotações: ações (1/3)
input string InpSymbols3         = "MSFT,NFLX,NKE,NVDA,ORCL,PEP,PFE,PG,PM,PYPL,REGN,SBUX,T,TMO,TMUS,TSLA,UNH,UPS,V,VRTX,VZ,WFC,WMT,XOM,AMC,BB,BEKE,BIDU,BILI,BRQS,BYND,CAN,EDU,FTNT,FUTU,IQ,JD,LI,NIO,NTES,PDD,RLX,TAL,TIGR,TME,TSM,VIPS"; // Cotações: ações (2/3)
input string InpSymbols4         = "XPEV,YUMC,ZTO,META,SPCX"; // Cotações: ações (3/3)
input int    InpPollSeconds      = 1;                       // Busca de ordens (s)
input int    InpHeartbeatSeconds = 5;                       // Heartbeat (s)

#define EA_VERSION "JevExecutor/2.0"
#define MAX_SEEN   500

CTrade   g_trade;
string   g_mode = "";
string   g_cmd = "";
datetime g_lastPoll = 0;
datetime g_lastHeartbeat = 0;
datetime g_lastFlatten = 0;
bool     g_apiOk = true;
bool     g_urlWarned = false;
string   g_lastHeartbeatReply = "OK";
string   g_seen[];
string   g_symbols[];

//+------------------------------------------------------------------+
string TradeModeText()
  {
   return JevEnumText(EnumToString((ENUM_ACCOUNT_TRADE_MODE)AccountInfoInteger(ACCOUNT_TRADE_MODE)),
                      "ACCOUNT_TRADE_MODE_");
  }

bool AlgoEnabled()
  {
   return TerminalInfoInteger(TERMINAL_TRADE_ALLOWED) && MQLInfoInteger(MQL_TRADE_ALLOWED);
  }

string Canonical(const string broker)
  {
   int n = StringLen(broker), k = StringLen(InpSuffix);
   if(k > 0 && n > k && StringSubstr(broker, n - k) == InpSuffix)
      return StringSubstr(broker, 0, n - k);
   return broker;
  }

//--- as três chaves; devolve "" se pode enviar, senão o motivo
string ExecuteBlockedReason()
  {
   if(!InpExecute)
      return "InpExecute=false (o EA só registra)";
   if(g_mode != "DEMO" && g_mode != "LIVE")
      return "sistema em " + g_mode + " (ordens só em DEMO ou LIVE)";
   string acc = TradeModeText();
   if(g_mode == "DEMO" && acc != "DEMO")
      return "sistema em DEMO mas a conta é " + acc;
   if(g_mode == "LIVE" && acc != "REAL")
      return "sistema em LIVE mas a conta é " + acc;
   if(!AlgoEnabled())
      return "Algo Trading desligado no terminal";
   if(!TerminalInfoInteger(TERMINAL_CONNECTED))
      return "terminal sem conexão com a corretora";
   return "";
  }

//+------------------------------------------------------------------+
int OnInit()
  {
   if(!EventSetTimer(1))
     {
      Print("JevExecutor: não consegui criar o timer");
      return INIT_FAILED;
     }
   JevSplitList(InpSymbols + "," + InpSymbols2 + "," + InpSymbols3 + "," + InpSymbols4, g_symbols);
   g_trade.SetExpertMagicNumber(InpMagic);
   g_trade.SetAsyncMode(false);
   PrintFormat("JevExecutor %s iniciado: conta %I64d em %s (%s), API %s, %s, teto %.2f lote.", EA_VERSION,
               AccountInfoInteger(ACCOUNT_LOGIN), AccountInfoString(ACCOUNT_SERVER), TradeModeText(), InpApi,
               InpExecute ? "ENVIO DE ORDENS LIGADO" : "envio desligado (só registra)", InpMaxLot);
   return INIT_SUCCEEDED;
  }

void OnDeinit(const int reason)
  {
   EventKillTimer();
   Print("JevExecutor: parado (posições abertas continuam com SL/TP no servidor)");
  }

void OnTimer()
  {
   datetime now = TimeGMT();
   if(now - g_lastPoll >= InpPollSeconds)
     {
      g_lastPoll = now;
      PollSignals();
     }
   if(g_cmd == "FLATTEN" && now - g_lastFlatten >= 2)
     {
      g_lastFlatten = now;
      FlattenAll();
     }
   if(now - g_lastHeartbeat >= InpHeartbeatSeconds)
     {
      g_lastHeartbeat = now;
      SendHeartbeat();
     }
  }

//+------------------------------------------------------------------+
//| Falha de comunicação: registra só na mudança de estado (sem spam) |
//+------------------------------------------------------------------+
void ApiFailure(const int code)
  {
   int err = GetLastError();
   if(code == -1 && err == JEV_ERR_URL_NOT_ALLOWED)
     {
      if(!g_urlWarned)
         PrintFormat("JevExecutor: a URL %s não está liberada. Ferramentas → Opções → Expert Advisors → "
                     "\"Permitir WebRequest para as URLs listadas\" → adicione %s", InpApi, InpApi);
      g_urlWarned = true;
      return;
     }
   if(g_apiOk)
      PrintFormat("JevExecutor: API fora (HTTP %d, erro %d). Sem API, nenhuma ordem nova; posições seguem "
                  "com SL/TP no servidor.", code, err);
   g_apiOk = false;
  }

void ApiOk()
  {
   if(!g_apiOk)
      Print("JevExecutor: API de volta");
   g_apiOk = true;
  }

//+------------------------------------------------------------------+
void PollSignals()
  {
   string url = StringFormat("%s/api/ea/signals?account=%I64d&server=%s", InpApi,
                             AccountInfoInteger(ACCOUNT_LOGIN), JevUrlEncode(AccountInfoString(ACCOUNT_SERVER)));
   string body;
   int code = JevHttpGet(url, body);
   if(code != 200)
     {
      ApiFailure(code);
      return;
     }
   ApiOk();
   string lines[];
   int n = StringSplit(body, '\n', lines);
   for(int i = 0; i < n; i++)
     {
      string line = lines[i];
      StringTrimLeft(line);
      StringTrimRight(line);
      if(line == "")
         continue;
      string f[];
      int k = StringSplit(line, ';', f);
      if(k == 2 && f[0] == "MODE")
        {
         if(f[1] != g_mode)
            PrintFormat("JevExecutor: modo do sistema = %s", f[1]);
         g_mode = f[1];
        }
      else
         if(k == 2 && f[0] == "CMD")
           {
            if(f[1] != g_cmd)
              {
               if(f[1] == "BLOCK")
                  Print("JevExecutor: BLOCK — nenhuma entrada nova (fechamentos continuam)");
               else
                  if(f[1] == "FLATTEN")
                     Print("JevExecutor: FLATTEN — fechando todas as posições deste EA");
                  else
                     if(g_cmd != "")
                        PrintFormat("JevExecutor: comando = %s", f[1]);
              }
            g_cmd = f[1];
           }
         else
            if(k == 10 && f[0] == "OPEN")
               ExecuteOpen(f);
            else
               if(k == 5 && f[0] == "CLOSE")
                  ExecuteClose(f);
               else
                  PrintFormat("JevExecutor: linha ignorada (formato desconhecido): %s", line);
     }
  }

//+------------------------------------------------------------------+
//| "2026-10-14T12:32:35Z" → datetime UTC                              |
//+------------------------------------------------------------------+
datetime ParseIsoUtc(string s)
  {
   StringReplace(s, "-", ".");
   StringReplace(s, "T", " ");
   StringReplace(s, "Z", "");
   return StringToTime(s);
  }

bool AlreadySeen(const string id)
  {
   for(int i = 0; i < ArraySize(g_seen); i++)
      if(g_seen[i] == id)
         return true;
   if(ArraySize(g_seen) >= MAX_SEEN)
      ArrayRemove(g_seen, 0, 1);
   int k = ArraySize(g_seen);
   ArrayResize(g_seen, k + 1);
   g_seen[k] = id;
   return false;
  }

//+------------------------------------------------------------------+
//| OPEN;id;símbolo;lado;volume;dist_sl;dist_tp;desvio;expira;fechar_após
//+------------------------------------------------------------------+
void ExecuteOpen(const string &f[])
  {
   string id = f[1];
   if(AlreadySeen(id))
      return;
   string broker = JevBrokerSymbol(f[2], InpSuffix);
   string side = f[3];
   double volume = StringToDouble(f[4]);
   double slDist = StringToDouble(f[5]);
   double tpDist = StringToDouble(f[6]);
   int deviation = (int)StringToInteger(f[7]);
   datetime expires = ParseIsoUtc(f[8]);
   string reason = "";

   if(g_cmd == "BLOCK" || g_cmd == "FLATTEN")
      reason = "comando " + g_cmd + " ativo";
   else
      if(!SymbolSelect(broker, true))
         reason = "símbolo " + broker + " inexistente";
      else
         if(side != "BUY" && side != "SELL")
            reason = "lado inválido " + side;
         else
            if(expires <= TimeGMT())
               reason = "ordem vencida (" + f[8] + ")";
            else
               if(!(slDist > 0) || !(tpDist > 0))
                  reason = "sem stop ou alvo";
   if(reason == "")
     {
      double vmin = SymbolInfoDouble(broker, SYMBOL_VOLUME_MIN);
      double vmax = MathMin(SymbolInfoDouble(broker, SYMBOL_VOLUME_MAX), InpMaxLot);
      double step = SymbolInfoDouble(broker, SYMBOL_VOLUME_STEP);
      double point = SymbolInfoDouble(broker, SYMBOL_POINT);
      long stopsLevel = SymbolInfoInteger(broker, SYMBOL_TRADE_STOPS_LEVEL);
      if(volume < vmin - 1e-9 || volume > vmax + 1e-9 || MathAbs(volume / step - MathRound(volume / step)) > 1e-6)
         reason = StringFormat("volume %.2f fora de [%.2f, %.2f] passo %.2f (InpMaxLot %.2f)", volume, vmin, vmax,
                               step, InpMaxLot);
      else
         if(stopsLevel > 0 && slDist < stopsLevel * point)
            reason = StringFormat("stop de %s abaixo do mínimo da corretora (%I64d pontos)", f[5], stopsLevel);
     }
   if(reason == "")
      reason = ExecuteBlockedReason();
   if(reason != "")
     {
      PrintFormat("JevExecutor: recusei a ordem %s (%s %s %.2f): %s", id, side, broker, volume, reason);
      ReportExecution(id, "REJECTED", 0, 0, reason);
      return;
     }

   int digits = (int)SymbolInfoInteger(broker, SYMBOL_DIGITS);
   double price = side == "BUY" ? SymbolInfoDouble(broker, SYMBOL_ASK) : SymbolInfoDouble(broker, SYMBOL_BID);
   double sl = NormalizeDouble(side == "BUY" ? price - slDist : price + slDist, digits);
   double tp = NormalizeDouble(side == "BUY" ? price + tpDist : price - tpDist, digits);
   g_trade.SetDeviationInPoints(deviation);
   g_trade.SetTypeFillingBySymbol(broker);
   string comment = "jev#" + id;
   bool sent = side == "BUY" ? g_trade.Buy(volume, broker, 0.0, sl, tp, comment)
               : g_trade.Sell(volume, broker, 0.0, sl, tp, comment);
   uint rc = g_trade.ResultRetcode();
   if(sent && (rc == TRADE_RETCODE_DONE || rc == TRADE_RETCODE_DONE_PARTIAL))
     {
      ulong ticket = g_trade.ResultOrder();   // conta hedge: o ticket da posição é o da ordem que a abriu
      PrintFormat("JevExecutor: ordem %s executada — %s %s %.2f a %s, SL %s, TP %s, posição %I64u", id, side, broker,
                  volume, DoubleToString(g_trade.ResultPrice(), digits), DoubleToString(sl, digits),
                  DoubleToString(tp, digits), ticket);
      ReportExecution(id, "FILLED", ticket, g_trade.ResultPrice(),
                      StringFormat("SL %s TP %s", DoubleToString(sl, digits), DoubleToString(tp, digits)));
     }
   else
     {
      string msg = StringFormat("corretora recusou: %u %s", rc, g_trade.ResultRetcodeDescription());
      PrintFormat("JevExecutor: ordem %s NÃO executada — %s", id, msg);
      ReportExecution(id, "REJECTED", 0, 0, msg);
     }
  }

//+------------------------------------------------------------------+
//| CLOSE;id;ticket;desvio;expira — fechar nunca é bloqueado por BLOCK  |
//+------------------------------------------------------------------+
void ExecuteClose(const string &f[])
  {
   string id = f[1];
   if(AlreadySeen(id))
      return;
   ulong ticket = (ulong)StringToInteger(f[2]);
   int deviation = (int)StringToInteger(f[3]);
   string reason = ExecuteBlockedReason();
   if(reason == "" && !PositionSelectByTicket(ticket))
      reason = StringFormat("posição %I64u não está aberta", ticket);
   if(reason == "" && PositionGetInteger(POSITION_MAGIC) != InpMagic)
      reason = StringFormat("posição %I64u não é deste EA (magic diferente)", ticket);
   if(reason != "")
     {
      PrintFormat("JevExecutor: não fechei %I64u (ordem %s): %s", ticket, id, reason);
      ReportExecution(id, "REJECTED", ticket, 0, reason);
      return;
     }
   g_trade.SetDeviationInPoints(deviation);
   if(g_trade.PositionClose(ticket, deviation)
      && (g_trade.ResultRetcode() == TRADE_RETCODE_DONE || g_trade.ResultRetcode() == TRADE_RETCODE_DONE_PARTIAL))
     {
      PrintFormat("JevExecutor: posição %I64u fechada a %s (ordem %s)", ticket,
                  DoubleToString(g_trade.ResultPrice(), _Digits), id);
      ReportExecution(id, "FILLED", ticket, g_trade.ResultPrice(), "fechada");
     }
   else
     {
      string msg = StringFormat("corretora recusou: %u %s", g_trade.ResultRetcode(), g_trade.ResultRetcodeDescription());
      PrintFormat("JevExecutor: falhou fechar %I64u — %s", ticket, msg);
      ReportExecution(id, "REJECTED", ticket, 0, msg);
     }
  }

//+------------------------------------------------------------------+
//| FLATTEN: fecha todas as posições com o magic deste EA             |
//+------------------------------------------------------------------+
void FlattenAll()
  {
   if(ExecuteBlockedReason() != "")
      return;
   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0 || PositionGetInteger(POSITION_MAGIC) != InpMagic)
         continue;
      if(g_trade.PositionClose(ticket))
         PrintFormat("JevExecutor: FLATTEN fechou %I64u", ticket);
      else
         PrintFormat("JevExecutor: FLATTEN não fechou %I64u: %u %s", ticket, g_trade.ResultRetcode(),
                     g_trade.ResultRetcodeDescription());
     }
  }

//+------------------------------------------------------------------+
void ReportExecution(const string id, const string status, const ulong ticket, const double price, const string msg)
  {
   string json = StringFormat("{\"id\":%s,\"status\":\"%s\",\"ticket\":%I64u,\"price\":%s,\"message\":\"%s\"}",
                              id, status, ticket, DoubleToString(price, 8), JevJsonEscape(msg));
   string reply;
   int code = JevHttpPostJson(InpApi + "/api/ea/execution", json, reply);
   if(code != 200)
      PrintFormat("JevExecutor: não consegui reportar a ordem %s (HTTP %d). Confira no MT5.", id, code);
  }

//+------------------------------------------------------------------+
string QuotesJson()
  {
   string out = "";
   for(int i = 0; i < ArraySize(g_symbols); i++)
     {
      string broker = JevBrokerSymbol(g_symbols[i], InpSuffix);
      MqlTick t;
      if(!SymbolSelect(broker, true) || !SymbolInfoTick(broker, t))
         continue;
      int digits = (int)SymbolInfoInteger(broker, SYMBOL_DIGITS);
      if(out != "")
         out += ",";
      out += StringFormat("{\"symbol\":\"%s\",\"bid\":%s,\"ask\":%s,\"spread\":%I64d,\"time\":\"%s\"}",
                          g_symbols[i], DoubleToString(t.bid, digits), DoubleToString(t.ask, digits),
                          SymbolInfoInteger(broker, SYMBOL_SPREAD),
                          JevIsoUtc((datetime)(t.time - JevServerOffsetSeconds())));
     }
   return "[" + out + "]";
  }

string PositionsJson(int &count)
  {
   string out = "";
   count = 0;
   for(int i = 0; i < PositionsTotal(); i++)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0 || PositionGetInteger(POSITION_MAGIC) != InpMagic)
         continue;
      string broker = PositionGetString(POSITION_SYMBOL);
      int digits = (int)SymbolInfoInteger(broker, SYMBOL_DIGITS);
      bool buy = PositionGetInteger(POSITION_TYPE) == POSITION_TYPE_BUY;
      if(out != "")
         out += ",";
      out += StringFormat("{\"ticket\":%I64u,\"symbol\":\"%s\",\"side\":\"%s\",\"volume\":%s,\"price\":%s,"
                          "\"sl\":%s,\"tp\":%s,\"profit\":%s,\"magic\":%I64d,\"time\":\"%s\"}",
                          ticket, Canonical(broker), buy ? "BUY" : "SELL",
                          DoubleToString(PositionGetDouble(POSITION_VOLUME), 2),
                          DoubleToString(PositionGetDouble(POSITION_PRICE_OPEN), digits),
                          DoubleToString(PositionGetDouble(POSITION_SL), digits),
                          DoubleToString(PositionGetDouble(POSITION_TP), digits),
                          DoubleToString(PositionGetDouble(POSITION_PROFIT) + PositionGetDouble(POSITION_SWAP), 2),
                          PositionGetInteger(POSITION_MAGIC),
                          JevIsoUtc((datetime)(PositionGetInteger(POSITION_TIME) - JevServerOffsetSeconds())));
      count++;
     }
   return "[" + out + "]";
  }

void SendHeartbeat()
  {
   int mine = 0;
   string open = PositionsJson(mine);
   string json = StringFormat(
                    "{\"account\":%I64d,\"server\":\"%s\",\"company\":\"%s\",\"trade_mode\":\"%s\","
                    "\"ea_mode\":\"%s\",\"ea_version\":\"%s\",\"equity\":%s,\"balance\":%s,\"currency\":\"%s\","
                    "\"margin\":%s,\"margin_free\":%s,\"margin_level\":%s,"
                    "\"leverage\":%I64d,\"positions\":%d,\"algo_enabled\":%s,\"connected\":%s,"
                    "\"terminal_build\":%d,\"suffix\":\"%s\",\"magic\":%I64d,\"cmd\":\"%s\",\"max_lot\":%s,"
                    "\"time\":\"%s\",\"quotes\":%s,\"open\":%s}",
                    AccountInfoInteger(ACCOUNT_LOGIN),
                    JevJsonEscape(AccountInfoString(ACCOUNT_SERVER)),
                    JevJsonEscape(AccountInfoString(ACCOUNT_COMPANY)),
                    TradeModeText(), InpExecute ? "EXECUTE" : "RECORD_ONLY", EA_VERSION,
                    DoubleToString(AccountInfoDouble(ACCOUNT_EQUITY), 2),
                    DoubleToString(AccountInfoDouble(ACCOUNT_BALANCE), 2),
                    AccountInfoString(ACCOUNT_CURRENCY),
                    DoubleToString(AccountInfoDouble(ACCOUNT_MARGIN), 2),
                    DoubleToString(AccountInfoDouble(ACCOUNT_MARGIN_FREE), 2),
                    DoubleToString(AccountInfoDouble(ACCOUNT_MARGIN_LEVEL), 1),
                    AccountInfoInteger(ACCOUNT_LEVERAGE),
                    mine,
                    AlgoEnabled() ? "true" : "false",
                    TerminalInfoInteger(TERMINAL_CONNECTED) ? "true" : "false",
                    (int)TerminalInfoInteger(TERMINAL_BUILD),
                    JevJsonEscape(InpSuffix), InpMagic, g_cmd, DoubleToString(InpMaxLot, 2),
                    JevIsoUtc(TimeGMT()), QuotesJson(), open);
   string reply;
   int code = JevHttpPostJson(InpApi + "/api/ea/heartbeat", json, reply);
   if(code != 200)
     {
      ApiFailure(code);
      return;
     }
   ApiOk();
   StringTrimRight(reply);
   if(reply != g_lastHeartbeatReply)
      PrintFormat("JevExecutor: heartbeat → %s", reply);
   g_lastHeartbeatReply = reply;
  }
//+------------------------------------------------------------------+
