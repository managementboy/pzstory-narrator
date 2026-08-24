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
        reject("unpaired high surrogate", "{\"x\":\"\\ud800\"}", "surrogate");
        reject("unpaired low surrogate", "{\"x\":\"\\udc00\"}", "surrogate");
        reject("duplicate object key", "{\"x\":1,\"x\":2}", "duplicate");

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
        T.throwsWith("writer refuses NaN", "non-finite",
                () -> Json.of(java.util.Map.of("n", Double.NaN)));
        T.throwsWith("writer refuses an unpaired surrogate", "surrogate", () ->
                new Json().obj().put("bad", String.valueOf((char) 0xd800)));
        String emoji = Character.toString(0x1f642);
        String emojiJson = new Json().obj().put("emoji", emoji).endObj().toString();
        T.eq("writer preserves paired surrogates", emoji,
                JsonParse.parseObject(emojiJson).get("emoji"));

        Json transactional = new Json().obj().put("kept", true);
        Json.Checkpoint checkpoint = transactional.checkpoint();
        transactional.objKey("broken").put("partial", true);
        transactional.rollback(checkpoint).put("after", "ok").endObj();
        Map<String, Object> rolledBack = JsonParse.parseObject(transactional.toString());
        T.ok("writer rollback removes partial section", !rolledBack.containsKey("broken"));
        T.eq("writer remains usable after rollback", "ok", rolledBack.get("after"));
    }

    private static void reject(String what, String json, String message) {
        T.throwsWith(what, message, () -> JsonParse.parse(json));
    }
}
