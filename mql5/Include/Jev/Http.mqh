//+------------------------------------------------------------------+
//| Jev Forex — HTTP com a API local (127.0.0.1:8080)                 |
//|                                                                  |
//| WebRequest exige a URL liberada em:                              |
//| Ferramentas → Opções → Expert Advisors →                          |
//| "Permitir WebRequest para as URLs listadas" → http://127.0.0.1:8080
//+------------------------------------------------------------------+
#ifndef JEV_HTTP_MQH
#define JEV_HTTP_MQH

// 25/10/2026: 1,5 s era pouco — um pico de CPU no PC (previsões, radar) estourava uma consulta isolada
#define JEV_HTTP_TIMEOUT_MS 5000
#define JEV_ERR_URL_NOT_ALLOWED 4014

//--- o erro do PRÓPRIO WebRequest, guardado antes de qualquer outra chamada (antes, a conversão de uma resposta vazia
//--- sobrescrevia o erro com 4006 e o log mostrava "HTTP 1003, erro 4006", que não dizia nada)
int g_jevHttpError = 0;

//--- GET; devolve o código HTTP (−1 ou ≥ 1000 = falha local: veja g_jevHttpError e JevHttpReason)
int JevHttpGet(const string url, string &body)
  {
   char data[], result[];
   string headers;
   ResetLastError();
   int code = WebRequest("GET", url, "", JEV_HTTP_TIMEOUT_MS, data, result, headers);
   g_jevHttpError = GetLastError();
   body = code > 0 && code < 1000 && ArraySize(result) > 0 ? CharArrayToString(result, 0, WHOLE_ARRAY, CP_UTF8) : "";
   return code;
  }

//--- o motivo de uma falha, em português
string JevHttpReason(const int code, const int err)
  {
   if(code == -1)
     {
      switch(err)
        {
         case 4014: return "URL não liberada no MT5 (Ferramentas → Opções → Expert Advisors)";
         case 5200: return "endereço da API inválido";
         case 5201: return "não conectou: o servidor do sistema está desligado ou reiniciando";
         case 5202: return StringFormat("sem resposta em %d s (tempo esgotado)", JEV_HTTP_TIMEOUT_MS / 1000);
         case 5203: return "a requisição falhou no meio";
        }
      return StringFormat("falha local do MT5 (erro %d)", err);
     }
   if(code >= 1000)
      return StringFormat("sem resposta HTTP válida (código interno do MT5 %d, erro %d): em geral o servidor do sistema "
                          "estava reiniciando ou demorou demais", code, err);
   return StringFormat("o servidor respondeu HTTP %d", code);
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
   g_jevHttpError = GetLastError();
   body = code > 0 && code < 1000 && ArraySize(result) > 0 ? CharArrayToString(result, 0, WHOLE_ARRAY, CP_UTF8) : "";
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
