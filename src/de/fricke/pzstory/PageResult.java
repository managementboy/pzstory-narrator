package de.fricke.pzstory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A complete, validated narrator reply.
 *
 * Model text is untrusted input.  The streaming UI is deliberately tolerant
 * while text is still arriving, but campaign persistence is not: no premise,
 * page, canon, direction, or remembered state is changed until this class has
 * proved that the terminal reply obeys the whole output contract.
 */
public final class PageResult {

    private static final int MAX_REPLY_CHARS = 128 * 1024;
    private static final int MAX_TITLE_CHARS = 120;
    private static final int MAX_PREMISE_CHARS = 4 * 1024;
    private static final int MAX_PAGE_CHARS = 32 * 1024;
    private static final int MAX_CANON_ENTRIES = 12;
    private static final int MAX_CANON_CHARS = 300;
    private static final int MAX_TODO_CHARS = 160;

    public final String premise;
    public final String title;
    public final String page;
    public final List<String> canon;
    public final String todo;

    private PageResult(String premise, String title, String page,
                       List<String> canon, String todo) {
        this.premise = premise;
        this.title = title;
        this.page = page;
        this.canon = List.copyOf(canon);
        this.todo = todo;
    }

    /** A stable, player-readable contract failure. */
    public static final class Invalid extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        Invalid(String message) { super(message); }
    }

    /**
     * Parses a terminal reply.  Headings must be literal and ordered.  Empty
     * CANON and TODO sections are valid, but the headings themselves are
     * required so a truncated response cannot masquerade as a finished page.
     */
    public static PageResult parse(String raw, boolean firstPage, int targetWords) {
        if (raw == null || raw.isBlank()) throw invalid("the reply was empty");
        if (raw.length() > MAX_REPLY_CHARS) {
            throw invalid("the reply exceeded " + MAX_REPLY_CHARS + " characters");
        }

        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] required = firstPage
                ? new String[] { "PREMISE", "TITLE", "PAGE", "CANON", "TODO" }
                : new String[] { "TITLE", "PAGE", "CANON", "TODO" };
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        String current = null;
        int nextRequired = 0;

        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("###")) {
                if (!trimmed.matches("### (PREMISE|TITLE|PAGE|CANON|TODO)")) {
                    throw invalid("an unknown or malformed heading was returned: "
                            + bounded(trimmed, 60));
                }
                String name = trimmed.substring(4);
                if (!firstPage && "PREMISE".equals(name)) {
                    throw invalid("PREMISE may only be written on the first page");
                }
                if (sections.containsKey(name)) {
                    throw invalid("the reply contained ### " + name + " twice");
                }
                if (nextRequired >= required.length || !required[nextRequired].equals(name)) {
                    String expected = nextRequired < required.length
                            ? "### " + required[nextRequired] : "the end of the reply";
                    throw invalid("expected " + expected + " before ### " + name);
                }
                sections.put(name, new StringBuilder());
                current = name;
                nextRequired++;
                continue;
            }

            if (current == null) {
                if (!trimmed.isEmpty()) {
                    throw invalid("text appeared before the first required heading");
                }
                continue;
            }
            StringBuilder body = sections.get(current);
            if (body.length() > 0) body.append('\n');
            body.append(line);
        }

        if (nextRequired != required.length) {
            throw invalid("the reply ended before ### " + required[nextRequired]);
        }

        String premise = value(sections, "PREMISE");
        String title = value(sections, "TITLE");
        String page = value(sections, "PAGE");
        String canonText = value(sections, "CANON");
        String todoText = value(sections, "TODO");

        validateSingleLine("title", title, 1, MAX_TITLE_CHARS);
        if ("...".equals(title) || "title".equalsIgnoreCase(title)) {
            throw invalid("the title was a placeholder");
        }

        if (firstPage) {
            if (premise.isBlank()) throw invalid("the first page had no premise");
            if (premise.length() > MAX_PREMISE_CHARS) {
                throw invalid("the premise exceeded " + MAX_PREMISE_CHARS + " characters");
            }
            int premiseWords = words(premise);
            if (premiseWords < 20 || premiseWords > 250) {
                throw invalid("the premise was " + premiseWords
                        + " words; expected a focused 20-250 word foundation");
            }
        }

        if (page.isBlank()) throw invalid("the PAGE section was empty");
        if (page.length() > MAX_PAGE_CHARS) {
            throw invalid("the page exceeded " + MAX_PAGE_CHARS + " characters");
        }
        int pageWords = words(page);
        int maximumWords = Math.max(600, Math.max(100, targetWords) * 3);
        if (pageWords < 40 || pageWords > maximumWords) {
            throw invalid("the page was " + pageWords + " words; safe range is 40-"
                    + maximumWords + " words for this setting");
        }

        List<String> canon = bulletLines(canonText, "CANON", MAX_CANON_ENTRIES,
                MAX_CANON_CHARS);
        String todo = singleBullet(todoText, "TODO", MAX_TODO_CHARS);
        return new PageResult(premise, title, page, canon, todo);
    }

    private static String value(Map<String, StringBuilder> sections, String name) {
        StringBuilder value = sections.get(name);
        return value == null ? "" : value.toString().strip();
    }

    private static List<String> bulletLines(String text, String section,
                                             int maxEntries, int maxChars) {
        List<String> out = new ArrayList<>();
        if (text.isBlank()) return out;
        for (String line : text.split("\n")) {
            String entry = line.strip();
            if (entry.isEmpty()) continue;
            if (!entry.startsWith("-")) {
                throw invalid(section + " entries must begin with '-'");
            }
            entry = entry.substring(1).strip();
            if (entry.isEmpty()) throw invalid(section + " contained an empty bullet");
            if (entry.length() > maxChars) {
                throw invalid(section + " contained an entry over " + maxChars
                        + " characters");
            }
            out.add(entry);
            if (out.size() > maxEntries) {
                throw invalid(section + " contained more than " + maxEntries + " entries");
            }
        }
        return out;
    }

    private static String singleBullet(String text, String section, int maxChars) {
        if (text.isBlank()) return "";
        List<String> entries = bulletLines(text, section, 1, maxChars);
        return entries.isEmpty() ? "" : entries.get(0);
    }

    private static void validateSingleLine(String field, String value,
                                           int minChars, int maxChars) {
        if (value.indexOf('\n') >= 0) throw invalid(field + " must be one line");
        if (value.length() < minChars || value.length() > maxChars) {
            throw invalid(field + " length was outside " + minChars + "-" + maxChars);
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw invalid(field + " contained a control character");
            }
        }
    }

    private static int words(String text) {
        String stripped = text.strip();
        return stripped.isEmpty() ? 0 : stripped.split("\\s+").length;
    }

    private static Invalid invalid(String message) { return new Invalid(message); }

    private static String bounded(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
