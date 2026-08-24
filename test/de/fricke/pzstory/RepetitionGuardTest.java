package de.fricke.pzstory;

/** Titles and page openings compare predictably across punctuation/case. */
public final class RepetitionGuardTest {
    public static void run() {
        T.group("Story repetition - deterministic signatures");
        T.eq("title ignores punctuation and case", "the dead grid",
                RepetitionGuard.titleKey("  The Dead: Grid! "));
        T.eq("opening uses ten words", "one two three four five six seven eight nine ten",
                RepetitionGuard.openingKey(
                        "One two three four five six seven eight nine ten eleven twelve"));
        T.eq("blank opening stays blank", "", RepetitionGuard.openingKey("\n\t"));
    }
}
