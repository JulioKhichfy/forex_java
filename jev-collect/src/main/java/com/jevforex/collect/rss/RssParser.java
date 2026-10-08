package com.jevforex.collect.rss;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Leitor mínimo de RSS 2.0 e Atom, sem dependências externas e protegido contra XXE. */
public final class RssParser {

    private RssParser() {
    }

    public record FeedItem(String title, String link, String guid, Instant publishedAt, String summary) {
        /** Chave de deduplicação: guid quando existe, senão o link. */
        public String key() {
            return guid != null && !guid.isBlank() ? guid : link;
        }
    }

    public static List<FeedItem> parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        Document doc = b.parse(new ByteArrayInputStream(xml));

        List<FeedItem> out = new ArrayList<>();
        NodeList items = doc.getElementsByTagNameNS("*", "item");      // RSS 2.0 / RDF
        for (int i = 0; i < items.getLength(); i++) {
            Element e = (Element) items.item(i);
            out.add(new FeedItem(
                    text(e, "title"),
                    text(e, "link"),
                    text(e, "guid"),
                    parseDate(firstNonBlank(text(e, "pubDate"), text(e, "date"))),
                    text(e, "description")));
        }
        NodeList entries = doc.getElementsByTagNameNS("*", "entry");   // Atom
        for (int i = 0; i < entries.getLength(); i++) {
            Element e = (Element) entries.item(i);
            out.add(new FeedItem(
                    text(e, "title"),
                    atomLink(e),
                    text(e, "id"),
                    parseDate(firstNonBlank(text(e, "published"), text(e, "updated"))),
                    firstNonBlank(text(e, "summary"), text(e, "content"))));
        }
        return out;
    }

    private static String text(Element parent, String local) {
        NodeList nl = parent.getElementsByTagNameNS("*", local);
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getParentNode() == parent) {
                String t = n.getTextContent();
                return t == null ? null : t.trim();
            }
        }
        return null;
    }

    private static String atomLink(Element entry) {
        NodeList nl = entry.getElementsByTagNameNS("*", "link");
        String fallback = null;
        for (int i = 0; i < nl.getLength(); i++) {
            Element l = (Element) nl.item(i);
            String rel = l.getAttribute("rel");
            String href = l.getAttribute("href");
            if (rel.isEmpty() || "alternate".equals(rel)) return href;
            if (fallback == null) fallback = href;
        }
        return fallback;
    }

    static Instant parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(s.trim()).toInstant();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
