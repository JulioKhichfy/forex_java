//+------------------------------------------------------------------+
//| JevExecutor.mq5 — Jev Forex, passo 2: SOMENTE SHADOW              |
//|                                                                  |
//| Este EA NÃO envia ordens: não inclui Trade.mqh nem chama          |
//| OrderSend. Ele:                                                  |
//|  - busca sinais e comandos em GET /api/ea/signals a cada segundo  |
//|  - valida cada sinal (símbolo, lado, volume, validade, SL/TP) e   |
//|    registra no log o que FARIA                                    |
//|  - obedece/registra BLOCK e FLATTEN                               |
//|  - envia heartbeat a cada 10 s (POST /api/ea/heartbeat)           |
//| O envio real de ordens (com SL/TP no servidor) chega no passo 6.  |
//|                                                                  |
//| Anexe a UM gráfico qualquer (ex.: EURUSDm M1).                    |
//| Libere a URL: Ferramentas → Opções → Expert Advisors →             |
//| "Permitir WebRequest para as URLs listadas" → http://127.0.0.1:8080
//+------------------------------------------------------------------+
#property copyright   "Jev Forex"
#property version     "1.00"
#property description "Jev Forex — passo 2: SHADOW. Lê e valida sinais, envia heartbeat. Nunca envia ordens."

#include <Jev/Csv.mqh>
#include <Jev/Http.mqh>

input string InpApi              = "http://127.0.0.1:8080"; // API local (use 127.0.0.1, não localhost)
input long   InpMagic            = 770001;                  // Magic number do mercado fx
input string InpSuffix           = "m";                     // Sufixo da corretora (Exness: m)
input int    InpPollSeconds      = 1;                       // Busca de sinais (s)
input int    InpHeartbeatSeconds = 10;                      // Heartbeat (s)

#define EA_VERSION "JevExecutor/1.0-shadow"
#define EA_MODE    "SHADOW"
#define MAX_SEEN   500

string   g_mode = "";
string   g_cmd = "";
datetime g_lastPoll = 0;
datetime g_lastHeartbeat = 0;
bool     g_apiOk = true;
bool     g_urlWarned = false;
string   g_lastHeartbeatReply = "OK";
string   g_seen[];

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

//+------------------------------------------------------------------+
int OnInit()
  {
   if(!EventSetTimer(1))
     {
      Print("JevExecutor: não consegui criar o timer");
      return INIT_FAILED;
     }
   PrintFormat("JevExecutor %s iniciado: conta %I64d em %s (%s), API %s. MODO SHADOW: nenhuma ordem será enviada.",
               EA_VERSION, AccountInfoInteger(ACCOUNT_LOGIN), AccountInfoString(ACCOUNT_SERVER), TradeModeText(),
               InpApi);
   if(TradeModeText() != "DEMO")
      Print("JevExecutor: ATENÇÃO — esta conta não é DEMO. Use a conta demo até o critério de saída do passo 6.");
   return INIT_SUCCEEDED;
  }

void OnDeinit(const int reason)
  {
   EventKillTimer();
   Print("JevExecutor: parado");
  }

void OnTimer()
  {
   datetime now = TimeGMT();
   if(now - g_lastPoll >= InpPollSeconds)
     {
      g_lastPoll = now;
      PollSignals();
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
      PrintFormat("JevExecutor: API fora (HTTP %d, erro %d). O Java está rodando em modo servidor? "
                  "Sem API, nada novo acontece.", code, err);
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
            PrintFormat("JevExecutor: modo do sistema = %s (este EA continua em SHADOW)", f[1]);
         g_mode = f[1];
        }
      else
         if(k == 2 && f[0] == "CMD")
           {
            if(f[1] != g_cmd)
              {
               if(f[1] == "BLOCK")
                  Print("JevExecutor: BLOCK — nenhuma entrada nova (posições seguem com SL/TP)");
               else
                  if(f[1] == "FLATTEN")
                     Print("JevExecutor: FLATTEN recebido — em SHADOW só registra (não fecha nada)");
                  else
                     if(g_cmd != "")
                        PrintFormat("JevExecutor: comando = %s", f[1]);
              }
            g_cmd = f[1];
           }
         else
            if(k == 9)
               ShadowSignal(f);
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
//| id;símbolo;lado;volume;sl;tp;desvio_pontos;expira_em_utc;fechar_após_utc
//| Valida como o executor real validaria e registra a decisão.       |
//+------------------------------------------------------------------+
void ShadowSignal(const string &f[])
  {
   string id = f[0];
   if(AlreadySeen(id))
      return;
   string broker = f[1] + InpSuffix;
   string side = f[2];
   double volume = StringToDouble(f[3]);
   double sl = StringToDouble(f[4]);
   double tp = StringToDouble(f[5]);
   datetime expires = ParseIsoUtc(f[7]);
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
               reason = "sinal vencido (" + f[7] + ")";
   if(reason == "")
     {
      double vmin = SymbolInfoDouble(broker, SYMBOL_VOLUME_MIN);
      double vmax = SymbolInfoDouble(broker, SYMBOL_VOLUME_MAX);
      double step = SymbolInfoDouble(broker, SYMBOL_VOLUME_STEP);
      double bid = SymbolInfoDouble(broker, SYMBOL_BID);
      double ask = SymbolInfoDouble(broker, SYMBOL_ASK);
      if(volume < vmin - 1e-9 || volume > vmax + 1e-9 || MathAbs(volume / step - MathRound(volume / step)) > 1e-6)
         reason = StringFormat("volume %.2f fora de [%.2f, %.2f] passo %.2f", volume, vmin, vmax, step);
      else
         if(side == "BUY" && !(sl < ask && ask < tp))
            reason = StringFormat("SL/TP incoerentes para BUY (sl %s, ask %s, tp %s)", f[4], DoubleToString(ask), f[5]);
         else
            if(side == "SELL" && !(tp < bid && bid < sl))
               reason = StringFormat("SL/TP incoerentes para SELL (tp %s, bid %s, sl %s)", f[5], DoubleToString(bid), f[4]);
     }
   if(reason == "")
      PrintFormat("[SHADOW] executaria %s: %s %s %.2f lote, SL %s, TP %s, desvio %s pts, fechar após %s",
                  id, side, broker, volume, f[4], f[5], f[6], f[8]);
   else
      PrintFormat("[SHADOW] recusaria %s (%s %s): %s", id, side, broker, reason);
  }

//+------------------------------------------------------------------+
void SendHeartbeat()
  {
   string json = StringFormat(
                    "{\"account\":%I64d,\"server\":\"%s\",\"company\":\"%s\",\"trade_mode\":\"%s\","
                    "\"ea_mode\":\"%s\",\"ea_version\":\"%s\",\"equity\":%s,\"balance\":%s,\"currency\":\"%s\","
                    "\"leverage\":%I64d,\"positions\":%d,\"algo_enabled\":%s,\"connected\":%s,"
                    "\"terminal_build\":%d,\"suffix\":\"%s\",\"magic\":%I64d,\"cmd\":\"%s\",\"time\":\"%s\"}",
                    AccountInfoInteger(ACCOUNT_LOGIN),
                    JevJsonEscape(AccountInfoString(ACCOUNT_SERVER)),
                    JevJsonEscape(AccountInfoString(ACCOUNT_COMPANY)),
                    TradeModeText(), EA_MODE, EA_VERSION,
                    DoubleToString(AccountInfoDouble(ACCOUNT_EQUITY), 2),
                    DoubleToString(AccountInfoDouble(ACCOUNT_BALANCE), 2),
                    AccountInfoString(ACCOUNT_CURRENCY),
                    AccountInfoInteger(ACCOUNT_LEVERAGE),
                    PositionsTotal(),
                    AlgoEnabled() ? "true" : "false",
                    TerminalInfoInteger(TERMINAL_CONNECTED) ? "true" : "false",
                    (int)TerminalInfoInteger(TERMINAL_BUILD),
                    JevJsonEscape(InpSuffix), InpMagic, g_cmd, JevIsoUtc(TimeGMT()));
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
