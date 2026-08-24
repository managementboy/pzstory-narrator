package de.fricke.pzstory;

import java.util.List;
import java.util.Map;

/** Strictness and resource ceilings at every Java JSON trust boundary. */
public final class JsonParseTest {

    public static void run() {
        T.group("JsonParse - valid documents");
        Map<String, Object> value = JsonParse.parseObject(
                "\uFEFF{\"text\":\"line\\n\\u263a\",\"n\":-12.5e+2,"
                        + "\"items\":[true,false,null]}");
        T.eq("BOM and escapes", "line\n☺", value.get("text"));
        T.eq("strict number", Double.valueOf(-1250), value.get("n"));
        T.eq("array retained", 3, ((List<?>) value.get("items")).size());

        T.group("JsonParse - malformed strings are rejected");
        reject("raw newline in string", "{\"x\":\"a\nb\"}", "control");
        reject("incomplete escape", "{\"x\":\"abc\\", "escape");
        reject("short unicode escape", "{\"x\":\"\\u12\"}", "unicode");
        reject("non-hex unicode escape", "{\"x\":\"\\u12xz\"}", "unicode");

        T.group("JsonParse - non-JSON numbers are rejected");
        reject("leading plus", "+1", "number");
        reject("leading zero", "01", "number");
        reject("missing integer", ".5", "number");
        reject("missing fraction", "1.", "number");
        reject("missing exponent", "1e", "number");
        reject("infinite exponent", "1e309", "finite");

        T.group("JsonParse - recursion is bounded");
        String nested = "[".repeat(201) + "0" + "]".repeat(201);
        reject("201 levels", nested, "deeper than");

        T.group("Json writer/parser round trip");
        String encoded = new Json().obj()
                .put("quote", "a\"b\\c\n")
                .put("finite", 12.25)
                .endObj().toString();
        Map<String, Object> roundTrip = JsonParse.parseObject(encoded);
        T.eq("escaped string", "a\"b\\c\n", roundTrip.get("quote"));
        T.eq("finite decimal", Double.valueOf(12.25), roundTrip.get("finite"));
    }

    private static void reject(String what, String json, String message) {
        T.throwsWith(what, message, () -> JsonParse.parse(json));
    }
}
