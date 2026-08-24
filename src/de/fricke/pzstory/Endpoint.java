package de.fricke.pzstory;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Where this mod is allowed to send a request.
 *
 * EVERY page carries private game state: the survivor's name, their current
 * situation, their inventory, the player's own notes and the whole campaign so
 * far. Sending that in clear text over a network is a privacy failure whether
 * or not an API key rides along with it, which is why the rule here is about
 * the TRANSPORT and not about whether a credential is present.
 *
 * The policy this replaces classified loopback with substring tests:
 *
 *     u.contains("//localhost")
 *     u.contains("//127.0.0.1")
 *
 * Every one of these defeats that, and every one of them is a real host that
 * resolves somewhere other than this machine:
 *
 *     http://localhost.evil.example       - a registrable domain
 *     http://127.0.0.1.evil.example       - likewise
 *     http://127.0.0.1@evil.example       - userinfo; the HOST is evil.example
 *     http://evil.example/path//localhost - the needle is in the path
 *
 * So nothing here looks at the URL as text. It parses to a URI and asks the
 * parsed components, which is the only way to be sure which host will actually
 * be dialled.
 *
 * Pure Java by design: no game classes, so it is unit-testable on any JDK.
 */
public final class Endpoint {

    private Endpoint() {}

    /**
     * The only hosts for which plaintext HTTP is tolerated.
     *
     * Loopback and nothing else. This is what a local Ollama or LM Studio
     * listens on, and a request to it never leaves the machine, so there is no
     * network path on which to intercept it.
     *
     * Deliberately a literal allow-list rather than InetAddress.isLoopback():
     * resolving a name here would (a) hit DNS from the game thread and (b)
     * accept any attacker-controlled domain with an A record of 127.0.0.1,
     * which is a classic SSRF bypass. A name we did not write down is remote.
     */
    private static final java.util.Set<String> LOOPBACK_HOSTS = java.util.Set.of(
            "localhost",
            "127.0.0.1",
            "::1",
            "[::1]");

    /** Longest URL we will even attempt to parse. Guards the parser itself. */
    public static final int MAX_URL_CHARS = 2048;

    /** Thrown when an endpoint is not permitted. The message reaches the player. */
    public static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Rejected(String why) { super(why); }
    }

    /**
     * Validates a base URL and returns its normalised form (no trailing slash).
     *
     * @throws Rejected with a player-readable reason. Never returns null and
     *                  never returns something that failed a check - callers
     *                  are expected to let this propagate into a fault page.
     */
    public static String requireAllowed(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new Rejected("no endpoint URL configured");
        }
        String s = raw.strip();
        if (s.length() > MAX_URL_CHARS) {
            throw new Rejected("endpoint URL is absurdly long (" + s.length() + " characters)");
        }
        // Control characters and whitespace inside a URL are always either a
        // mistake or an attempt at request splitting.
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || Character.isWhitespace(c)) {
                throw new Rejected("endpoint URL contains whitespace or a control character");
            }
        }

        URI u;
        try {
            u = new URI(s);
        } catch (URISyntaxException e) {
            throw new Rejected("endpoint URL is not a valid URI: " + e.getReason());
        }

        if (u.isOpaque()) {
            throw new Rejected("endpoint URL must be hierarchical, e.g. https://host/path");
        }
        if (!u.isAbsolute()) {
            throw new Rejected("endpoint URL must start with https:// or http://");
        }

        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new Rejected("unsupported scheme '" + scheme + "' - only https and http are allowed");
        }

        // Userinfo is the "127.0.0.1@evil.example" trick. It has no legitimate
        // use here and its only effect would be to disguise the real host.
        if (u.getRawUserInfo() != null) {
            throw new Rejected("endpoint URL must not contain a username or password");
        }
        if (u.getRawFragment() != null) {
            throw new Rejected("endpoint URL must not contain a '#' fragment");
        }
        if (u.getRawQuery() != null) {
            throw new Rejected("endpoint URL must not contain a '?' query string");
        }

        String host = u.getHost();
        if (host == null || host.isEmpty()) {
            // getHost() returns null for a malformed authority that
            // getAuthority() will still happily show, e.g. "http://a_b/".
            throw new Rejected("endpoint URL has no valid host"
                    + (u.getAuthority() != null ? " (authority: " + safe(u.getAuthority()) + ")" : ""));
        }

        int port = u.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new Rejected("endpoint port " + port + " is out of range");
        }

        // Path traversal in a configured base URL is never intentional.
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        if (path.contains("..")) {
            throw new Rejected("endpoint path must not contain '..'");
        }

        if (scheme.equals("http") && !isLoopbackHost(host)) {
            throw new Rejected("refusing to send game state in clear text to '"
                    + safe(host) + "'. Plain http is allowed only for a model "
                    + "running on this machine (localhost, 127.0.0.1 or [::1]). "
                    + "Use https:// for anything else.");
        }

        // Normalise: strip any trailing slashes so callers can append a path.
        String out = u.toString();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    /** True when this URL is permitted. Never throws. */
    public static boolean isAllowed(String raw) {
        try {
            requireAllowed(raw);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when the endpoint is a model running on this machine.
     *
     * Used to decide how much campaign history to send: a local 8B model on a
     * laptop cannot take the whole book, a hosted one can. Replaces
     * {@code baseUrl.contains("localhost")} in StoryAPI, which was the same
     * bypassable test wearing a different hat.
     */
    public static boolean isLocal(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            URI u = new URI(raw.strip());
            return u.getHost() != null && isLoopbackHost(u.getHost());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isLoopbackHost(String host) {
        return LOOPBACK_HOSTS.contains(host.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * A single path segment, percent-encoded so it cannot escape its position.
     *
     * The Gemini adapter builds a URL around a model id out of profiles.json.
     * Unencoded, a model of "x?key=stolen" or "../../v1beta/models/y" would
     * rewrite the endpoint. Encoding is applied to everything that is not an
     * RFC 3986 unreserved character, so ? # / .. space and control characters
     * all become inert.
     */
    public static String encodeSegment(String segment) {
        if (segment == null) return "";
        StringBuilder b = new StringBuilder(segment.length() + 8);
        byte[] bytes = segment.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte value : bytes) {
            int c = value & 0xff;
            boolean unreserved =
                    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            // '.' is unreserved but "." and ".." are path-relative, so a
            // segment consisting only of dots is handled below.
            if (unreserved) b.append((char) c);
            else b.append('%').append(String.format("%02X", c));
        }
        String out = b.toString();
        if (out.equals(".") || out.equals("..")) {
            // Cannot happen through the encoder above, but a dot-only segment
            // is meaningful to a path resolver, so refuse rather than emit it.
            throw new Rejected("model id '" + segment + "' is not a usable path segment");
        }
        return out;
    }

    /** Trims a value for inclusion in an error message. */
    private static String safe(String s) {
        String t = s.length() > 120 ? s.substring(0, 120) + "..." : s;
        return t.replaceAll("[\\p{Cntrl}]", "?");
    }
}
