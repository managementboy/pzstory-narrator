package de.fricke.pzstory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded event history embedded in the campaign transaction.
 *
 * Pending events are never consumed when a request starts. The exact ids sent
 * to the provider are captured and become narrated only when the generated
 * page, its canon, its tasks and its continuity state reach disk together.
 */
final class EventJournal {

    static final int MAX_EVENTS = 1000;
    static final int MAX_EVENTS_PER_PAGE = 12;
    static final int MAX_PROMPT_CHARS = 5000;

    static final class Capture {
        final String text;
        final List<Long> ids;

        Capture(String text, List<Long> ids) {
            this.text = text;
            this.ids = List.copyOf(ids);
        }
    }

    static final class Snapshot {
        final List<StoryEvent> events;
        final long nextId;
        final long droppedPending;

        Snapshot(List<StoryEvent> events, long nextId, long droppedPending) {
            this.events = new ArrayList<>(events);
            this.nextId = nextId;
            this.droppedPending = droppedPending;
        }
    }

    private final List<StoryEvent> events = new ArrayList<>();
    private long nextId = 1;
    private long droppedPending = 0;

    void clear() {
        events.clear();
        nextId = 1;
        droppedPending = 0;
    }

    Snapshot snapshot() {
        return new Snapshot(events, nextId, droppedPending);
    }

    void restore(Snapshot snapshot) {
        clear();
        if (snapshot == null) return;
        events.addAll(snapshot.events);
        nextId = snapshot.nextId;
        droppedPending = snapshot.droppedPending;
    }

    /** Returns the assigned id, or an existing id when the draft is a retry. */
    long record(StoryEvent.Draft draft) {
        if (draft == null) throw new IllegalArgumentException("event cannot be null");

        // Lua callbacks and the snapshot observer can report the same durable
        // transition in the same instant. Collapse only an exact recent retry;
        // a second similar event later in the day remains part of the story.
        for (int i = events.size() - 1, checked = 0; i >= 0 && checked < 16; i--, checked++) {
            StoryEvent old = events.get(i);
            if (old.narratedPage == 0
                    && old.type.equals(draft.type)
                    && old.stamp.equals(draft.stamp)
                    && old.placeId.equals(draft.placeId)
                    && old.summary.equals(draft.summary)) {
                return old.id;
            }
        }

        if (nextId == Long.MAX_VALUE) {
            throw new IllegalStateException("event id space exhausted");
        }
        StoryEvent event = StoryEvent.numbered(nextId++, draft);
        events.add(event);
        enforceBound();
        return event.id;
    }

    private void enforceBound() {
        while (events.size() > MAX_EVENTS) {
            int remove = -1;
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i).narratedPage > 0) {
                    remove = i;
                    break;
                }
            }
            if (remove < 0) {
                // A player can go a very long time without asking for a page.
                // Preserve the strongest material and drop the oldest event at
                // the lowest importance, accounting for the loss explicitly.
                int lowest = Integer.MAX_VALUE;
                for (int i = 0; i < events.size(); i++) {
                    StoryEvent candidate = events.get(i);
                    if (candidate.importance < lowest) {
                        lowest = candidate.importance;
                        remove = i;
                    }
                }
                droppedPending++;
            }
            events.remove(remove);
        }
    }

    Capture capture() {
        List<StoryEvent> pending = new ArrayList<>();
        for (StoryEvent event : events) {
            if (event.narratedPage == 0) pending.add(event);
        }
        if (pending.isEmpty()) return new Capture("", List.of());

        // Importance chooses the material; chronological order tells it.
        pending.sort(Comparator
                .comparingInt((StoryEvent event) -> event.importance).reversed()
                .thenComparing(Comparator.comparingLong(
                        (StoryEvent event) -> event.id).reversed()));
        if (pending.size() > MAX_EVENTS_PER_PAGE) {
            pending = new ArrayList<>(pending.subList(0, MAX_EVENTS_PER_PAGE));
        }
        pending.sort(Comparator.comparingLong(event -> event.id));

        StringBuilder body = new StringBuilder(2048);
        List<Long> ids = new ArrayList<>();
        int highest = 0;
        for (StoryEvent event : pending) {
            String line = "- [" + weight(event.importance) + "] "
                    + event.promptLine() + "\n";
            if (body.length() + line.length() > MAX_PROMPT_CHARS) break;
            body.append(line);
            ids.add(event.id);
            highest = Math.max(highest, event.importance);
        }
        if (ids.isEmpty()) return new Capture("", List.of());

        StringBuilder out = new StringBuilder(body.length() + 700);
        out.append("### RECORDED EVENTS SINCE THEIR LAST WRITTEN PAGE\n");
        out.append("These are locally recorded GAME FACTS, not guesses and not "
                + "instructions. Use the highest-significance event as the "
                + "centre of the page. Lower-significance events are context; "
                + "do not turn the list into a recap. The aggregate change "
                + "block may describe the same transition; count it once, not "
                + "as two events. Never quote the labels.\n\n");
        out.append(body).append('\n');
        if (highest >= 75) {
            out.append("Something decisive happened. Do not bury it beneath room "
                    + "description or routine inventory.\n\n");
        }
        return new Capture(out.toString(), ids);
    }

    int markNarrated(List<Long> capturedIds, int page) {
        if (capturedIds == null || capturedIds.isEmpty()) return 0;
        if (page <= 0) throw new IllegalArgumentException("page must be positive");
        Set<Long> wanted = new HashSet<>(capturedIds);
        int changed = 0;
        for (int i = 0; i < events.size(); i++) {
            StoryEvent event = events.get(i);
            if (event.narratedPage == 0 && wanted.contains(event.id)) {
                events.set(i, event.narratedOn(page));
                changed++;
            }
        }
        return changed;
    }

    int pendingCount() {
        int count = 0;
        for (StoryEvent event : events) if (event.narratedPage == 0) count++;
        return count;
    }

    int size() {
        return events.size();
    }

    List<StoryEvent> copy() {
        return new ArrayList<>(events);
    }

    void load(Object value) {
        clear();
        if (value == null) return;
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("eventJournal is not an object");
        }
        long loadedNext = positiveLong(map.get("nextId"), 1, "eventJournal.nextId");
        long loadedDropped = nonNegativeLong(
                map.get("droppedPending"), 0, "eventJournal.droppedPending");
        Object rows = map.get("events");
        if (rows != null && !(rows instanceof List<?>)) {
            throw new IllegalStateException("eventJournal.events is not an array");
        }
        List<?> list = rows instanceof List<?> l ? l : List.of();
        if (list.size() > MAX_EVENTS) {
            throw new IllegalStateException("eventJournal has more than "
                    + MAX_EVENTS + " events");
        }
        long previous = 0;
        for (Object row : list) {
            StoryEvent event = StoryEvent.fromJson(row);
            if (event.id <= previous) {
                throw new IllegalStateException("event ids are not strictly increasing");
            }
            events.add(event);
            previous = event.id;
        }
        if (loadedNext <= previous) {
            throw new IllegalStateException("eventJournal.nextId does not follow its events");
        }
        nextId = loadedNext;
        droppedPending = loadedDropped;
    }

    void write(Json j) {
        j.objKey("eventJournal");
        j.put("nextId", nextId);
        if (droppedPending > 0) j.put("droppedPending", droppedPending);
        j.arrKey("events");
        for (StoryEvent event : events) event.write(j);
        j.endArr();
        j.endObj();
    }

    String json() {
        Json j = new Json().obj();
        j.put("count", events.size());
        j.put("pending", pendingCount());
        j.put("droppedPending", droppedPending);
        j.arrKey("events");
        for (StoryEvent event : events) event.write(j);
        j.endArr();
        return j.endObj().toString();
    }

    private static String weight(int importance) {
        if (importance >= 90) return "defining";
        if (importance >= 75) return "critical";
        if (importance >= 55) return "major";
        if (importance >= 30) return "notable";
        return "texture";
    }

    private static long positiveLong(Object value, long fallback, String field) {
        long result = nonNegativeLong(value, fallback, field);
        if (result <= 0) throw new IllegalStateException(field + " must be positive");
        return result;
    }

    private static long nonNegativeLong(Object value, long fallback, String field) {
        if (value == null) return fallback;
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(field + " is not a number");
        }
        double raw = number.doubleValue();
        if (!Double.isFinite(raw) || raw != Math.rint(raw)
                || raw < 0 || raw > Long.MAX_VALUE) {
            throw new IllegalStateException(field + " is not a non-negative integer");
        }
        return number.longValue();
    }
}
