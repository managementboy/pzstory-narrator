package de.fricke.pzstory;

/** Sandbox option labels must be facts, never untranslated UI identifiers. */
public final class StateReaderOptionTest {
    public static void run() {
        T.group("StateReader - Sandbox option values");
        T.eq("enum uses selected translated value", "Fast Shamblers",
                SandboxOption.selectedValue(new FakeEnum()));
        T.eq("integer uses selected number", "14",
                SandboxOption.selectedValue(new FakeInteger()));
        T.eq("untranslated identifier is rejected", null,
                SandboxOption.selectedValue(new FakeUntranslated()));
    }

    public static final class FakeEnum {
        public int getValue() { return 2; }
        public String getValueTranslation() { return "ZSpeed"; }
        public String getValueTranslationByIndexOrNull(int index) {
            return index == 2 ? "Fast Shamblers" : null;
        }
    }

    public static final class FakeInteger {
        public int getValue() { return 14; }
    }

    public static final class FakeUntranslated {
        public String getValue() { return "ZTransmission"; }
        public String getValueAsString() { return "ZTransmission"; }
    }
}
