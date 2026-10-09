//+------------------------------------------------------------------+
//| Jev Forex — HTTP com a API local (127.0.0.1:8080)                 |
//|                                                                  |
//| WebRequest exige a URL liberada em:                              |
//| Ferramentas → Opções → Expert Advisors →                          |
//| "Permitir WebRequest para as URLs listadas" → http://127.0.0.1:8080
//+------------------------------------------------------------------+
#ifndef JEV_HTTP_MQH
#define JEV_HTTP_MQH

#define JEV_HTTP_TIMEOUT_MS 1500
#define JEV_ERR_URL_NOT_ALLOWED 4014

//--- GET; devolve o código HTTP (−1 = falha local: veja GetLastError)
int JevHttpGet(const string url, string &body)
  {
   char data[], result[];
   string headers;
   ResetLastError();
   int code = WebRequest("GET", url, "", JEV_HTTP_TIMEOUT_MS, data, result, headers);
   body = code > 0 ? CharArrayToString(result, 0, WHOLE_ARRAY, CP_UTF8) : "";
   return code;
  }

//--- POST com corpo JSON
int JevHttpPostJson(const string url, const string json, string &body)
  {
   char data[], result[];
   string headers;
   int n = StringToCharArray(json, data, 0, WHOLE_ARRAY, CP_UTF8);
   if(n > 0)
      ArrayResize(data, n - 1);   // sem o terminador nulo
   ResetLastError();
   int code = WebRequest("POST", url, "Content-Type: application/json\r\n", JEV_HTTP_TIMEOUT_MS,
                         data, result, headers);
   body = code > 0 ? CharArrayToString(result, 0, WHOLE_ARRAY, CP_UTF8) : "";
   return code;
  }

string JevJsonEscape(string s)
  {
   StringReplace(s, "\\", "\\\\");
   StringReplace(s, "\"", "\\\"");
   return s;
  }

//--- codifica para query string (letras, dígitos e -_.~ passam direto)
string JevUrlEncode(const string s)
  {
   string out = "";
   uchar bytes[];
   int n = StringToCharArray(s, bytes, 0, WHOLE_ARRAY, CP_UTF8) - 1;
   for(int i = 0; i < n; i++)
     {
      uchar c = bytes[i];
      bool safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                  || c == '-' || c == '_' || c == '.' || c == '~';
      out += safe ? CharToString(c) : StringFormat("%%%02X", c);
     }
   return out;
  }

#endif
//+------------------------------------------------------------------+
