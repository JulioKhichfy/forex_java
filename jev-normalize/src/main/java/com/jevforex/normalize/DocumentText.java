package com.jevforex.normalize;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Texto de um documento bruto do bronze (HTML, PDF ou TXT), pronto para dividir em trechos.
 * Parágrafos ficam separados por linha em branco; dentro do parágrafo, uma linha só.
 */
public final class DocumentText {

    private static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style|noscript|nav|header|footer|form)[^>]*>.*?</\\1>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern BLOCK = Pattern.compile("(?i)</?(p|div|br|li|h[1-6]|tr|table|blockquote|section)[^>]*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r\\u00A0]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n+");
    private static final Pattern HYPHEN_BREAK = Pattern.compile("(\\p{L})-\\n(\\p{Ll})");
    private static final String[] ARTICLE = {"id=\"article\"", "<article", "<main", "id=\"main-content\"", "id=\"content\""};
    /** Parágrafos de interface do site, nunca conteúdo (aviso de navegador da RBNZ, botões do BoC…). */
    private static final Pattern NOISE = Pattern.compile("(?i)^(browser issue|it looks like the browser you.re using"
            + ".*|share this page.*|print this page|back to top|skip to (main )?content|cookie.*settings.*)$");
    /** A partir daqui vem barra lateral/rodapé com OUTROS documentos: o artigo acabou. */
    private static final Pattern END_OF_ARTICLE = Pattern.compile("(?i)^(related information|related links|"
            + "related content|related publications|see also|more from the bank)$");
    private static final int MIN_ARTICLE_CHARS = 300;

    private DocumentText() {
    }

    /** Caracteres de controle (menos \n e \t): o Postgres não aceita \u0000 em jsonb e o Jev não precisa deles. */
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    /** Mais que isto de caracteres ilegíveis (U+FFFD) = extração quebrada (fonte sem mapeamento, binário). */
    private static final double MAX_GARBLED = 0.02;

    /** @param contentType html | pdf | txt */
    public static String extract(byte[] content, String contentType) {
        if (content == null || content.length == 0) return "";
        String type = contentType == null ? "html" : contentType.toLowerCase(Locale.ROOT);
        if (!type.equals("pdf") && looksBinary(content)) return "";   // ex.: planilha (zip) servida como página
        String text = switch (type) {
            case "pdf" -> pdf(content);
            case "txt" -> tidy(new String(content, StandardCharsets.UTF_8));
            default -> html(new String(content, StandardCharsets.UTF_8));
        };
        return clean(text);
    }

    /** Tira caracteres de controle; texto majoritariamente ilegível vira vazio (não vale a pena para o Jev). */
    static String clean(String text) {
        String t = CONTROL.matcher(text).replaceAll("");
        if (t.isEmpty()) return t;
        long garbled = t.chars().filter(ch -> ch == '�').count();
        return (double) garbled / t.length() > MAX_GARBLED ? "" : t;
    }

    /** Zip/xlsx, gzip ou muitos bytes de controle no começo: não é texto. */
    static boolean looksBinary(byte[] b) {
        if (b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) return true;
        if (b.length >= 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B) return true;
        int n = Math.min(b.length, 4096), control = 0;
        for (int i = 0; i < n; i++) {
            int v = b[i] & 0xFF;
            if (v < 0x09 || (v > 0x0D && v < 0x20)) control++;
        }
        return control > 8 && (double) control / n > 0.01;   // um \u0000 solto num texto curto não é binário
    }

    /** Tipo pela extensão do arquivo no lake (…/&lt;sha&gt;.pdf). */
    public static String typeOf(String lakePath) {
        String p = lakePath.toLowerCase(Locale.ROOT);
        return p.endsWith(".pdf") ? "pdf" : p.endsWith(".txt") ? "txt" : "html";
    }

    static String html(String html) {
        // começa pelo corpo do artigo quando a página o marca (Fed: id="article"; BCE, BoE: <main>)
        String lower = html.toLowerCase(Locale.ROOT);
        int start = -1;
        for (String marker : ARTICLE) {
            int at = lower.indexOf(marker);
            if (at > 0 && (start < 0 || at < start)) start = at;
        }
        if (start > 0) html = html.substring(Math.max(0, html.lastIndexOf('<', start)));
        String s = COMMENT.matcher(html).replaceAll(" ");
        s = SCRIPT.matcher(s).replaceAll(" ");
        s = BLOCK.matcher(s).replaceAll("\n\n");
        s = TAG.matcher(s).replaceAll(" ");
        s = entities(s);
        return withoutBoilerplate(tidy(s));
    }

    /** Tira ruído de interface, parágrafos repetidos em sequência e o que vem depois do fim do artigo. */
    static String withoutBoilerplate(String text) {
        StringBuilder out = new StringBuilder(text.length());
        String previous = null;
        for (String p : text.split("\n\n")) {
            if (NOISE.matcher(p).matches() || p.equals(previous)) continue;
            if (END_OF_ARTICLE.matcher(p).matches() && out.length() >= MIN_ARTICLE_CHARS) break;
            if (!out.isEmpty()) out.append("\n\n");
            out.append(p);
            previous = p;
        }
        return out.toString();
    }

    static String pdf(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setParagraphEnd("\n\n");     // marca o fim de parágrafo detectado pelo layout
            String raw = stripper.getText(doc);
            raw = HYPHEN_BREAK.matcher(raw.replace("\r", "")).replaceAll("$1$2");   // infla-\ntion → inflation
            return tidy(raw);
        } catch (IOException e) {
            throw new UncheckedIOException("PDF ilegível: " + e.getMessage(), e);
        }
    }

    /** Espaços normalizados; quebras simples viram espaço (um parágrafo = uma linha). */
    static String tidy(String s) {
        s = SPACES.matcher(s.replace("\r", "")).replaceAll(" ");
        StringBuilder out = new StringBuilder(s.length());
        for (String para : BLANK_LINES.split(s)) {
            String p = para.replace('\n', ' ').replaceAll(" {2,}", " ").trim();
            if (p.isEmpty()) continue;
            if (!out.isEmpty()) out.append("\n\n");
            out.append(p);
        }
        return out.toString();
    }

    private static String entities(String s) {
        s = s.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&rsquo;", "'").replace("&lsquo;", "'").replace("&ldquo;", "\"").replace("&rdquo;", "\"")
                .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…").replace("&euro;", "€")
                .replace("&pound;", "£").replace("&yen;", "¥").replace("&lt;", "<").replace("&gt;", ">");
        var m = NUMERIC_ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                cp = ' ';
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&amp;", "&");
    }
}
