package de.fricke.pzstory;

/** Endpoint policy: every bypass in the audit, plus the cases that must work. */
public final class EndpointTest {

    public static void run() {
        T.group("Endpoint - loopback HTTP is allowed");
        allow("http://localhost:11434/v1");
        allow("http://127.0.0.1:1234/v1");
        allow("http://[::1]:11434/v1");
        allow("http://localhost/v1");
        allow("http://LOCALHOST:11434/v1");          // scheme/host are case-insensitive

        T.group("Endpoint - HTTPS is allowed");
        allow("https://api.openai.com/v1");
        allow("https://generativelanguage.googleapis.com/v1beta");
        allow("https://openrouter.ai/api/v1");
        allow("https://example.com:8443/v1");

        T.group("Endpoint - the audit's bypass payloads are rejected");
        // Each of these defeated the old substring test.
        deny("http://localhost.evil.example",        "clear text");
        deny("http://127.0.0.1.evil.example",        "clear text");
        deny("http://127.0.0.1@evil.example",        "username");
        deny("http://evil.example/path//localhost",  "clear text");
        deny("http://user:pw@localhost:11434/v1",    "username");
        denyAny("http://[::1]@evil.example/v1");
        denyAny("http://localhost%2eevil.example/v1");
        denyAny("http://evil.example/#//localhost");

        T.group("Endpoint - remote plaintext is refused even with no API key");
        // The request body is private game state regardless of credentials.
        deny("http://api.openai.com/v1",     "clear text");
        deny("http://192.168.1.50:11434/v1", "clear text");
        deny("http://10.0.0.5/v1",           "clear text");
        deny("http://8.8.8.8/v1",            "clear text");

        T.group("Endpoint - malformed and hostile URLs");
        deny(null,                             "no endpoint");
        deny("",                               "no endpoint");
        deny("   ",                            "no endpoint");
        deny("ftp://localhost/v1",             "unsupported scheme");
        deny("file:///etc/passwd",             "unsupported scheme");
        deny("javascript:alert(1)",            "hierarchical");
        deny("mailto:a@b.c",                   "hierarchical");
        deny("/v1/chat",                       "https://");
        denyAny("localhost:11434");
        denyAny("http://localhost:99999/v1");
        deny("http://localhost:0/v1",          "out of range");
        deny("https://host/a/../../etc",       "'..'");
        deny("https://host/v1?key=leak",       "query");
        deny("https://host/v1#frag",           "fragment");
        deny("https://host\n/v1",              "control character");
        deny("https://host /v1",               "whitespace");
        deny("https://" + "a".repeat(3000),    "absurdly long");

        T.group("Endpoint - normalisation");
        T.eq("trailing slash stripped",
                "https://api.openai.com/v1",
                Endpoint.requireAllowed("https://api.openai.com/v1/"));
        T.eq("many trailing slashes stripped",
                "https://api.openai.com/v1",
                Endpoint.requireAllowed("https://api.openai.com/v1///"));
        T.eq("surrounding space trimmed",
                "http://localhost:11434/v1",
                Endpoint.requireAllowed("  http://localhost:11434/v1  "));

        T.group("Endpoint.isLocal - replaces baseUrl.contains(\"localhost\")");
        T.ok("localhost is local",        Endpoint.isLocal("http://localhost:11434/v1"));
        T.ok("127.0.0.1 is local",        Endpoint.isLocal("http://127.0.0.1:1234/v1"));
        T.ok("[::1] is local",            Endpoint.isLocal("http://[::1]:11434/v1"));
        T.ok("localhost.evil is NOT local",
                !Endpoint.isLocal("https://localhost.evil.example/v1"));
        T.ok("path containing localhost is NOT local",
                !Endpoint.isLocal("https://evil.example/path//localhost"));
        T.ok("userinfo localhost is NOT local",
                !Endpoint.isLocal("https://localhost@evil.example/v1"));
        T.ok("remote host is not local",  !Endpoint.isLocal("https://api.openai.com/v1"));
        T.ok("null is not local",         !Endpoint.isLocal(null));

        T.group("Endpoint.encodeSegment - Gemini model path cannot be rewritten");
        T.eq("ordinary model untouched", "gemini-3.5-flash",
                Endpoint.encodeSegment("gemini-3.5-flash"));
        T.eq("query injection encoded", "x%3Fkey%3Dstolen",
                Endpoint.encodeSegment("x?key=stolen"));
        T.eq("fragment encoded", "x%23frag", Endpoint.encodeSegment("x#frag"));
        T.eq("slash encoded", "..%2F..%2Fv1beta", Endpoint.encodeSegment("../../v1beta"));
        T.eq("space encoded", "a%20b", Endpoint.encodeSegment("a b"));
        T.eq("newline encoded", "a%0Ab", Endpoint.encodeSegment("a\nb"));
        T.eq("unicode encoded", "%C3%A9", Endpoint.encodeSegment("é"));
        T.throwsWith("bare .. refused", "path segment",
                () -> Endpoint.encodeSegment(".."));
    }

    private static void allow(String url) {
        try {
            String out = Endpoint.requireAllowed(url);
            T.ok("allow " + brief(url), out != null && !out.isEmpty());
        } catch (Throwable t) {
            T.ok("allow " + brief(url) + "  [rejected: " + t.getMessage() + "]", false);
        }
    }

    /** Rejected, for any reason. Used where several guards could fire first. */
    private static void denyAny(String url) {
        try {
            String out = Endpoint.requireAllowed(url);
            T.ok("deny " + brief(url) + "  [WRONGLY ALLOWED as " + out + "]", false);
        } catch (Endpoint.Rejected r) {
            T.ok("deny " + brief(url), true);
        } catch (Throwable t) {
            T.ok("deny " + brief(url) + "  [unexpected " + t.getClass().getSimpleName() + "]", false);
        }
    }

    private static String brief(String s) {
        if (s == null) return "null";
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }

    private static void deny(String url, String expectReason) {
        try {
            String out = Endpoint.requireAllowed(url);
            T.ok("deny " + brief(url) + "  [WRONGLY ALLOWED as " + out + "]", false);
        } catch (Endpoint.Rejected r) {
            String m = String.valueOf(r.getMessage()).toLowerCase();
            boolean right = m.contains(expectReason.toLowerCase());
            T.ok("deny " + brief(url) + (right ? "" : "  [wrong reason: " + r.getMessage() + "]"), right);
        } catch (Throwable t) {
            T.ok("deny " + url + "  [unexpected " + t.getClass().getSimpleName() + "]", false);
        }
    }
}
