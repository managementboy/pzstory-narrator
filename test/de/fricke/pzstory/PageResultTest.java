package de.fricke.pzstory;

/** Terminal model replies must be complete before campaign state is touched. */
public final class PageResultTest {

    private static final String PREMISE = words("premise", 24);
    private static final String PAGE = words("page", 60);

    public static void run() {
        T.group("PageResult - complete replies");
        PageResult first = PageResult.parse("""
                ### PREMISE
                %s

                ### TITLE
                The Quiet Door

                ### PAGE
                %s

                ### CANON
                - [belief] they distrust the silence upstairs

                ### TODO
                - learn what made the noise
                """.formatted(PREMISE, PAGE), true, 200);
        T.eq("first-page title", "The Quiet Door", first.title);
        T.eq("first-page premise", PREMISE, first.premise);
        T.eq("canon parsed", 1, first.canon.size());
        T.eq("single todo parsed", "learn what made the noise", first.todo);

        PageResult later = PageResult.parse("""
                ### TITLE
                Waiting Room
                ### PAGE
                %s
                ### CANON

                ### TODO

                """.formatted(PAGE).replace("\n", "\r\n"), false, 100);
        T.eq("CRLF accepted", "Waiting Room", later.title);
        T.eq("empty canon accepted", 0, later.canon.size());
        T.eq("empty todo accepted", "", later.todo);

        T.group("PageResult - incomplete replies are never committable");
        reject("first page needs premise", laterReply(), true, "PREMISE");
        reject("missing title heading", "### PAGE\n" + PAGE
                + "\n### CANON\n\n### TODO\n", false, "TITLE");
        reject("missing terminal TODO", "### TITLE\nT\n### PAGE\n" + PAGE
                + "\n### CANON\n", false, "TODO");
        reject("sections out of order", "### TITLE\nT\n### CANON\n\n### PAGE\n"
                + PAGE + "\n### TODO\n", false, "PAGE");
        reject("duplicate heading", "### TITLE\nT\n### PAGE\n" + PAGE
                + "\n### PAGE\nagain\n### CANON\n\n### TODO\n", false, "PAGE");
        reject("unknown heading", "### TITLE\nT\n### PAGE\n" + PAGE
                + "\n### NOTES\nno\n### CANON\n\n### TODO\n", false, "unknown");
        reject("two TODO entries", "### TITLE\nT\n### PAGE\n" + PAGE
                + "\n### CANON\n\n### TODO\n- one\n- two\n", false, "more than 1");
        reject("untyped canon", "### TITLE\nT\n### PAGE\n" + PAGE
                + "\n### CANON\n- merely uneasy\n### TODO\n", false, "[kind]");
        reject("survivor mood is not a person fact", "### TITLE\nT\n### PAGE\n" + PAGE
                + "\n### CANON\n- [person] the survivor feels afraid now\n### TODO\n",
                false, "another person");
        reject("short partial page", "### TITLE\nT\n### PAGE\ncut off"
                + "\n### CANON\n\n### TODO\n", false, "at least 40");

        PageResult naturalLong = PageResult.parse("### TITLE\nA Complete Moment\n### PAGE\n"
                + words("unhurried", 1200) + "\n### CANON\n\n### TODO\n", false, 150);
        T.eq("natural pages are not rejected by the old length setting",
                1200, naturalLong.page.split("\\s+").length);
    }

    private static String laterReply() {
        return "### TITLE\nT\n### PAGE\n" + PAGE + "\n### CANON\n\n### TODO\n";
    }

    private static void reject(String name, String reply, boolean first, String message) {
        T.throwsWith(name, message, () -> PageResult.parse(reply, first, 200));
    }

    private static String words(String word, int count) {
        return (word + " ").repeat(count).strip();
    }
}
