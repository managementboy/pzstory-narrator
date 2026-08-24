package de.fricke.pzstory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deliberate narrative setups and their explicit payoff/abandonment records. */
final class ThreadMemory {
    static final int MAX_THREADS = 100;
    private static final Pattern COMMAND = Pattern.compile(
            "^\\[thread]\\s+(setup|payoff|abandon)\\s+([a-z0-9][a-z0-9-]{2,39})\\s*:\\s*(.{3,240})$",
            Pattern.CASE_INSENSITIVE);

    record Thread(long id, String key, String setup, String status,
                  String resolution, String source, int openedPage,
                  int closedPage) {}
    record Snapshot(List<Thread> threads, long nextId) {}

    private final ArrayList<Thread> threads = new ArrayList<>();
    private long nextId = 1;

    void clear() { threads.clear(); nextId = 1; }
    Snapshot snapshot() { return new Snapshot(List.copyOf(threads), nextId); }
    void restore(Snapshot s) { threads.clear(); threads.addAll(s.threads); nextId = s.nextId; }
    int openCount() { int n = 0; for (Thread t : threads) if ("open".equals(t.status)) n++; return n; }

    static boolean looksLikeCommand(String text) {
        return text != null && text.strip().toLowerCase(Locale.ROOT).startsWith("[thread]");
    }

    boolean apply(String text, String source, int page) {
        if (text == null || !StoryFact.SOURCES.contains(source) || page < 0) return false;
        Matcher match = COMMAND.matcher(text.strip());
        if (!match.matches()) return false;
        String action = match.group(1).toLowerCase(Locale.ROOT);
        String key = match.group(2).toLowerCase(Locale.ROOT);
        String detail = clean(match.group(3), 240);
        int at = find(key);
        if ("setup".equals(action)) {
            if (at >= 0) return false;
            if (threads.size() >= MAX_THREADS && !dropOldestClosed()) return false;
            threads.add(new Thread(nextId++, key, detail, "open", "", source, page, 0));
            return true;
        }
        if (at < 0 || !"open".equals(threads.get(at).status)) return false;
        Thread old = threads.get(at);
        String status = "payoff".equals(action) ? "paid" : "abandoned";
        threads.set(at, new Thread(old.id, old.key, old.setup, status, detail,
                old.source, old.openedPage, page));
        return true;
    }

    String prompt(int currentPage) {
        if (threads.isEmpty()) return "";
        StringBuilder out = new StringBuilder(2048);
        out.append("### DELIBERATE SETUPS\n");
        out.append("Open setups may be developed or paid off, but must not be forgotten. ")
                .append("Use the exact key in CANON when resolving one.\n\n");
        boolean any = false;
        for (Thread t : threads) {
            if (!"open".equals(t.status)) continue;
            any = true;
            out.append("- ").append(t.key).append(": ").append(t.setup)
                    .append(" (opened page ").append(t.openedPage).append(')');
            if (currentPage - t.openedPage >= 5) {
                out.append(" — OVERDUE: develop it now, pay it off, or abandon it explicitly");
            }
            out.append('\n');
        }
        if (!any) out.append("(no open setups)\n");
        out.append("\nTo create or close a setup, use exactly one of these CANON forms:\n")
                .append("- [thread] setup short-key: what was deliberately established\n")
                .append("- [thread] payoff short-key: how it paid off\n")
                .append("- [thread] abandon short-key: why it is deliberately closed\n\n");
        return out.toString();
    }

    void write(Json j) {
        j.objKey("threadMemory"); j.put("nextId", nextId); j.arrKey("threads");
        for (Thread t : threads) {
            j.obj(); j.put("id", t.id); j.put("key", t.key); j.put("setup", t.setup);
            j.put("status", t.status); j.put("resolution", t.resolution);
            j.put("source", t.source); j.put("openedPage", t.openedPage);
            j.put("closedPage", t.closedPage); j.endObj();
        }
        j.endArr(); j.endObj();
    }

    void load(Object value) {
        clear();
        if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("threadMemory is not an object");
        Object rows = map.get("threads");
        if (!(rows instanceof List<?> list) || list.size() > MAX_THREADS) {
            throw new IllegalStateException("threadMemory threads are invalid");
        }
        long previous = 0;
        for (Object row : list) {
            if (!(row instanceof Map<?, ?>)) throw new IllegalStateException("thread is not an object");
            long id = integer(row, "id", 0);
            String key = JsonParse.str(row, "key", "");
            String status = JsonParse.str(row, "status", "");
            String setup = clean(JsonParse.str(row, "setup", ""), 240);
            String resolution = clean(JsonParse.str(row, "resolution", ""), 240);
            String source = JsonParse.str(row, "source", "");
            int opened = (int) integer(row, "openedPage", 0);
            int closed = (int) integer(row, "closedPage", 0);
            if (id <= previous || !key.matches("[a-z0-9][a-z0-9-]{2,39}")
                    || !List.of("open", "paid", "abandoned").contains(status)
                    || !StoryFact.SOURCES.contains(source) || setup.length() < 3
                    || ("open".equals(status) != (closed == 0 && resolution.isEmpty()))) {
                throw new IllegalStateException("thread record is invalid");
            }
            threads.add(new Thread(id, key, setup, status, resolution, source, opened, closed));
            previous = id;
        }
        nextId = integer(map, "nextId", previous + 1);
        if (nextId <= previous) throw new IllegalStateException("threadMemory nextId is stale");
    }

    String json() { Json j = new Json().obj(); write(j); return j.endObj().toString(); }

    private int find(String key) {
        for (int i = 0; i < threads.size(); i++) if (threads.get(i).key.equals(key)) return i;
        return -1;
    }
    private boolean dropOldestClosed() {
        for (int i = 0; i < threads.size(); i++) {
            if (!"open".equals(threads.get(i).status)) { threads.remove(i); return true; }
        }
        return false;
    }
    private static String clean(String value, int max) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        if (clean.length() > max) throw new IllegalStateException("thread text is too long");
        return clean;
    }
    private static long integer(Object object, String key, long fallback) {
        if (!(object instanceof Map<?, ?> map) || !(map.get(key) instanceof Number n)) return fallback;
        double d = n.doubleValue();
        if (!Double.isFinite(d) || d != Math.rint(d) || d < 0 || d > Integer.MAX_VALUE) {
            throw new IllegalStateException(key + " is not a non-negative integer");
        }
        return n.longValue();
    }
}
