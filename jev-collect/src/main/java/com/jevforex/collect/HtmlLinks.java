package com.jevforex.collect;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links para PDFs anexos no corpo de uma página (ex.: "Attachment (PDF)" no Fed, o discurso em PDF no BoE).
 *
 * <p>Só o corpo do artigo conta: menus e rodapés costumam ter PDFs que não são o documento
 * (relatório anual, política de privacidade). Só o mesmo site: nada de seguir links para fora.</p>
 */
public final class HtmlLinks {

    private static final Pattern PDF_LINK = Pattern.compile(
            "(?is)<a\\s[^>]*?href\\s*=\\s*[\"']([^\"'#]+?\\.pdf(?:\\?[^\"'#]*)?)[\"'][^>]*>(.*?)</a>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final String[] START = {"id=\"article\"", "<article", "<main", "id=\"main-content\"", "id=\"content\""};
    private static final String[] END = {"</article>", "</main>", "<footer"};

    private HtmlLinks() {
    }

    public record Link(URI url, String label) {
    }

    public static List<Link> pdfLinks(String html, URI pageUrl, int max) {
        if (html == null || max <= 0) return List.of();
        String body = articleRegion(html);
        Set<URI> seen = new LinkedHashSet<>();
        List<Link> out = new ArrayList<>();
        Matcher m = PDF_LINK.matcher(body);
        while (m.find() && out.size() < max) {
            URI url;
            try {
                url = pageUrl.resolve(m.group(1).trim().replace("&amp;", "&").replace(" ", "%20"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (url.getHost() == null || !url.getHost().equalsIgnoreCase(pageUrl.getHost())) continue;
            if (!"https".equalsIgnoreCase(url.getScheme()) && !"http".equalsIgnoreCase(url.getScheme())) continue;
            if (!seen.add(url)) continue;
            String label = TAG.matcher(m.group(2)).replaceAll(" ").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
            out.add(new Link(url, label));
        }
        return out;
    }

    /** Do início do artigo até o fim dele (ou da página, se não houver marcação). */
    static String articleRegion(String html) {
        String lower = html.toLowerCase(Locale.ROOT);
        int start = -1;
        for (String s : START) {
            int at = lower.indexOf(s);
            if (at >= 0 && (start < 0 || at < start)) start = at;
        }
        if (start < 0) return html;
        int end = lower.length();
        for (String e : END) {
            int at = lower.indexOf(e, start + 1);
            if (at > start && at < end) end = at;
        }
        return html.substring(start, end);
    }
}
