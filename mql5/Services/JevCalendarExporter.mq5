//+------------------------------------------------------------------+
//| JevCalendarExporter.mq5 — Jev Forex, passo 2                      |
//|                                                                  |
//| Exporta o calendário econômico do MT5 para Common\Files\jev\inbox:|
//|  1. dicionário de eventos das 8 moedas (calevents_*.csv)          |
//|  2. histórico por moeda e ano completo (origin=HISTORY)           |
//|  3. retrato desde a última execução até +N dias (origin=SNAPSHOT) |
//|  4. a cada segundo, as novidades de CalendarValueLast (origin=LIVE)
//|                                                                  |
//| Horários: o calendário vem no fuso do SERVIDOR; o arquivo leva o  |
//| horário do servidor, o UTC e o deslocamento usado.                |
//| Valores: inteiros ×10^6 e LONG_MIN = ausente, exatamente como o   |
//| MT5 entrega (bronze é o bruto; o Java decodifica).                |
//+------------------------------------------------------------------+
#property service
#property copyright   "Jev Forex"
#property version     "1.00"
#property description "Exporta o calendário econômico (8 moedas) para Common\\Files\\jev\\inbox, em UTC."

#include <Jev/Csv.mqh>
#include <Jev/Symbols.mqh>

input string   InpCurrencies      = "USD,EUR,GBP,JPY,CHF,AUD,CAD,NZD"; // Moedas
input datetime InpHistoryFrom     = D'2016.01.01';                     // Histórico a partir de
input int      InpLookaheadDays   = 14;                                // Dias à frente nos retratos
input int      InpSnapshotMinutes = 60;                                // Retrato periódico (min; 0 = desliga)
input int      InpPollMs          = 1000;                              // Intervalo do CalendarValueLast (ms)

#define EXPORTER "JevCalendarExporter/1.0"
#define CAL_HEADER "value_id;event_id;event_code;currency;country;importance;time_server;time_utc;period;revision;actual_raw;forecast_raw;prev_raw;revised_prev_raw;impact"

string g_currencies[];
//--- cache pequeno de países (id → código, moeda): são ~20 para as 8 moedas
ulong  g_countryId[];
string g_countryCode[];
string g_countryCur[];

//+------------------------------------------------------------------+
bool CountryOf(const ulong id, string &code, string &currency)
  {
   for(int i = 0; i < ArraySize(g_countryId); i++)
      if(g_countryId[i] == id)
        {
         code = g_countryCode[i];
         currency = g_countryCur[i];
         return true;
        }
   MqlCalendarCountry c;
   if(!CalendarCountryById(id, c))
      return false;
   int k = ArraySize(g_countryId);
   ArrayResize(g_countryId, k + 1);
   ArrayResize(g_countryCode, k + 1);
   ArrayResize(g_countryCur, k + 1);
   g_countryId[k] = id;
   g_countryCode[k] = c.code;
   g_countryCur[k] = c.currency;
   code = c.code;
   currency = c.currency;
   return true;
  }

//+------------------------------------------------------------------+
//| Escreve valores num arquivo; devolve quantas linhas entraram      |
//+------------------------------------------------------------------+
long WriteValues(JevCsvFile &f, const MqlCalendarValue &vals[], const int offset)
  {
   long before = f.rows;
   for(int i = 0; i < ArraySize(vals); i++)
     {
      MqlCalendarEvent ev;
      if(!CalendarEventById(vals[i].event_id, ev))
         continue;
      string ccode, ccur;
      if(!CountryOf(ev.country_id, ccode, ccur))
         continue;
      if(!JevListContains(g_currencies, ccur))
         continue;
      datetime t = vals[i].time;
      string line = StringFormat("%I64u;%I64u;%s;%s;%s;%s;%s;%s;%s;%d;%I64d;%I64d;%I64d;%I64d;%s",
                                 vals[i].id, vals[i].event_id, JevClean(ev.event_code), ccur, ccode,
                                 JevEnumText(EnumToString(ev.importance), "CALENDAR_IMPORTANCE_"),
                                 JevIsoServer(t), JevIsoUtc(t - offset),
                                 vals[i].period == 0 ? "" : JevIsoDate(vals[i].period),
                                 vals[i].revision,
                                 vals[i].actual_value, vals[i].forecast_value,
                                 vals[i].prev_value, vals[i].revised_prev_value,
                                 JevEnumText(EnumToString(vals[i].impact_type), "CALENDAR_IMPACT_"));
      JevCsvRow(f, line);
     }
   return f.rows - before;
  }

//+------------------------------------------------------------------+
//| Um arquivo com valores de todas as moedas no intervalo            |
//| [from, to] (horário do servidor).                                 |
//+------------------------------------------------------------------+
bool ExportRange(const datetime from, const datetime to, const string origin, const string tag,
                 const string onlyCurrency)
  {
   int offset = JevServerOffsetSeconds();
   JevCsvFile f;
   if(!JevCsvOpen(f, JevFileName("calendar", tag)))
      return false;
   JevCsvHeader(f, JevMeta(EXPORTER, origin, offset, TimeGMT()));
   JevCsvHeader(f, CAL_HEADER);
   for(int c = 0; c < ArraySize(g_currencies); c++)
     {
      if(onlyCurrency != "" && g_currencies[c] != onlyCurrency)
         continue;
      MqlCalendarValue vals[];
      ResetLastError();
      if(CalendarValueHistory(vals, from, to, NULL, g_currencies[c]) < 0)
        {
         PrintFormat("JevCalendarExporter: CalendarValueHistory %s falhou (erro %d)", g_currencies[c], GetLastError());
         JevCsvAbort(f);
         return false;
        }
      WriteValues(f, vals, offset);
     }
   if(f.rows == 0)
     {
      JevCsvAbort(f);
      return true;
     }
   long rows = f.rows;
   if(!JevCsvCommit(f))
      return false;
   PrintFormat("JevCalendarExporter: %s %s → %I64d valores", origin, tag, rows);
   return true;
  }

//+------------------------------------------------------------------+
//| Dicionário de eventos das moedas                                  |
//+------------------------------------------------------------------+
void ExportEvents()
  {
   JevCsvFile f;
   if(!JevCsvOpen(f, JevFileName("calevents", "all")))
      return;
   JevCsvHeader(f, JevMeta(EXPORTER, "SNAPSHOT", JevServerOffsetSeconds(), TimeGMT()));
   JevCsvHeader(f, "event_id;event_code;name;currency;country;importance;type;sector;frequency;time_mode;unit;multiplier;digits;source_url");
   for(int c = 0; c < ArraySize(g_currencies); c++)
     {
      MqlCalendarEvent evs[];
      if(CalendarEventByCurrency(g_currencies[c], evs) <= 0)
        {
         PrintFormat("JevCalendarExporter: nenhum evento para %s (erro %d)", g_currencies[c], GetLastError());
         continue;
        }
      for(int i = 0; i < ArraySize(evs); i++)
        {
         string ccode, ccur;
         if(!CountryOf(evs[i].country_id, ccode, ccur))
            continue;
         JevCsvRow(f, StringFormat("%I64u;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%u;%s",
                                   evs[i].id, JevClean(evs[i].event_code), JevClean(evs[i].name), ccur, ccode,
                                   JevEnumText(EnumToString(evs[i].importance), "CALENDAR_IMPORTANCE_"),
                                   JevEnumText(EnumToString(evs[i].type), "CALENDAR_TYPE_"),
                                   JevEnumText(EnumToString(evs[i].sector), "CALENDAR_SECTOR_"),
                                   JevEnumText(EnumToString(evs[i].frequency), "CALENDAR_FREQUENCY_"),
                                   JevEnumText(EnumToString(evs[i].time_mode), "CALENDAR_TIMEMODE_"),
                                   JevEnumText(EnumToString(evs[i].unit), "CALENDAR_UNIT_"),
                                   JevEnumText(EnumToString(evs[i].multiplier), "CALENDAR_MULTIPLIER_"),
                                   evs[i].digits, JevClean(evs[i].source_url)));
        }
     }
   long rows = f.rows;
   if(rows == 0)
     {
      JevCsvAbort(f);
      return;
     }
   if(JevCsvCommit(f))
      PrintFormat("JevCalendarExporter: dicionário → %I64d eventos", rows);
  }

//+------------------------------------------------------------------+
//| Histórico: um arquivo por moeda e ano completo, uma única vez     |
//+------------------------------------------------------------------+
void ExportHistory()
  {
   MqlDateTime now;
   TimeToStruct(TimeTradeServer(), now);
   MqlDateTime start;
   TimeToStruct(InpHistoryFrom, start);
   for(int c = 0; c < ArraySize(g_currencies) && !IsStopped(); c++)
      for(int y = start.year; y < now.year && !IsStopped(); y++)
        {
         string key = StringFormat("calendar_hist_%s_%d", g_currencies[c], y);
         if(JevStateRead(key, "") == "done")
            continue;
         datetime from = StringToTime(StringFormat("%d.01.01 00:00", y));
         datetime to = StringToTime(StringFormat("%d.01.01 00:00", y + 1)) - 1;
         if(from < InpHistoryFrom)
            from = InpHistoryFrom;
         if(ExportRange(from, to, "HISTORY", StringFormat("hist-%s-%d", g_currencies[c], y), g_currencies[c]))
            JevStateWrite(key, "done");
        }
  }

//+------------------------------------------------------------------+
void OnStart()
  {
   if(JevSplitList(InpCurrencies, g_currencies) == 0)
     {
      Print("JevCalendarExporter: nenhuma moeda configurada");
      return;
     }
   if(!JevWaitConnected())
      return;
   PrintFormat("JevCalendarExporter: conta %I64d em %s; servidor − UTC = %d s; moedas %s",
               AccountInfoInteger(ACCOUNT_LOGIN), AccountInfoString(ACCOUNT_SERVER),
               JevServerOffsetSeconds(), InpCurrencies);

   //--- inicializa o change_id ANTES dos retratos: o que mudar durante a exportação aparece no loop
   ulong changeId = 0;
   MqlCalendarValue vals[];
   CalendarValueLast(changeId, vals);

   ExportEvents();
   ExportHistory();

   //--- retrato desde a última vez que este Service esteve vivo (cobre o tempo em que ficou parado)
   datetime nowServer = TimeTradeServer();
   MqlDateTime d;
   TimeToStruct(nowServer, d);
   datetime yearStart = StringToTime(StringFormat("%d.01.01 00:00", d.year));
   datetime from = (datetime)StringToInteger(JevStateRead("calendar_last_alive", "0"));
   from = from > 0 ? from - 86400 : yearStart;
   if(from < InpHistoryFrom)
      from = InpHistoryFrom;
   ExportRange(from, nowServer + InpLookaheadDays * 86400, "SNAPSHOT", "snap", "");

   datetime lastSnapshot = TimeGMT();
   datetime lastAlive = 0;
   while(!IsStopped())
     {
      ArrayResize(vals, 0);
      if(CalendarValueLast(changeId, vals) > 0)
        {
         int offset = JevServerOffsetSeconds();
         JevCsvFile f;
         if(JevCsvOpen(f, JevFileName("calendar", "live")))
           {
            JevCsvHeader(f, JevMeta(EXPORTER, "LIVE", offset, TimeGMT()));
            JevCsvHeader(f, CAL_HEADER);
            if(WriteValues(f, vals, offset) > 0)
              {
               long rows = f.rows;
               if(JevCsvCommit(f))
                  PrintFormat("JevCalendarExporter: LIVE → %I64d valores", rows);
              }
            else
               JevCsvAbort(f);   // novidades só de outras moedas
           }
        }
      if(InpSnapshotMinutes > 0 && TimeGMT() - lastSnapshot >= InpSnapshotMinutes * 60)
        {
         datetime s = TimeTradeServer();
         ExportRange(s - 86400, s + InpLookaheadDays * 86400, "SNAPSHOT", "snap", "");
         lastSnapshot = TimeGMT();
        }
      if(TimeGMT() - lastAlive >= 60)
        {
         JevStateWrite("calendar_last_alive", IntegerToString((long)TimeTradeServer()));
         lastAlive = TimeGMT();
        }
      Sleep(InpPollMs);
     }
   JevStateWrite("calendar_last_alive", IntegerToString((long)TimeTradeServer()));
   Print("JevCalendarExporter: parado");
  }
//+------------------------------------------------------------------+
