package de.fricke.pzstory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What changed between two snapshots.
 *
 * THE SNAPSHOT IS A PHOTOGRAPH; THIS IS THE INTERVAL. Without it the narrator
 * only ever sees where the survivor is standing, so every page re-describes
 * the room - and the most interesting thing that has happened (a skill won off
 * the television, a wound taken, a bag filled) is invisible, because the state
 * shows the result and never the movement.
 *
 * Rendered as plain lines rather than JSON: this block is the answer to "what
 * is this page about", and it should read like an answer.
 */
public final class Delta {

    private Delta() {}

    /**
     * True when the survivor has not moved since the last page.
     *
     * This used to measure flat distance only, and got page 2 badly wrong: the
     * survivor climbed the stairs from the living room to the bathroom, which
     * is two tiles sideways and one floor up. Flat distance said 2, so the
     * prompt asserted "THE SURVIVOR HAS NOT MOVED" in the same request whose
     * change block said "they are now in a different room: bathroom". Handed
     * two contradictory facts, the model split the difference and had him
     * perform small actions in place - turning a tap, splashing his face -
     * which is the one thing it must never do.
     *
     * A floor change or a room change IS movement, whatever the tape measure
     * says.
     */
    public static boolean stillStanding(String beforeJson, String afterJson) {
        if (beforeJson == null || beforeJson.isBlank()) return true;  // opening page
        try {
            Map<String, Object> a = JsonParse.parseObject(beforeJson);
            Map<String, Object> b = JsonParse.parseObject(afterJson);
            Map<String, Object> pa = JsonParse.map(a, "position"), pb = JsonParse.map(b, "position");
            if (pa == null || pb == null) return false;
            if (JsonParse.num(pa, "z", 0) != JsonParse.num(pb, "z", 0)) return false;
            String ra = JsonParse.str(pa, "room", null), rb = JsonParse.str(pb, "room", null);
            if (ra != null ? !ra.equals(rb) : rb != null) return false;
            int dx = JsonParse.num(pb, "x", 0) - JsonParse.num(pa, "x", 0);
            int dy = JsonParse.num(pb, "y", 0) - JsonParse.num(pa, "y", 0);
            return Math.sqrt((double) dx * dx + (double) dy * dy) < 3;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String between(String beforeJson, String afterJson) {
        if (beforeJson == null || beforeJson.isBlank()) {
            // The opening page has no interval, but it still needs the rule -
            // otherwise page one is the one page free to invent a walk.
            return """
                   ### WHAT HAS CHANGED SINCE THE LAST PAGE

                   Nothing yet - this is the opening. The survivor is exactly \
                   where the state puts them and has done nothing you can \
                   describe as an action. Do not write them crossing a room, \
                   opening a door or looking out of a window.
                   """;
        }
        Map<String, Object> a, b;
        try {
            a = JsonParse.parseObject(beforeJson);
            b = JsonParse.parseObject(afterJson);
        } catch (Throwable t) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        // "has not moved" is a caution, not news - it must not make an
        // otherwise empty interval look eventful.
        List<String> cautions = new ArrayList<>();
        time(a, b, lines);
        place(a, b, lines, cautions);
        skills(a, b, lines);
        dead(a, b, lines);
        racket(a, b, lines);
        wheels(a, b, lines);
        body(a, b, lines);
        grid(a, b, lines);
        shelter(a, b, lines);
        things(a, b, lines);

        if (lines.isEmpty()) {
            return """
                   ### WHAT HAS CHANGED SINCE THE LAST PAGE

                   Nothing measurable. No time has passed, nothing was learned, \
                   nothing was taken or lost, and the survivor has not moved.

                   Do NOT re-describe the room; the last page already did that. \
                   A page about a moment in which nothing changes must be about \
                   the waiting itself - what the stillness is starting to cost, \
                   what the mind does when the body has nothing to do. Go inward \
                   or go quiet, but do not repeat.
                   """;
        }

        StringBuilder sb = new StringBuilder(1024);
        sb.append("### WHAT HAS CHANGED SINCE THE LAST PAGE\n");
        sb.append("This is what the page is about. Look here FIRST - it is the "
                + "only part of the state that is news. The rest is scenery the "
                + "last page already covered, and repeating it is the single "
                + "most common way this story goes dull.\n\n");
        for (String l : lines) sb.append("- ").append(l).append('\n');
        for (String l : cautions) sb.append("- ").append(l).append('\n');
        sb.append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ bits

    private static void time(Map<String, Object> a, Map<String, Object> b, List<String> out) {
        Map<String, Object> ta = JsonParse.map(a, "time"), tb = JsonParse.map(b, "time");
        if (ta == null || tb == null) return;
        double ha = dbl(ta, "worldAgeHours"), hb = dbl(tb, "worldAgeHours");
        double d = hb - ha;
        if (d <= 0.02) return;
        if (d < 1) out.add(Math.round(d * 60) + " minutes have passed.");
        else if (d < 24) out.add(String.format("%.1f hours have passed.", d));
        else out.add(String.format("%.1f days have passed.", d / 24.0));
    }

    private static void place(Map<String, Object> a, Map<String, Object> b,
                              List<String> out, List<String> cautions) {
        Map<String, Object> pa = JsonParse.map(a, "position"), pb = JsonParse.map(b, "position");
        if (pa == null || pb == null) return;
        int dx = JsonParse.num(pb, "x", 0) - JsonParse.num(pa, "x", 0);
        int dy = JsonParse.num(pb, "y", 0) - JsonParse.num(pa, "y", 0);
        int dist = (int) Math.round(Math.sqrt((double) dx * dx + (double) dy * dy));

        String ra = JsonParse.str(pa, "room", null), rb = JsonParse.str(pb, "room", null);
        boolean roomChanged = ra != null ? !ra.equals(rb) : rb != null;

        // Stairs. Two tiles sideways and a whole floor is not "has not moved",
        // and it is worth a line of its own - climbing is effort, and the sound
        // a staircase makes is one of the few noises she makes on purpose.
        int za = JsonParse.num(pa, "z", 0), zb = JsonParse.num(pb, "z", 0);
        if (zb != za) {
            out.add("They have gone " + (zb > za ? "UP" : "DOWN") + " "
                    + Math.abs(zb - za) + " floor"
                    + (Math.abs(zb - za) == 1 ? "" : "s") + ".");
        }

        // Screen directions only: the view is isometric, so a compass bearing
        // would be meaningless to the player reading the page.
        if (dist >= 3) {
            String across = dx - dy > 0 ? "right" : "left";
            String updown = dx + dy > 0 ? "down" : "up";
            String how = dist < 15 ? "a short way" : dist < 120 ? "some distance"
                       : dist < 500 ? "a few streets" : "right across town";
            out.add("The survivor has moved " + how + " (" + dist + " paces, "
                    + across + " and " + updown + " on screen).");
        } else if (!roomChanged && zb == za) {
            cautions.add("The survivor has NOT moved. Do not write them walking "
                    + "anywhere, and do not describe the room again.");
        }
        if (roomChanged && rb != null) {
            String where = JsonParse.str(pb, "floor", "");
            out.add("They are now in a different room: " + rb
                    + (where.isEmpty() ? "" : ", " + where)
                    + ". This is NOT the "
                    + (ra == null ? "room" : ra)
                    + " from the last page, and if it shares a name with a room "
                    + "they have been in before it is still a different room "
                    + "with different things in it. Read `here` again; do not "
                    + "carry furniture over from anywhere else.");
        }
    }

    private static void skills(Map<String, Object> a, Map<String, Object> b, List<String> out) {
        Map<String, Object> sa = JsonParse.map(a, "skills"), sb = JsonParse.map(b, "skills");
        if (sb == null) return;
        for (Map.Entry<String, Object> e : sb.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> nb)) continue;
            int lvlB = JsonParse.num(nb, "level", 0);
            double xpB = dbl(nb, "xp");
            int lvlA = 0; double xpA = 0;
            Object oa = sa == null ? null : sa.get(e.getKey());
            if (oa instanceof Map<?, ?> na) {
                lvlA = JsonParse.num(na, "level", 0);
                xpA = dbl(na, "xp");
            }
            if (lvlB > lvlA) {
                out.add("**" + e.getKey() + " has gone up to level " + lvlB
                        + "** - they are measurably better at this than they were.");
            } else if (xpB - xpA > 1) {
                out.add(e.getKey() + " has improved (" + Math.round(xpB - xpA)
                        + " points of practice).");
            }
        }
    }

    private static void dead(Map<String, Object> a, Map<String, Object> b, List<String> out) {
        Object da = a.get("theDead"), db = b.get("theDead");
        boolean was = da instanceof Map<?, ?>, now = db instanceof Map<?, ?>;
        if (!was && now) {
            out.add("**They are here now** - " + JsonParse.str(db, "withinSight", "some")
                    + " within sight, where a moment ago there were none.");
        } else if (was && !now) {
            out.add("The dead that were in sight are gone. It is quiet again.");
        } else if (was && now) {
            String x = JsonParse.str(da, "withinSight", ""), y = JsonParse.str(db, "withinSight", "");
            if (!x.equals(y)) out.add("The number outside has changed: " + x + " -> " + y + ".");
            boolean chasedBefore = da instanceof Map<?, ?> m1 && m1.containsKey("comingForHer");
            boolean chasedNow = db instanceof Map<?, ?> m2 && m2.containsKey("comingForHer");
            if (!chasedBefore && chasedNow) out.add("**They have noticed her.**");
            if (chasedBefore && !chasedNow) out.add("Whatever was following has lost her.");
        }
    }

    /** Getting into, or out of, a vehicle. In a road story this IS the plot. */
    private static void wheels(Map<String, Object> a, Map<String, Object> b, List<String> out) {
        Map<String, Object> va = JsonParse.map(a, "inAVehicle"),
                            vb = JsonParse.map(b, "inAVehicle");
        if (va == null && vb != null) {
            out.add("**THEY HAVE GOT INTO A VEHICLE** - the "
                    + JsonParse.str(vb, "model", "one they found")
                    + ", and they are sitting in it now. After a week on foot "
                    + "this is enormous. Write them IN it.");
        } else if (va != null && vb == null) {
            out.add("They have got out of the vehicle and are back on their feet.");
        } else if (va != null) {
            boolean ea = Boolean.TRUE.equals(va.get("engineRunning"));
            boolean eb = Boolean.TRUE.equals(vb.get("engineRunning"));
            if (!ea && eb) out.add("**THE ENGINE HAS STARTED.** It caught. It is "
                    + "running, and it is the loudest thing for streets around.");
            else if (ea && !eb) out.add("The engine has stopped.");
        }
    }

    /** A noise that was not there a moment ago - the loudest news there is. */
    private static void racket(Map<String, Object> a, Map<String, Object> b, List<String> out) {
        Map<String, Object> na = JsonParse.map(a, "noise"), nb = JsonParse.map(b, "noise");
        if (nb == null) {
            if (na != null) out.add("The noise has stopped. It is quiet again, "
                    + "and whatever it called is still out there.");
            return;
        }
        String what = JsonParse.str(nb, "what", "a noise");
        if (na == null) {
            out.add("**IT HAS STARTED: " + what + "** - "
                    + JsonParse.str(nb, "howFar", "close by")
                    + ". This is the page. Nothing else in the state comes near it.");
        } else if (!what.equals(JsonParse.str(na, "what", ""))) {
            out.add("**The noise has changed: " + what + "**.");
        }
    }

    private static void body(Map<String, Object> a, Map<String, Object> b, List<String> out) {
        Map<String, Object> ha = JsonParse.map(a, "health"), hb = JsonParse.map(b, "health");
        if (hb == null) return;
        double oa = ha == null ? 100 : dbl(ha, "overall"), ob = dbl(hb, "overall");
        if (ob < oa - 1) out.add("They are hurt worse than they were.");
        else if (ob > oa + 1) out.add("They have healed a little.");

        int wa = ha == null ? 0 : JsonParse.num(ha, "partsBitten", 0);
        if (JsonParse.num(hb, "partsBitten", 0) > wa) out.add("**They have been BITTEN.**");
        int sa = ha == null ? 0 : JsonParse.num(ha, "partsScratched", 0);
        if (JsonParse.num(hb, "partsScratched", 0) > sa) out.add("They have been scratched.");

        Object ca = a.get("character"), cb = b.get("character");
        if (cb instanceof Map<?, ?>) {
            int ka = (ca instanceof Map<?, ?>) ? JsonParse.num(ca, "zombieKills", 0) : 0;
            int kb = JsonParse.num(cb, "zombieKills", 0);
            if (kb > ka) out.add("They have killed " + (kb - ka) + " more of them.");
        }
    }

    /**
     * The lights and the taps.
     *
     * The night the power dies is one of the largest events in a campaign and
     * for fourteen builds it reached the narrator as nothing at all. Here it
     * arrives in the one block the charter tells the narrator to read first.
     */
    private static void grid(Map<String, Object> a, Map<String, Object> b, List<String> out) {
        Map<String, Object> ua = JsonParse.map(a, "utilities"),
                            ub = JsonParse.map(b, "utilities");
        if (ua == null || ub == null) return;
        Boolean pa = bool(ua, "mainsPower"), pb = bool(ub, "mainsPower");
        if (pa != null && pb != null && pa && !pb) {
            out.add("**THE POWER HAS GONE.** Everything electric in this room "
                    + "just stopped. This is one of the biggest things that "
                    + "happens in this story - give it the page.");
        } else if (pa != null && pb != null && !pa && pb) {
            out.add("There is power here again - a generator, or somewhere the "
                    + "grid still reaches.");
        }
        Boolean wa = bool(ua, "mainsWater"), wb = bool(ub, "mainsWater");
        if (wa != null && wb != null && wa && !wb) {
            out.add("**THE WATER HAS BEEN CUT OFF.** The taps are finished. "
                    + "Whatever is standing in sinks and tubs is now all there is.");
        }
    }

    /**
     * What has been done to the room itself: curtains, locks, barricades.
     *
     * Only meaningful when he is still in the SAME room - crossing a doorway
     * changes every one of these numbers without anybody having done anything.
     * These are small acts and they are among the most deliberate things a
     * survivor ever does; drawing a curtain is a decision about being seen.
     */
    private static void shelter(Map<String, Object> a, Map<String, Object> b, List<String> out) {
        Map<String, Object> pa = JsonParse.map(a, "position"), pb = JsonParse.map(b, "position");
        if (pa == null || pb == null) return;
        if (JsonParse.num(pa, "z", 0) != JsonParse.num(pb, "z", 0)) return;
        String ra = JsonParse.str(pa, "room", null), rb = JsonParse.str(pb, "room", null);
        if (ra == null || !ra.equals(rb)) return;

        Map<String, Object> ha = JsonParse.map(a, "here"), hb = JsonParse.map(b, "here");
        if (ha == null || hb == null) return;

        Map<String, Object> wa = JsonParse.map(ha, "windows"), wb = JsonParse.map(hb, "windows");
        if (wa != null && wb != null) {
            int ca = JsonParse.num(wa, "curtainsDrawn", 0), cb = JsonParse.num(wb, "curtainsDrawn", 0);
            if (cb > ca) {
                int n = cb - ca;
                out.add("They have drawn " + (n == 1 ? "a curtain" : n + " curtains")
                        + " across the " + (n == 1 ? "window" : "windows")
                        + ". Nothing out there can see in through "
                        + (n == 1 ? "it" : "them") + " now.");
            } else if (cb < ca) {
                out.add("A curtain that was drawn is open again.");
            }
            int ba = JsonParse.num(wa, "barricaded", 0), bb = JsonParse.num(wb, "barricaded", 0);
            if (bb > ba) out.add("A window has been boarded over.");
            int sa = JsonParse.num(wa, "smashed", 0), sb2 = JsonParse.num(wb, "smashed", 0);
            if (sb2 > sa) out.add("**A window in here is broken that was not before.**");
        }

        Map<String, Object> da = JsonParse.map(ha, "doors"), db = JsonParse.map(hb, "doors");
        if (da != null && db != null) {
            int la = JsonParse.num(da, "locked", 0), lb = JsonParse.num(db, "locked", 0);
            if (lb > la) out.add("A door in here has been locked.");
            int oa = JsonParse.num(da, "open", 0), ob = JsonParse.num(db, "open", 0);
            if (ob > oa) out.add("A door in here is standing open that was shut.");
            else if (ob < oa) out.add("A door in here has been closed.");
        }

        int bodA = JsonParse.num(ha, "bodiesInSight", 0), bodB = JsonParse.num(hb, "bodiesInSight", 0);
        if (bodB > bodA) out.add("**There is a body in here that was not here before.**");
    }

    private static Boolean bool(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o instanceof Boolean b ? b : null;
    }

    /**
     * What is in his hands that was not before - and, crucially, HOW.
     *
     * The flat version of this reported "They now carry: Fanny Pack, Comb,
     * Alcohol Wipes, ID Card: Young Sparrow, Wallet, Money..." and the page
     * quite reasonably wrote him stuffing the wallet and the money into the
     * pack. He did nothing of the kind. He picked up one bag that already had
     * a stranger's life in it. Those are different events and the interval has
     * to say which one happened, because the difference is the whole scene.
     */
    private static void things(Map<String, Object> a, Map<String, Object> b, List<String> out) {
        Set<String> bagsBefore = bagNames(a), bagsAfter = bagNames(b);
        List<String> newBags = new ArrayList<>();
        for (String s : bagsAfter) if (!bagsBefore.contains(s)) newBags.add(s);

        // Anything that arrived INSIDE a bag he has only just acquired was not
        // gathered by him and must not be written as though it was.
        Set<String> arrivedInside = new LinkedHashSet<>();
        for (String bag : newBags) collect(contentsOf(b, bag), arrivedInside);

        Set<String> before = items(a), after = items(b);
        List<String> gained = new ArrayList<>(), lost = new ArrayList<>();
        for (String s : after) {
            if (before.contains(s)) continue;
            if (arrivedInside.contains(s) || newBags.contains(s)) continue;
            gained.add(s);
        }
        for (String s : before) if (!after.contains(s)) lost.add(s);

        for (String bag : newBags) {
            List<String> inside = new ArrayList<>();
            collect(contentsOf(b, bag), inside);
            if (inside.isEmpty()) {
                out.add("They have picked up a " + bag + ", empty.");
            } else {
                out.add("They have picked up a " + bag + ". It was ALREADY FULL "
                        + "when they found it - " + join(inside, 8) + " - and "
                        + "none of that is theirs or was put there by them. "
                        + "Somebody else owned this. Do NOT write them packing "
                        + "these things; write them finding out what is in it.");
            }
        }
        // Cap the list: a looting spree should read as a spree, not a manifest.
        if (!gained.isEmpty()) out.add("They now carry: " + join(gained, 8) + ".");
        if (!lost.isEmpty())   out.add("No longer carried: " + join(lost, 6) + ".");
    }

    private static Set<String> bagNames(Map<String, Object> m) {
        Set<String> out = new LinkedHashSet<>();
        if (m.get("bags") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> bag) {
                    Object n = bag.get("name");
                    if (n instanceof String s) out.add(s);
                }
            }
        }
        return out;
    }

    private static Object contentsOf(Map<String, Object> m, String bagName) {
        if (m.get("bags") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> bag && bagName.equals(bag.get("name"))) {
                    return bag.get("contents");
                }
            }
        }
        return null;
    }

    private static void collect(Object arr, List<String> into) {
        if (arr instanceof List<?> l) {
            for (Object o : l) if (o instanceof String s) into.add(s);
        }
    }

    private static Set<String> items(Map<String, Object> m) {
        Set<String> out = new LinkedHashSet<>();
        collect(m.get("stowedOnHim"), out);
        collect(m.get("wearing"), out);
        Object bags = m.get("bags");
        if (bags instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> bag) collect(bag.get("contents"), out);
            }
        }
        return out;
    }

    private static void collect(Object arr, Set<String> into) {
        if (arr instanceof List<?> l) {
            for (Object o : l) if (o instanceof String s) into.add(s);
        }
    }

    private static String join(List<String> l, int max) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(l.size(), max);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(l.get(i));
        }
        if (l.size() > n) sb.append(" and ").append(l.size() - n).append(" more");
        return sb.toString();
    }

    /** Takes Object so a wildcard Map from a nested parse works unchanged. */
    private static double dbl(Object m, String k) {
        if (!(m instanceof Map<?, ?> map)) return 0.0;
        Object o = map.get(k);
        return o instanceof Double d ? d : 0.0;
    }

    /** Trimmed copy of a snapshot, small enough to keep between pages. */
    public static String keep(String snapshot) {
        try {
            Map<String, Object> m = JsonParse.parseObject(snapshot);
            Map<String, Object> keep = new LinkedHashMap<>();
            for (String k : new String[]{"time", "position", "skills", "health",
                                         "character", "stowedOnHim", "wearing", "bags", "inHisHands",
                                         "theDead", "utilities", "here", "noise", "inAVehicle"}) {
                if (m.containsKey(k)) keep.put(k, m.get(k));
            }
            return Json.of(keep);
        } catch (Throwable t) {
            return snapshot;
        }
    }
}
