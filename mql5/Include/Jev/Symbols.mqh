//+------------------------------------------------------------------+
//| Jev Forex — listas de símbolos e sufixo da corretora              |
//|                                                                  |
//| O sistema usa sempre o nome canônico (EURUSD). O sufixo da        |
//| corretora (Exness: "m" → EURUSDm) só existe aqui, na fronteira.  |
//+------------------------------------------------------------------+
#ifndef JEV_SYMBOLS_MQH
#define JEV_SYMBOLS_MQH

//--- "EURUSD, GBPUSD" → {"EURUSD","GBPUSD"}; ignora itens vazios
int JevSplitList(const string csv, string &out[])
  {
   string parts[];
   int n = StringSplit(csv, ',', parts);
   ArrayResize(out, 0);
   for(int i = 0; i < n; i++)
     {
      string s = parts[i];
      StringTrimLeft(s);
      StringTrimRight(s);
      if(s == "")
         continue;
      int k = ArraySize(out);
      ArrayResize(out, k + 1);
      out[k] = s;
     }
   return ArraySize(out);
  }

bool JevListContains(const string &list[], const string value)
  {
   for(int i = 0; i < ArraySize(list); i++)
      if(list[i] == value)
         return true;
   return false;
  }

string JevBrokerSymbol(const string canonical, const string suffix)
  {
   return canonical + suffix;
  }

#endif
//+------------------------------------------------------------------+
