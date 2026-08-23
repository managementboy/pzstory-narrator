package de.fricke.pzstory;

/**
 * A test harness in one file.
 *
 * The project ships no runtime dependencies and downloads nothing during a
 * build, and a test framework would break both. This is enough: assertions,
 * grouping, a failure list and a non-zero exit code, which is all CI needs.
 */
public final class T {

    private static int passed = 0;
    private static final java.util.List<String> failures = new java.util.ArrayList<>();
    private static String group = "";

    private T() {}

    public static void group(String name) {
        group = name;
        System.out.println("\n  " + name);
    }

    public static void ok(String what, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("    PASS  " + what);
        } else {
            failures.add(group + " / " + what);
            System.out.println("    FAIL  " + what);
        }
    }

    public static void eq(String what, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures.add(group + " / " + what
                    + "\n            expected: " + expected
                    + "\n            actual:   " + actual);
            System.out.println("    FAIL  " + what);
            System.out.println("            expected: " + expected);
            System.out.println("            actual:   " + actual);
        } else {
            passed++;
            System.out.println("    PASS  " + what);
        }
    }

    /** Asserts that the body throws, and that the message mentions `contains`. */
    public static void throwsWith(String what, String contains, Runnable body) {
        try {
            body.run();
            failures.add(group + " / " + what + " (did not throw)");
            System.out.println("    FAIL  " + what + " (did not throw)");
        } catch (Throwable t) {
            String m = String.valueOf(t.getMessage());
            if (contains == null || m.toLowerCase().contains(contains.toLowerCase())) {
                passed++;
                System.out.println("    PASS  " + what);
            } else {
                failures.add(group + " / " + what + " (wrong message: " + m + ")");
                System.out.println("    FAIL  " + what + " (wrong message: " + m + ")");
            }
        }
    }

    public static int report() {
        System.out.println("\n" + "-".repeat(60));
        if (failures.isEmpty()) {
            System.out.println(passed + " passed, 0 failed");
            return 0;
        }
        System.out.println(passed + " passed, " + failures.size() + " FAILED:");
        for (String f : failures) System.out.println("  * " + f);
        return 1;
    }
}
