package de.fricke.pzstory;

/** The synthetic lab must remain complete and incapable of save mutation. */
public final class TestLabDiagnosticsTest {
    public static void run() {
        T.group("Test Lab - non-destructive gameplay scenarios");
        String all = TestLabDiagnostics.run("all");
        T.ok("all gameplay scenarios pass",
                all.contains("\"passed\":22") && all.contains("\"total\":22"));
        T.ok("scenario run declares that no save changed",
                all.contains("\"saveChanged\":false"));
        for (String scenario : new String[] {
                "place", "door", "vehicle", "noise", "kill", "time", "continuity",
                "director"
        }) {
            String result = TestLabDiagnostics.run(scenario);
            T.ok(scenario + " scenario has no failure",
                    !result.contains("\"pass\":false"));
        }
        T.ok("unknown scenarios fail visibly",
                TestLabDiagnostics.run("unknown").contains("\"pass\":false"));
    }
}
