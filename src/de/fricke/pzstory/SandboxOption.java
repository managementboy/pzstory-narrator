package de.fricke.pzstory;

/** Converts a selected sandbox option into a narrator-safe human value. */
final class SandboxOption {
    private SandboxOption() {}

    /**
     * Returns the selected value, not an enum's translation-key prefix.
     *
     * Build 42's EnumSandboxOption#getValueTranslation() returns identifiers
     * such as "ZSpeed". The human value comes from the selected enum index.
     */
    static String selectedValue(Object option) {
        if (option == null) return null;
        Object raw = invokeNoArg(option, "getValue");
        if (raw instanceof Number number) {
            int index = number.intValue();
            for (String getter : new String[]{
                    "getValueTranslationByIndexOrNull",
                    "getValueTranslationByIndex"}) {
                try {
                    Object value = option.getClass().getMethod(getter, int.class)
                            .invoke(option, index);
                    String text = clean(value);
                    if (text != null) return text;
                } catch (ReflectiveOperationException | SecurityException ignored) {
                    // Integer options have no per-index translation.
                }
            }
        }
        String value = clean(raw);
        if (value != null) return value;
        // Some config options expose only the string form.
        return clean(invokeNoArg(option, "getValueAsString"));
    }

    private static Object invokeNoArg(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private static String clean(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        // An untranslated enum prefix is not information and must never be
        // presented to the narrator as a world fact.
        if (text.matches("Z[A-Z][A-Za-z0-9_]*")) return null;
        return text;
    }
}
