package com.jevforex.collect;

import java.util.regex.Pattern;

/**
 * Extração simples de texto de HTML.
 * Provisório: no passo 3 o normalizador faz uma extração melhor (só o corpo do artigo).
 */
public final class HtmlText {

    private static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style|noscript|nav|header|footer)[^>]*>.*?</\\1>");
    private static final Pattern BLOCK = Pattern.compile("(?i)</?(p|div|br|li|h[1-6]|tr)[^>]*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern LINES = Pattern.compile("\\n\\s*\\n+");

    private HtmlText() {
    }

    public static String extract(String html, int maxChars) {
        if (html == null) return "";
        // começa pelo corpo do artigo quando a página o marca (ex.: Fed usa id="article")
        String lower = html.toLowerCase(java.util.Locale.ROOT);
        for (String marker : new String[]{"id=\"article\"", "<article", "<main"}) {
            int at = lower.indexOf(marker);
            if (at > 0) {
                html = html.substring(Math.max(0, html.lastIndexOf('<', at)));
                break;
            }
        }
        String s = SCRIPT.matcher(html).replaceAll(" ");
        s = BLOCK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&rsquo;", "'").replace("&lsquo;", "'")
                .replace("&ldquo;", "\"").replace("&rdquo;", "\"").replace("&mdash;", "—")
                .replace("&ndash;", "–").replace("&lt;", "<").replace("&gt;", ">");
        s = SPACES.matcher(s).replaceAll(" ");
        s = LINES.matcher(s).replaceAll("\n\n").trim();
        return s.length() > maxChars ? s.substring(0, maxChars) : s;
    }
}
