package com.jevforex.collect.archive;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Onde cada banco central guarda comunicados e atas (conferido em 09/10/2026) e como ler os índices.
 * Os métodos {@code parse…} são puros (HTML → itens), para testar sem rede.
 */
public final class ArchiveSources {

    private ArchiveSources() {
    }

    /** Um banco: emissor, moeda e como montar a lista de itens. */
    public record Bank(String id, String issuer, String currency) {
    }

    public static final Map<String, Bank> BANKS = new LinkedHashMap<>();

    static {
        BANKS.put("fed", new Bank("fed", "Federal Reserve", "USD"));
        BANKS.put("ecb", new Bank("ecb", "European Central Bank", "EUR"));
        BANKS.put("boe", new Bank("boe", "Bank of England", "GBP"));
        BANKS.put("boj", new Bank("boj", "Bank of Japan", "JPY"));
        BANKS.put("boc", new Bank("boc", "Bank of Canada", "CAD"));
    }

    // ------------------------------------------------------------------ Fed: uma página com os últimos 5 anos

    public static final String FED_INDEX = "https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm";
    private static final Pattern FED_STATEMENT = Pattern.compile("/newsevents/pressreleases/monetary(\\d{8})a\\.htm");
    private static final Pattern FED_MINUTES = Pattern.compile("/monetarypolicy/fomcminutes(\\d{8})\\.htm");

    public static List<ArchiveItem> parseFed(String html, int fromYear) {
        List<ArchiveItem> out = new ArrayList<>();
        for (String d : distinct(FED_STATEMENT, html)) {
            LocalDate date = yyyymmdd(d);
            if (date.getYear() < fromYear) continue;
            out.add(new ArchiveItem("https://www.federalreserve.gov/newsevents/pressreleases/monetary" + d + "a.htm",
                    "FOMC statement " + date, "statement", date, "fomc-meeting-statement", "same_day"));
        }
        for (String d : distinct(FED_MINUTES, html)) {
            LocalDate date = yyyymmdd(d);
            if (date.getYear() < fromYear) continue;
            out.add(new ArchiveItem("https://www.federalreserve.gov/monetarypolicy/fomcminutes" + d + ".htm",
                    "Minutes of the FOMC meeting of " + date, "minutes", date, "fomc-minutes", "first_after"));
        }
        return out;
    }

    // ------------------------------------------------------------------ BCE: índices anuais

    public static String ecbDecisionsIndex(int year) {
        return "https://www.ecb.europa.eu/press/govcdec/mopo/" + year + "/html/index_include.en.html";
    }

    public static String ecbStatementsIndex(int year) {
        return "https://www.ecb.europa.eu/press/press_conference/monetary-policy-statement/" + year
                + "/html/index_include.en.html";
    }

    public static String ecbAccountsIndex(int year) {
        return "https://www.ecb.europa.eu/press/accounts/" + year + "/html/index_include.en.html";
    }

    /**
     * @param prefix mp (decisão), is (declaração na coletiva) ou mg (accounts)
     */
    public static List<ArchiveItem> parseEcb(String html, String prefix) {
        Pattern p = Pattern.compile("href=\"(/press/[^\"]*/ecb\\." + prefix + "(\\d{6})~[0-9a-f]+\\.en\\.html)\"");
        List<ArchiveItem> out = new ArrayList<>();
        Matcher m = p.matcher(html == null ? "" : html);
        List<String> seen = new ArrayList<>();
        while (m.find()) {
            if (seen.contains(m.group(1))) continue;
            seen.add(m.group(1));
            LocalDate date = yymmdd(m.group(2));
            String url = "https://www.ecb.europa.eu" + m.group(1);
            out.add(switch (prefix) {
                case "mp" -> new ArchiveItem(url, "ECB monetary policy decisions " + date, "statement", date,
                        "ecb-interest-rate-decision", "same_day");
                case "is" -> new ArchiveItem(url, "ECB monetary policy statement " + date, "press_conference", date,
                        "ecb-monetary-policy-press-conference", "same_day");
                default -> new ArchiveItem(url, "Account of the ECB monetary policy meeting, published " + date,
                        "accounts", date, "ecb-monetary-policy-meeting-accounts", "same_day");
            });
        }
        return out;
    }

    // ------------------------------------------------------------------ BoE: URL pelo mês da decisão (do calendário)

    /** https://www.bankofengland.co.uk/monetary-policy-summary-and-minutes/2024/august-2024 */
    public static ArchiveItem boeItem(LocalDate decision) {
        String month = decision.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
        return new ArchiveItem("https://www.bankofengland.co.uk/monetary-policy-summary-and-minutes/"
                + decision.getYear() + "/" + month + "-" + decision.getYear(),
                "Monetary Policy Summary and minutes " + decision, "summary_minutes", decision,
                "boe-interest-rate-decision", "same_day");
    }

    // ------------------------------------------------------------------ BoJ: índices anuais

    public static String bojStatementsIndex(int year) {
        return "https://www.boj.or.jp/en/mopo/mpmdeci/state_" + year + "/index.htm";
    }

    public static String bojMinutesIndex(int year) {
        return "https://www.boj.or.jp/en/mopo/mpmsche_minu/minu_" + year + "/index.htm";
    }

    public static List<ArchiveItem> parseBojStatements(String html, int year) {
        Pattern p = Pattern.compile("/en/mopo/mpmdeci/state_" + year + "/k(\\d{6})a\\.htm");
        List<ArchiveItem> out = new ArrayList<>();
        for (String d : distinct(p, html)) {
            LocalDate date = yymmdd(d);
            out.add(new ArchiveItem("https://www.boj.or.jp/en/mopo/mpmdeci/state_" + year + "/k" + d + "a.htm",
                    "Statement on Monetary Policy " + date, "statement", date, "boj-monetary-policy-statement",
                    "same_day"));
        }
        return out;
    }

    /**
     * Ata da reunião de g&lt;data&gt;: publicada depois da reunião SEGUINTE. Os índices de anos anteriores
     * linkam a página g&lt;data&gt;.htm (o PDF vem como anexo); o do ano corrente, o PDF direto.
     */
    public static List<ArchiveItem> parseBojMinutes(String html, int year) {
        Pattern p = Pattern.compile("/en/mopo/mpmsche_minu/minu_" + year + "/(g\\d{6}\\.(?:htm|pdf))");
        List<ArchiveItem> out = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        for (String file : distinct(p, html)) {
            String d = file.substring(1, 7);
            if (dates.contains(d)) continue;   // a mesma ata em .htm e .pdf: fica a primeira
            dates.add(d);
            LocalDate date = yymmdd(d);
            out.add(new ArchiveItem("https://www.boj.or.jp/en/mopo/mpmsche_minu/minu_" + year + "/" + file,
                    "Minutes of the Monetary Policy Meeting of " + date, "minutes", date,
                    "boj-monetary-policy-meeting-minutes", "after_next:boj-monetary-policy-statement"));
        }
        return out;
    }

    // ------------------------------------------------------------------ BoC: URL pela data da decisão (do calendário)

    /** https://www.bankofcanada.ca/2024/01/fad-press-release-2024-01-24/ */
    public static ArchiveItem bocItem(LocalDate decision) {
        String ymd = decision.toString();
        return new ArchiveItem("https://www.bankofcanada.ca/" + decision.getYear() + "/"
                + String.format("%02d", decision.getMonthValue()) + "/fad-press-release-" + ymd + "/",
                "Bank of Canada interest rate announcement " + ymd, "statement", decision,
                "boc-interest-rate-decision", "same_day");
    }

    // ------------------------------------------------------------------ util

    private static List<String> distinct(Pattern p, String html) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(html == null ? "" : html);
        while (m.find()) if (!out.contains(m.group(1))) out.add(m.group(1));
        return out;
    }

    private static LocalDate yyyymmdd(String s) {
        return LocalDate.of(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(4, 6)),
                Integer.parseInt(s.substring(6, 8)));
    }

    private static LocalDate yymmdd(String s) {
        return LocalDate.of(2000 + Integer.parseInt(s.substring(0, 2)), Integer.parseInt(s.substring(2, 4)),
                Integer.parseInt(s.substring(4, 6)));
    }
}
