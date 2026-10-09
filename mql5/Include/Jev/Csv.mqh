//+------------------------------------------------------------------+
//| Jev Forex — escrita de arquivos em Common\Files\jev               |
//|                                                                  |
//| Todo arquivo nasce em jev\tmp (.tmp) e só é movido para          |
//| jev\inbox quando está completo: o importador Java nunca lê um    |
//| arquivo pela metade.                                             |
//|                                                                  |
//| Formato:                                                         |
//|   #exporter=...;server=...;login=...;offset_s=...;origin=...;seen_utc=...
//|   cabeçalho;separado;por;ponto;e;vírgula                         |
//|   linhas...                                                      |
//+------------------------------------------------------------------+
#ifndef JEV_CSV_MQH
#define JEV_CSV_MQH

#define JEV_DIR   "jev"
#define JEV_INBOX "jev\\inbox"
#define JEV_TMP   "jev\\tmp"
#define JEV_STATE "jev\\state"

long g_jevFileSeq = 0;

//--- "2026-10-14T12:30:00Z" (UTC)
string JevIsoUtc(const datetime t)
  {
   MqlDateTime d;
   TimeToStruct(t, d);
   return StringFormat("%04d-%02d-%02dT%02d:%02d:%02dZ", d.year, d.mon, d.day, d.hour, d.min, d.sec);
  }

//--- "2026-10-14T15:30:00" (horário do servidor da corretora, sem Z)
string JevIsoServer(const datetime t)
  {
   MqlDateTime d;
   TimeToStruct(t, d);
   return StringFormat("%04d-%02d-%02dT%02d:%02d:%02d", d.year, d.mon, d.day, d.hour, d.min, d.sec);
  }

//--- "2026-09-01"
string JevIsoDate(const datetime t)
  {
   MqlDateTime d;
   TimeToStruct(t, d);
   return StringFormat("%04d-%02d-%02d", d.year, d.mon, d.day);
  }

//--- "20261014T123000Z" (vai no nome do arquivo: ordem alfabética = ordem cronológica)
string JevStamp(const datetime t)
  {
   MqlDateTime d;
   TimeToStruct(t, d);
   return StringFormat("%04d%02d%02dT%02d%02d%02dZ", d.year, d.mon, d.day, d.hour, d.min, d.sec);
  }

//--- Diferença servidor − UTC, arredondada para 15 min (documento mestre, cap. 5).
//--- Exness: 0. Corretoras em GMT+2/+3: 7200/10800.
int JevServerOffsetSeconds()
  {
   long diff = (long)TimeTradeServer() - (long)TimeGMT();
   return (int)(MathRound(diff / 900.0) * 900);
  }

//--- Remove o separador e quebras de linha de textos livres (nomes de eventos etc.).
string JevClean(string s)
  {
   StringReplace(s, ";", ",");
   StringReplace(s, "\r", " ");
   StringReplace(s, "\n", " ");
   return s;
  }

//--- Tira o prefixo de um EnumToString: CALENDAR_IMPORTANCE_HIGH → HIGH
string JevEnumText(string s, const string prefix)
  {
   StringReplace(s, prefix, "");
   return s;
  }

//--- Linha #meta, que descreve o arquivo inteiro.
string JevMeta(const string exporter, const string origin, const int offsetSeconds, const datetime seenUtc)
  {
   return StringFormat("#exporter=%s;server=%s;login=%I64d;offset_s=%d;origin=%s;seen_utc=%s",
                       exporter, JevClean(AccountInfoString(ACCOUNT_SERVER)),
                       AccountInfoInteger(ACCOUNT_LOGIN), offsetSeconds, origin, JevIsoUtc(seenUtc));
  }

//--- calendar_20261014T123006Z_live-000042.csv
string JevFileName(const string kind, const string tag)
  {
   g_jevFileSeq++;
   return StringFormat("%s_%s_%s-%06I64d.csv", kind, JevStamp(TimeGMT()), tag, g_jevFileSeq);
  }

//+------------------------------------------------------------------+
//| Arquivo em construção                                            |
//+------------------------------------------------------------------+
struct JevCsvFile
  {
   int               handle;
   string            name;
   string            tmp;
   long              rows;
  };

bool JevCsvOpen(JevCsvFile &f, const string name)
  {
   FolderCreate(JEV_DIR, FILE_COMMON);
   FolderCreate(JEV_INBOX, FILE_COMMON);
   FolderCreate(JEV_TMP, FILE_COMMON);
   f.name = name;
   f.tmp = JEV_TMP + "\\" + name + ".tmp";
   f.rows = 0;
   ResetLastError();
   f.handle = FileOpen(f.tmp, FILE_WRITE | FILE_TXT | FILE_ANSI | FILE_COMMON, '\t', CP_UTF8);
   if(f.handle == INVALID_HANDLE)
     {
      PrintFormat("Jev: não consegui criar %s (erro %d)", f.tmp, GetLastError());
      return false;
     }
   return true;
  }

//--- linha de meta ou cabeçalho (não conta como dado)
void JevCsvHeader(JevCsvFile &f, const string line)
  {
   FileWriteString(f.handle, line + "\n");
  }

void JevCsvRow(JevCsvFile &f, const string line)
  {
   FileWriteString(f.handle, line + "\n");
   f.rows++;
  }

//--- fecha e publica no inbox
bool JevCsvCommit(JevCsvFile &f)
  {
   FileClose(f.handle);
   f.handle = INVALID_HANDLE;
   string dst = JEV_INBOX + "\\" + f.name;
   ResetLastError();
   if(!FileMove(f.tmp, FILE_COMMON, dst, FILE_COMMON | FILE_REWRITE))
     {
      PrintFormat("Jev: não consegui mover %s para o inbox (erro %d)", f.tmp, GetLastError());
      FileDelete(f.tmp, FILE_COMMON);
      return false;
     }
   return true;
  }

//--- descarta (ex.: nenhuma linha útil)
void JevCsvAbort(JevCsvFile &f)
  {
   if(f.handle != INVALID_HANDLE)
      FileClose(f.handle);
   f.handle = INVALID_HANDLE;
   FileDelete(f.tmp, FILE_COMMON);
  }

//+------------------------------------------------------------------+
//| Estado persistente (jev\state\<chave>.txt): sobrevive a reinícios |
//+------------------------------------------------------------------+
string JevStateRead(const string key, const string def)
  {
   string path = JEV_STATE + "\\" + key + ".txt";
   if(!FileIsExist(path, FILE_COMMON))
      return def;
   int h = FileOpen(path, FILE_READ | FILE_TXT | FILE_ANSI | FILE_COMMON, '\t', CP_UTF8);
   if(h == INVALID_HANDLE)
      return def;
   string v = FileReadString(h);
   FileClose(h);
   StringTrimLeft(v);
   StringTrimRight(v);
   return v == "" ? def : v;
  }

bool JevStateWrite(const string key, const string value)
  {
   FolderCreate(JEV_DIR, FILE_COMMON);
   FolderCreate(JEV_STATE, FILE_COMMON);
   string path = JEV_STATE + "\\" + key + ".txt";
   string tmp = path + ".tmp";
   int h = FileOpen(tmp, FILE_WRITE | FILE_TXT | FILE_ANSI | FILE_COMMON, '\t', CP_UTF8);
   if(h == INVALID_HANDLE)
     {
      PrintFormat("Jev: não consegui gravar o estado %s (erro %d)", key, GetLastError());
      return false;
     }
   FileWriteString(h, value);
   FileClose(h);
   return FileMove(tmp, FILE_COMMON, path, FILE_COMMON | FILE_REWRITE);
  }

//+------------------------------------------------------------------+
//| Espera o terminal conectar e logar na conta                      |
//+------------------------------------------------------------------+
bool JevWaitConnected()
  {
   bool warned = false;
   while(!IsStopped())
     {
      if(TerminalInfoInteger(TERMINAL_CONNECTED) && AccountInfoInteger(ACCOUNT_LOGIN) > 0)
         return true;
      if(!warned)
        {
         Print("Jev: aguardando conexão com a corretora...");
         warned = true;
        }
      Sleep(1000);
     }
   return false;
  }

#endif
//+------------------------------------------------------------------+
