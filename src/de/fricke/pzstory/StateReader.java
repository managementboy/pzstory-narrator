package de.fricke.pzstory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import zombie.GameTime;
import zombie.characters.CharacterStat;
import zombie.characters.IsoPlayer;
import zombie.characters.SurvivorDesc;
import zombie.characters.BodyDamage.BodyDamage;
import zombie.characters.BodyDamage.Nutrition;
import zombie.characters.skills.PerkFactory;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Literature;
import zombie.iso.BuildingDef;
import zombie.iso.IsoGridSquare;
import zombie.iso.RoomDef;
import zombie.vehicles.BaseVehicle;

/**
 * Reads the live game state into a JSON snapshot for the prompt builder.
 *
 * This replaces PZWatch's Lua snapshot. Everything the Lua version had to
 * fight for is direct here: Stats has a typed get(CharacterStat) accessor in
 * 42.20.3 rather than needing toString() parsed, BodyDamage exposes per-part
 * health by index, and there is no Kahlua missing-method problem at all.
 *
 * Discipline carried over from the Lua version: every section runs under its
 * own try/catch. One unexpected null must cost us that section, never the
 * whole snapshot - a chapter written from a partial read is recoverable, a
 * crash mid-keypress is not.
 */
public final class StateReader {

    private StateReader() {}

    /** Names of the errors hit during the last read, for the debug log. */
    private static final ArrayList<String> lastErrors = new ArrayList<>();

    public static String snapshot() {
        return snapshot(Settings.knowledge());
    }

    /**
     * @param knowledge how much of the world the narrator may see.
     *                  See {@link Settings} - this is the player's dial,
     *                  not a rule of the mod.
     */
    public static String snapshot(int knowledge) {
        lastErrors.clear();
        Json j = new Json().obj();
        j.put("schema", 1);
        j.put("modVersion", Main.VERSION);
        j.put("wallClock", System.currentTimeMillis());

        IsoPlayer p = null;
        try {
            p = IsoPlayer.getInstance();
        } catch (Throwable t) {
            note("getInstance", t);
        }

        if (p == null) {
            j.put("error", "no player - main menu, or called before the world loaded");
            errors(j);
            return j.endObj().toString();
        }

        gameTime(j);
        character(j, p);
        position(j, p);
        stats(j, p);
        feeling(j, p);
        doingAndWeather(j, p);
        noise(j, p);
        utilities(j, p);
        threat(j, p);
        nutrition(j, p);
        health(j, p);
        skills(j, p);
        inventory(j, p);
        if (knowledge >= Settings.KNOW_GLANCE) surroundings(j, p);
        else basicPlace(j, p);

        errors(j);
        return j.endObj().toString();
    }

    // ---------------------------------------------------------------- time

    private static void gameTime(Json j) {
        try {
            GameTime gt = GameTime.getInstance();
            if (gt == null) return;
            j.objKey("time");
            // Day and month are 0-indexed in the save; GameTime reports the
            // same way, so add one before anything human reads it.
            j.put("year", gt.getYear());
            j.put("month", gt.getMonth() + 1);
            j.put("day", gt.getDay() + 1);
            j.put("hour", gt.getHour());
            j.put("minute", gt.getMinutes());
            j.put("nightsSurvived", gt.getNightsSurvived());
            j.put("worldAgeHours", gt.getWorldAgeHours());
            // How far past the first morning we are. World.KNOX dates the Knox
            // timeline relative to this rather than to a fixed calendar,
            // because the start date is a sandbox option the player can move.
            try {
                j.put("daysSinceItBegan", (int) gt.getWorldAgeDaysSinceBegin());
            } catch (Throwable ignored) { }
            j.endObj();
        } catch (Throwable t) {
            note("time", t);
        }
    }

    // ----------------------------------------------------------- character

    private static void character(Json j, IsoPlayer p) {
        try {
            j.objKey("character");
            j.put("username", p.getUsername());
            SurvivorDesc d = p.getDescriptor();
            if (d != null) {
                j.put("forename", d.getForename());
                j.put("surname", d.getSurname());
                j.put("female", d.isFemale());
                // Spelled out, not left as a boolean to be inferred.
                j.put("pronouns", d.isFemale() ? "she/her" : "he/him");
            }
            // Who he actually is. The richest story material in the save and
            // the last thing the snapshot was missing: 24 stats and every item
            // in his pockets, but no idea he was a park ranger who is afraid
            // of small spaces.
            try {
                if (d != null && d.getCharacterProfession() != null) {
                    j.put("occupation", d.getCharacterProfession().getName());
                }
            } catch (Throwable t) {
                note("occupation", t);
            }
            // Traits, WITH their polarity and what they mean.
            //
            // A bare list of names asks the narrator to guess twice: what the
            // trait does, and whether it is a virtue or a burden. For
            // "Claustrophobic" that is fine. For Thin-skinned, Conspicuous,
            // Desensitized, Crafty or Adrenaline Junkie it will sometimes guess
            // wrong, and a page that treats a burden as a gift misreads the
            // character at the root.
            //
            // getCost() is the polarity: a POSITIVE cost is a trait the player
            // spent points to have, a negative one is a flaw they took in order
            // to afford something else. That is precisely the biography signal
            // the charter asks the narrator to dramatise.
            try {
                var ct = p.getCharacterTraits();
                if (ct != null) {
                    var known = ct.getKnownTraits();
                    if (known != null && !known.isEmpty()) {
                        j.arrKey("traits");
                        for (var tr : known) {
                            if (tr == null) continue;
                            String n = tr.getName();
                            if (n == null || n.isEmpty()) continue;
                            j.obj();
                            // The internal id ("cook2", "fastreader") is not a
                            // name and must never be the first thing the model
                            // reads. The UI name is what a person would call it.
                            String pretty = null;
                            try {
                                var d0 = zombie.characters.traits.CharacterTraitDefinition
                                        .getCharacterTraitDefinition(tr);
                                if (d0 != null) {
                                    String ui = d0.getUIName();
                                    if (ui != null && !ui.isBlank()) pretty = ui;
                                }
                            } catch (Throwable ignored) { }
                            j.put("name", pretty != null ? pretty : n);
                            try {
                                var def = zombie.characters.traits.CharacterTraitDefinition
                                        .getCharacterTraitDefinition(tr);
                                if (def != null) {
                                    int cost = def.getCost();
                                    // A free trait is one the OCCUPATION grants -
                                    // a burger flipper's Keen Cook. Reporting
                                    // that as "neither a gift nor a burden" threw
                                    // away the most interesting thing about it.
                                    j.put("kind", def.isFree() || cost == 0
                                            ? "came with their trade, not chosen"
                                            : cost > 0 ? "a strength, chosen and paid for"
                                            : "a weakness, taken on purpose");
                                    String what = def.getDescription();
                                    if (what != null && !what.isBlank()) {
                                        j.put("means", what.replace('\n', ' ').trim());
                                    }
                                }
                            } catch (Throwable t) {
                                note("trait[" + n + "]", t);
                            }
                            j.endObj();
                        }
                        j.endArr();
                    }
                }
            } catch (Throwable t) {
                note("traits", t);
            }

            j.put("hoursSurvived", p.getHoursSurvived());
            j.put("zombieKills", p.getZombieKills());
            j.put("asleep", p.isAsleep());
            j.put("inventoryWeight", p.getInventoryWeight());
            j.endObj();
        } catch (Throwable t) {
            note("character", t);
        }
    }

    // ------------------------------------------------------------ position

    private static void position(Json j, IsoPlayer p) {
        try {
            j.objKey("position");
            int x = (int) p.getX();
            int y = (int) p.getY();
            int z = (int) p.getZ();
            j.put("x", x);
            j.put("y", y);
            j.put("z", z);
            // Which floor, in words. Houses have two living rooms and three
            // bedrooms, and the room NAME alone cannot tell them apart - the
            // page walked upstairs into a second living room, decided it was
            // back in the first one, and put its refrigerator in the corner.
            j.put("floor", z < 0 ? "in the basement"
                    : z == 0 ? "on the ground floor"
                    : z == 1 ? "one floor up"
                    : z + " floors up");
            // Chunk and cell, as used when cross-checking against the save.
            j.put("chunkX", x / 8);
            j.put("chunkY", y / 8);
            j.put("cellX", x / 300);
            j.put("cellY", y / 300);

            IsoGridSquare sq = p.getCurrentSquare();
            if (sq != null) {
                RoomDef room = sq.getRoomDef();
                if (room != null) {
                    j.put("room", room.getName());
                    // Words, not a count. Reported as a number it read like a
                    // measurement and came back as "the thirty-foot room" -
                    // a statistic quoted, in a unit that was never true. A
                    // person does not know a room's floor area; they know it
                    // is poky or that it echoes.
                    j.put("roomFeels", roomWord(sq.getRoomSize()));
                }
                BuildingDef b = sq.getBuildingDef();
                if (b != null) {
                    j.objKey("building");
                    j.put("id", b.getID());
                    j.put("x", b.getX());
                    j.put("y", b.getY());
                    j.put("w", b.getW());
                    j.put("h", b.getH());
                    j.put("roomCount", b.getRooms() != null ? b.getRooms().size() : 0);
                    j.endObj();
                } else {
                    j.put("outdoors", true);
                }
            }

            // The map's own name for this spot, when it has one. This is the
            // fourth of the four gaps: without it the model guessed "Muldraugh"
            // and a guessed town becomes permanent the moment it is written.
            try {
                var mg = zombie.iso.IsoWorld.instance == null
                        ? null : zombie.iso.IsoWorld.instance.getMetaGrid();
                if (mg != null) {
                    var zone = mg.getZoneAt(x, y, z);
                    if (zone != null) {
                        String zn = zone.getName();
                        String zt = zone.getType();
                        if (zn != null && !zn.isEmpty() && !zn.equalsIgnoreCase("null")) {
                            j.put("placeName", zn);
                        }
                        if (zt != null && !zt.isEmpty()) j.put("placeType", zt);
                    }
                }
            } catch (Throwable t) {
                note("place", t);
            }

            j.endObj();
            vehicle(j, p);
        } catch (Throwable t) {
            note("position", t);
        }
    }

    /**
     * Sitting in a vehicle.
     *
     * This used to be one opaque line buried inside `position`:
     * `"vehicle":{"script":"Base.fhqB10M_Riv"}`. A page written from that put
     * the survivor standing on a road turning a key over in his hand, while he
     * was in fact sat in the driver's seat of the bus it belongs to. The fact
     * was technically present and completely unreadable, and it was filed
     * under co-ordinates rather than under "what is happening".
     *
     * For a road story this is the single most consequential thing that can be
     * true, so it now gets its own block, near the top, in plain words.
     */
    private static void vehicle(Json j, IsoPlayer p) {
        try {
            BaseVehicle v = p.getVehicle();
            if (v == null) return;
            j.objKey("inAVehicle");
            j.put("note", "THE SURVIVOR IS SITTING IN THIS VEHICLE RIGHT NOW. "
                    + "Not beside it, not looking at it - in it, with the door "
                    + "shut behind them. Any page that has them standing "
                    + "outside is wrong.");

            // "Base.fhqB10M_Riv" is a file name, not something a person says.
            String script = String.valueOf(v.getScriptName());
            j.put("model", Vehicles.name(script));
            if (!Vehicles.known(script)) {
                // Say so rather than letting a script id be quoted as a make.
                j.put("modelUnknown", "This is the game's internal id, not a "
                        + "name. Do not print it. Call it what it looks like - "
                        + "a car, a van, a truck.");
            }

            try { j.put("driving", v.isDriver(p)); } catch (Throwable ignored) { }
            try { j.put("engineRunning", v.isEngineStarted()); } catch (Throwable ignored) { }
            try { j.put("keysInIgnition", v.isKeysInIgnition()); } catch (Throwable ignored) { }
            try {
                float f = v.getRemainingFuelPercentage();
                j.put("fuel", f <= 0.5f ? "the tank is dry"
                        : f < 10 ? "almost nothing in the tank"
                        : f < 30 ? "under a quarter of a tank"
                        : f < 70 ? "about half a tank"
                        : "a full tank");
            } catch (Throwable ignored) { }
            try {
                int c = v.getEngineCondition();
                j.put("engine", c <= 0 ? "the engine is wrecked"
                        : c < 30 ? "the engine is in a bad way"
                        : c < 70 ? "the engine has seen better days"
                        : "the engine sounds healthy");
            } catch (Throwable ignored) { }
            try { if (v.getHeadlightsOn()) j.put("headlightsOn", true); } catch (Throwable ignored) { }
            try {
                float kph = v.getCurrentSpeedKmHour();
                if (Math.abs(kph) > 1) j.put("moving", true);
            } catch (Throwable ignored) { }
            try { if (v.isDoorAlarmSounding()) j.put("itsAlarmIsGoingOff", true); } catch (Throwable ignored) { }
            j.endObj();
        } catch (Throwable t) {
            note("vehicle", t);
        }
    }

    // --------------------------------------------------------------- stats

    private static void stats(Json j, IsoPlayer p) {
        try {
            var st = p.getStats();
            if (st == null) return;
            j.objKey("stats");
            // CharacterStat is a registry, not an enum: ORDERED_STATS is the
            // canonical list and it includes anything a mod registered, so
            // iterating it keeps us correct without a hardcoded field list.
            for (CharacterStat cs : CharacterStat.ORDERED_STATS) {
                if (cs == null) continue;
                j.put(cs.getId(), st.get(cs));
            }
            j.endObj();

            j.objKey("threat");
            j.put("zombiesVisible", st.getNumVisibleZombies());
            j.put("zombiesChasing", st.getNumChasingZombies());
            j.put("zombiesVeryClose", st.getNumVeryCloseZombies());
            j.endObj();
        } catch (Throwable t) {
            note("stats", t);
        }
    }

    /**
     * The moodles: the game's own judgement about which number has crossed
     * into being worth a person's attention.
     *
     * This is the single most useful block in the snapshot and it was missing
     * for fourteen builds. The stats give raw floats and leave the narrator to
     * invent a threshold - is 0.41 stress "a bit tense" or "coming apart"? The
     * moodle answers that question the way the game answers it for the player,
     * in tiers, in the player's own vocabulary.
     *
     * It also reaches nine conditions no stat we read exposes at all: wet,
     * a cold coming on, anger, hyperthermia, hypothermia, windchill, drunk,
     * a noxious smell, plain discomfort. World.RULES tells the narrator that
     * being soaked and cold is dangerous; until now nothing told it she was.
     *
     * Reflected over the registry rather than hardcoded, so a mod that
     * registers its own moodle appears here for free.
     */
    private static void feeling(Json j, IsoPlayer p) {
        try {
            var mo = p.getMoodles();
            if (mo == null) return;
            boolean opened = false;
            for (java.lang.reflect.Field f
                    : zombie.scripting.objects.MoodleType.class.getFields()) {
                try {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() != zombie.scripting.objects.MoodleType.class) continue;
                    String id = f.getName();
                    // DEAD and ZOMBIE are end states, not feelings. If either is
                    // set the story is over and no page is being written.
                    if ("DEAD".equals(id) || "ZOMBIE".equals(id)) continue;

                    var mt = (zombie.scripting.objects.MoodleType) f.get(null);
                    if (mt == null) continue;
                    int lvl = mo.getMoodleLevel(mt);
                    if (lvl <= 0) continue;

                    if (!opened) {
                        j.objKey("feeling");
                        j.put("note", "What the survivor can actually feel right "
                                + "now, as the game itself rates it. Level 1 is "
                                + "barely there; 4 is as bad as it gets. These "
                                + "outrank the raw numbers in `stats` - if "
                                + "something is here, it is worth a line.");
                        opened = true;
                    }
                    j.objKey(id);
                    String says = null;
                    try { says = mo.getMoodleDisplayString(mt); } catch (Throwable ignored) { }
                    if (says != null && !says.isBlank()) j.put("says", says.trim());
                    j.put("level", lvl);
                    j.endObj();
                } catch (Throwable t) {
                    note("moodle[" + f.getName() + "]", t);
                }
            }
            if (opened) j.endObj();
        } catch (Throwable t) {
            note("feeling", t);
        }
    }

    /**
     * Whether the lights and the taps still work, here, now.
     *
     * The night the power dies is one of the two or three biggest beats in a
     * campaign, and until now it reached the narrator only as an absence it had
     * no instrument to detect. Both of these are one call each, and Delta turns
     * them into news the moment they flip.
     */
    private static void utilities(Json j, IsoPlayer p) {
        try {
            j.objKey("utilities");
            try {
                IsoGridSquare sq = p.getCurrentSquare();
                if (sq != null) {
                    // hasGridPower() is the MAINS. haveElectricity() is a
                    // generator running on this square - a completely different
                    // fact, and reading it as the mains reported the power dead
                    // at seven in the morning on the first day. The game's own
                    // ISVehicleMenu uses both, side by side, which is what gave
                    // the distinction away.
                    j.put("mainsPower", sq.hasGridPower());
                    if (sq.haveElectricity()) {
                        j.put("generatorRunningHere", true);
                    }
                }
            } catch (Throwable t) {
                note("power", t);
            }
            try {
                var w = zombie.iso.IsoWorld.instance;
                if (w != null) j.put("mainsWater", w.isHydroPowerOn());
            } catch (Throwable t) {
                note("water", t);
            }
            // How close the failure is, in words rather than a day count. The
            // narrator is forbidden to quote a number and could not use one
            // anyway; what it needs is whether to write the grid as solid
            // background or as something already going.
            try {
                var so = zombie.SandboxOptions.getInstance();
                GameTime gt = GameTime.getInstance();
                if (so != null && gt != null) {
                    // The game's own formula, lifted from ISButtonPrompt: the
                    // start option shifts the clock forward a month at a time.
                    double elapsed = gt.getWorldAgeHours() / 24.0
                            + (so.getTimeSinceApo() - 1) * 30.0;
                    String e = outlook(so.getElecShutModifier(), elapsed);
                    String w = outlook(so.getWaterShutModifier(), elapsed);
                    if (e != null) j.put("powerOutlook", e);
                    if (w != null) j.put("waterOutlook", w);
                }
            } catch (Throwable t) {
                note("outlook", t);
            }
            j.endObj();
        } catch (Throwable t) {
            note("utilities", t);
        }
    }

    /** Days remaining until a utility fails, as something a person could feel. */
    private static String outlook(int shutDay, double elapsed) {
        if (shutDay < 0) return "it is not going to fail in this world";
        double left = shutDay - elapsed;
        if (left <= 0)  return "already gone";
        if (left <= 3)  return "days at most - it could go at any time";
        if (left <= 10) return "a week or two left, no more";
        if (left <= 30) return "weeks yet, but it is coming";
        return "no sign of it going any time soon";
    }

    /**
     * What can be HEARD right now.
     *
     * The audit called meta-events unwritable because nothing exposed them
     * live. That was wrong: WorldSoundManager keeps every active sound in the
     * world, with a position, a radius and a repeating flag, and a house alarm
     * is simply a loud repeating sound that will not stop. So is a car alarm,
     * a siren, a gunshot, a smashing window.
     *
     * This is the single most important thing the state can report, because
     * sound is what brings them. A survivor who has just set off an alarm is
     * in a completely different story from one standing in a quiet house, and
     * until now the page could not tell the two apart.
     */
    private static void noise(Json j, IsoPlayer p) {
        try {
            var wsm = zombie.WorldSoundManager.instance;
            if (wsm == null) return;
            var list = wsm.soundList;
            if (list == null || list.isEmpty()) return;

            int px = (int) p.getX(), py = (int) p.getY();
            Object best = null;
            double bestScore = 0;
            int heard = 0, alarms = 0;

            int n = Math.min(list.size(), 400);
            for (int i = 0; i < n; i++) {
                var s = list.get(i);
                if (s == null) continue;
                int dx = s.x - px, dy = s.y - py;
                double dist = Math.sqrt((double) dx * dx + (double) dy * dy);
                if (s.radius <= 0 || dist > s.radius) continue;   // out of earshot
                heard++;
                if (s.repeating && s.radius >= 40) alarms++;
                // Loud and close beats loud and far.
                double score = s.radius * 2 + s.volume - dist;
                if (best == null || score > bestScore) { bestScore = score; best = s; }
            }
            if (heard == 0) return;

            var s = (zombie.WorldSoundManager.WorldSound) best;
            int dx = s.x - px, dy = s.y - py;
            double dist = Math.sqrt((double) dx * dx + (double) dy * dy);

            j.objKey("noise");
            boolean siren = s.repeating && s.radius >= 40;
            j.put("what", siren
                    ? "something is going off and it is NOT stopping - an alarm or a siren"
                    : s.radius >= 120 ? "one very loud noise, already fading"
                    : "a noise close by");
            j.put("howFar", dist < 8 ? "right here, in this room or the next"
                    : dist < 30 ? "inside this building or just outside it"
                    : dist < 90 ? "a street or so away"
                    : dist < 250 ? "somewhere across the neighbourhood"
                    : "far off");
            if (dist >= 8) {
                j.put("direction", (dx - dy > 0 ? "right" : "left")
                        + " and " + (dx + dy > 0 ? "down" : "up") + " on screen");
            }
            j.put("carries", s.radius >= 150 ? "for a very long way"
                    : s.radius >= 60 ? "well beyond this street" : "not far");
            if (s.stressZombies) {
                j.put("theDeadCanHearIt", true);
            }
            if (alarms > 0) {
                j.put("note", "THIS OUTRANKS EVERYTHING EXCEPT THE DEAD "
                        + "THEMSELVES. A sound like this is how a house fills "
                        + "up. Every one of them within earshot is already "
                        + "walking toward it, and it will keep calling them "
                        + "until it stops. The page is about this.");
            }
            j.endObj();
        } catch (Throwable t) {
            note("noise", t);
        }
    }

    private static void nutrition(Json j, IsoPlayer p) {
        try {
            Nutrition n = p.getNutrition();
            if (n == null) return;
            j.objKey("nutrition");
            // NOTE: calories is a slow weight budget, NOT a hunger signal.
            // Hunger lives in stats.hunger. Do not let the narrator confuse them.
            j.put("calories", n.getCalories());
            j.put("carbohydrates", n.getCarbohydrates());
            j.put("proteins", n.getProteins());
            j.put("lipids", n.getLipids());
            j.put("weight", n.getWeight());
            j.endObj();
        } catch (Throwable t) {
            note("nutrition", t);
        }
    }

    // -------------------------------------------------------------- health

    private static void health(Json j, IsoPlayer p) {
        try {
            BodyDamage bd = p.getBodyDamage();
            if (bd == null) return;
            j.objKey("health");
            j.put("overall", bd.getHealth());
            j.put("infected", bd.IsInfected());
            j.put("fakeInfected", bd.IsFakeInfected());
            j.put("apparentInfectionLevel", bd.getApparentInfectionLevel());
            j.put("partsBleeding", bd.getNumPartsBleeding());
            j.put("partsBitten", bd.getNumPartsBitten());
            j.put("partsScratched", bd.getNumPartsScratched());
            j.put("neckBleeding", bd.isNeckBleeding());

            // Only report parts that are not pristine. A list of 17 "100.0"
            // entries is prompt noise; an injury is the story.
            //
            // Health alone is NOT enough to decide that. Diffing against
            // PZWatch caught this: a strained arm sits at health 100 and was
            // silently dropped, yet pain and stiffness are exactly the small
            // detail that makes a chapter feel observed rather than reported.
            j.arrKey("wounds");
            for (int i = 0; i < 17; i++) {
                try {
                    float hp = bd.getBodyPartHealth(i);
                    boolean bitten = bd.IsBitten(i);
                    boolean scratched = bd.IsScratched(i);
                    boolean bleeding = bd.IsBleeding(i);

                    var part = bd.getBodyPart(zombie.characters.BodyDamage.BodyPartType.FromIndex(i));
                    float pain = part != null ? part.getPain() : 0f;
                    float stiff = part != null ? part.getStiffness() : 0f;
                    float burn = part != null ? part.getBurnTime() : 0f;
                    float deep = part != null ? part.getDeepWoundTime() : 0f;

                    boolean interesting = hp < 99.9f || bitten || scratched || bleeding
                            || pain > 0.01f || stiff > 0.01f || burn > 0f || deep > 0f;
                    if (!interesting) continue;

                    j.obj();
                    j.put("part", bd.getBodyPartName(i));
                    if (hp < 99.9f) j.put("health", hp);
                    if (pain > 0.01f) j.put("pain", pain);
                    if (stiff > 0.01f) j.put("stiffness", stiff);
                    if (bitten) j.put("bitten", true);
                    if (scratched) j.put("scratched", true);
                    if (bleeding) j.put("bleeding", true);
                    if (bleeding && bd.IsBleedingStemmed(i)) j.put("stemmed", true);
                    if (deep > 0f) j.put("deepWound", true);
                    if (burn > 0f) j.put("burned", true);
                    if (part != null && part.getBandageType() != null) {
                        j.put("bandaged", part.getBandageType());
                    }
                    if (part != null && part.getSplintItem() != null) {
                        j.put("splinted", true);
                    }
                    j.endObj();
                } catch (Throwable t) {
                    // A single part failing must not lose the rest - but it
                    // must not vanish either. Swallowing this silently is what
                    // hid the missing strain reading for a whole build cycle.
                    note("wound[" + i + "]", t);
                }
            }
            j.endArr();
            // Verified 2026-08-23 against a full 17-part unfiltered dump:
            // indices map correctly, no nulls, no exceptions. An empty wounds
            // array means she really is unhurt.
            j.endObj();
        } catch (Throwable t) {
            note("health", t);
        }
    }

    // -------------------------------------------------------------- skills

    private static void skills(Json j, IsoPlayer p) {
        try {
            var xp = p.getXp();
            j.objKey("skills");
            for (PerkFactory.Perk perk : PerkFactory.PerkList) {
                if (perk == null) continue;
                // Parent entries ("Passiv", "Agility"...) are categories, not skills.
                if (perk.getParent() == perk) continue;

                int lvl = p.getPerkLevel(perk);
                float points = 0f;
                try {
                    if (xp != null) points = xp.getXP(perk);
                } catch (Throwable ignored) { }

                // Keep level 0 when XP has been earned. "Has swung at something
                // and is starting to learn" is a different character from
                // "has never tried", and the old level>0 filter erased it.
                if (lvl <= 0 && points <= 0f) continue;

                String name = perk.getName();
                if (name == null || name.isEmpty()) name = perk.getId();
                j.objKey(name);
                j.put("level", lvl);
                j.put("xp", points);
                // Where the skill CAME from. A carpentry level that arrived off
                // a book reads differently from one earned with a hammer, and
                // until now the two were indistinguishable.
                try {
                    if (xp != null) {
                        int boost = xp.getPerkBoost(perk);
                        if (boost > 0) j.put("fromTheirTrade", boost);
                        float mult = xp.getMultiplier(perk);
                        if (mult > 1.01f) {
                            j.put("learningFaster", "a book they have read is still helping");
                        }
                    }
                } catch (Throwable ignored) {
                    // Optional colour. Never worth losing the skill block for.
                }
                j.endObj();
            }
            j.endObj();
        } catch (Throwable t) {
            note("skills", t);
        }
    }

    // ----------------------------------------------------------- inventory

    private static void inventory(Json j, IsoPlayer p) {
        try {
            // WHAT IS IN HIS HANDS, stated even when the answer is nothing.
            //
            // "carrying" means somewhere on his person - a pocket, a bag, the
            // bottom of a sack. A page read a tree branch out of that list and
            // laid it across his knees, which is a placement the game never
            // asserted. Only these two slots are actually held.
            j.objKey("inHisHands");
            InventoryItem prim = p.getPrimaryHandItem();
            InventoryItem sec = p.getSecondaryHandItem();
            if (prim != null) j.put("right", prim.getName());
            if (sec != null) j.put("left", sec.getName());
            if (prim == null && sec == null) {
                j.put("nothing", "Both hands are empty. Anything in `stowedOnHim` "
                        + "is stowed - in a pocket or a bag - not held, not "
                        + "resting anywhere, and not visible.");
            }
            j.endObj();

            ItemContainer inv = p.getInventory();
            if (inv == null) return;

            // Three separate lists, because lumping them together misleads the
            // narrator. Worn jeans are not "carried gear", and the contents of
            // a bag are where nearly everything actually lives - the first
            // version listed the Sling Bag and the key ring but nothing inside
            // them, which hid most of the inventory.
            Map<String, int[]> worn = new LinkedHashMap<>();
            Map<String, int[]> held = new LinkedHashMap<>();
            ArrayList<Bag> bags = new ArrayList<>();
            ArrayList<String> readable = new ArrayList<>();

            for (InventoryItem it : inv.getItems()) {
                if (it == null) continue;
                String name = displayName(it);

                if (it instanceof zombie.inventory.types.Clothing) {
                    bump(worn, name);
                } else {
                    bump(held, name);
                }

                if (it instanceof zombie.inventory.types.InventoryContainer ic) {
                    // A bag is not Clothing, so a fanny pack strapped round his
                    // waist was landing in "carrying" beside loose objects and
                    // the page could not tell that he was WEARING it. Ask the
                    // item where it is worn.
                    // Kept OUT of the name: Delta diffs these strings, and
                    // renaming a bag when it is strapped on would report the
                    // old one lost and a new one gained.
                    Bag b = new Bag(name);
                    try {
                        if (it.isEquipped() || p.isEquipped(it)) b.worn = "worn";
                        String at = it.getAttachedSlotType();
                        if (at != null && !at.isBlank()) b.worn = at;
                    } catch (Throwable ignored) { }
                    collect(ic.getInventory(), b.contents, readable, 1);
                    // Empty bags count. An empty bag is still a bag, and "he
                    // has a bag and nothing to put in it" is a scene.
                    bags.add(b);
                }
                collectLiterature(it, readable);
            }

            emit(j, "wearing", worn);
            // Renamed from "carrying", which invited the page to put things in
            // his hands or across his lap. This is the contents of his pockets
            // and his person, nothing more.
            emit(j, "stowedOnHim", held);

            if (!bags.isEmpty()) {
                j.arrKey("bags");
                for (Bag b : bags) {
                    j.obj();
                    j.put("name", b.name);
                    if (b.worn != null) j.put("wornAt", b.worn);
                    j.arrKey("contents");
                    for (Map.Entry<String, int[]> e : b.contents.entrySet()) {
                        j.val(withCount(e.getKey(), e.getValue()[0]));
                    }
                    j.endArr();
                    j.endObj();
                }
                j.endArr();
            }

            if (!readable.isEmpty()) {
                j.arrKey("literature");
                for (String s : readable) j.val(s);
                j.endArr();
            }
        } catch (Throwable t) {
            note("inventory", t);
        }
    }

    /** A container carried on the person, with its contents counted. */
    private static final class Bag {
        final String name;
        String worn;            // null when merely held in a hand
        final Map<String, int[]> contents = new LinkedHashMap<>();
        Bag(String name) { this.name = name; }
    }

    /** Recurses into nested containers; depth-limited so a cycle cannot hang the read. */
    private static void collect(ItemContainer c, Map<String, int[]> into,
                                ArrayList<String> readable, int depth) {
        if (c == null || depth > 3) return;
        for (InventoryItem it : c.getItems()) {
            if (it == null) continue;
            bump(into, displayName(it));
            collectLiterature(it, readable);
            if (it instanceof zombie.inventory.types.InventoryContainer ic) {
                collect(ic.getInventory(), into, readable, depth + 1);
            }
        }
    }

    private static void collectLiterature(InventoryItem it, ArrayList<String> readable) {
        // Carried is not read. The narrator must be able to tell the
        // difference, so the flag ships with the title.
        if (it instanceof Literature lit) {
            readable.add(displayName(it) + (lit.alreadyRead ? " [read]" : " [unread]"));
        }
    }

    private static String displayName(InventoryItem it) {
        String name = it.getName();
        return (name == null || name.isEmpty()) ? it.getFullType() : name;
    }

    /** Counts duplicates: six keys means six doors she can lock behind her. */
    private static void bump(Map<String, int[]> m, String name) {
        m.computeIfAbsent(name, k -> new int[1])[0]++;
    }

    private static String withCount(String name, int n) {
        return n > 1 ? name + " x" + n : name;
    }

    private static void emit(Json j, String key, Map<String, int[]> m) {
        if (m.isEmpty()) return;
        j.arrKey(key);
        for (Map.Entry<String, int[]> e : m.entrySet()) {
            j.val(withCount(e.getKey(), e.getValue()[0]));
        }
        j.endArr();
    }

    /**
     * The rules THIS world was started with.
     *
     * Sandbox settings change what is true: with transmission off, a bite is
     * just a wound, and a page building dread about infection would be simply
     * wrong. Read once per campaign and carried in the cached spine.
     */
    public static String sandbox() {
        StringBuilder sb = new StringBuilder(512);
        try {
            var so = zombie.SandboxOptions.getInstance();
            if (so == null) return "";
            sb.append("### THE RULES OF THIS PARTICULAR WORLD\n");
            sb.append("The player chose these when the game began. Where they ");
            sb.append("differ from the ordinary rules above, THESE win.\n\n");
            add(sb, "The dead move", so.lore.speed);
            add(sb, "Their strength", so.lore.strength);
            add(sb, "How hard they are to kill", so.lore.toughness);
            add(sb, "How the infection spreads", so.lore.transmission);
            add(sb, "How fast it kills", so.lore.mortality);
            add(sb, "How they find you", so.lore.cognition);
            add(sb, "Their memory", so.lore.memory);
            add(sb, "Their eyesight", so.lore.sight);
            add(sb, "Their hearing", so.lore.hearing);
            add(sb, "How many", so.zombies);
            add(sb, "Day length", so.dayLength);

            // The rest of what actually changes the world. Every one of these
            // was a gap the audit found, and most of them contradict something
            // World.RULES asserts flatly.
            add(sb, "Mains electricity fails", so.elecShut);
            add(sb, "Running water fails", so.waterShut);
            add(sb, "House alarms", so.alarm);
            add(sb, "Car alarms", so.carAlarm);
            add(sb, "The world reclaiming itself", so.erosionSpeed);
            add(sb, "Helicopters", so.helicopter);
            add(sb, "Distant gunshots and screams", so.metaEvent);
            add(sb, "The dead coming back", so.zombieRespawn);
            add(sb, "Buildings restocking", so.hoursForLootRespawn);

            // The shutoff windows are numbers, not enum labels, so they need
            // their own line - and they are the point. "Nobody knows the day"
            // was wrong: the save knows roughly when the grid goes.
            try {
                int e = so.getElecShutModifier(), w = so.getWaterShutModifier();
                if (e > 0 || w > 0) {
                    sb.append("- Roughly when the utilities go: power around day ")
                      .append(e).append(", water around day ").append(w)
                      .append(" of the outbreak. NEVER state a date; this is so ")
                      .append("you know whether the failure is near or far off.\n");
                }
            } catch (Throwable ignored) { }

            sb.append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void add(StringBuilder sb, String label, Object opt) {
        if (opt == null) return;
        String s = null;
        // Enum options carry a human label; integer and double ones do not, so
        // fall back to the raw value rather than dropping the line entirely.
        for (String getter : new String[]{"getValueTranslation", "getValue"}) {
            try {
                Object v = opt.getClass().getMethod(getter).invoke(opt);
                if (v == null) continue;
                String t = String.valueOf(v).trim();
                if (!t.isEmpty()) { s = t; break; }
            } catch (Throwable ignored) {
                // A renamed option costs one line, never the block.
            }
        }
        if (s == null) return;
        sb.append("- ").append(label).append(": ").append(s).append('\n');
    }

    // ------------------------------------------------------- doing / world

    /**
     * What she is DOING, and what the sky is doing.
     *
     * The biggest gap in the old snapshot. It reported a woman standing in a
     * living room with Cooking at level 3, and never that she was sat in front
     * of a television - so the page had a result with no activity behind it,
     * and re-described the furniture instead.
     */
    private static void doingAndWeather(Json j, IsoPlayer p) {
        try {
            j.objKey("rightNow");
            String act = null;
            try {
                act = p.getCurrentActionContextStateName();
            } catch (Throwable ignored) { }
            if (act != null && !act.isEmpty() && !"idle".equalsIgnoreCase(act)) {
                // Engine state names are like "playerreading" - readable enough
                // to be useful, and honest about being the engine's word.
                j.put("doing", act);
            }
            try { if (p.isReading()) j.put("reading", true); } catch (Throwable ignored) { }
            try { if (p.isAsleep()) j.put("asleep", true); } catch (Throwable ignored) { }
            j.endObj();
        } catch (Throwable t) {
            note("doing", t);
        }

        try {
            var cm = zombie.iso.weather.ClimateManager.getInstance();
            if (cm == null) return;
            j.objKey("weather");
            float c = cm.getTemperature();
            j.put("temperatureC", c);
            j.put("feels", c < 0 ? "freezing" : c < 8 ? "cold" : c < 16 ? "cool"
                         : c < 24 ? "mild" : c < 30 ? "warm" : "hot");
            float light = cm.getDayLightStrength();
            j.put("light", light < 0.15f ? "dark" : light < 0.4f ? "dim"
                         : light < 0.75f ? "overcast" : "bright");
            if (cm.isRaining()) j.put("raining", true);
            if (cm.isSnowing()) j.put("snowing", true);
            float fog = cm.getFogIntensity();
            if (fog > 0.2f) j.put("fog", fog > 0.6f ? "thick" : "some");
            float wind = cm.getWindspeedKph();
            if (wind > 20) j.put("wind", wind > 45 ? "strong" : "noticeable");
            j.endObj();
        } catch (Throwable t) {
            note("weather", t);
        }
    }

    // -------------------------------------------------------------- threat

    /**
     * How many of them are actually around, counted rather than inferred.
     *
     * `Stats.getNumVisibleZombies()` exists but feeds the moodle and music
     * systems and read zero while a crowd was on screen, so it cannot be
     * trusted as "what she can see". This walks the loaded zombie list and
     * measures, which is the only honest answer.
     *
     * Reported in bands, not exact counts: a survivor at a window sees "a lot",
     * never "seventeen", and an exact number would be a statistic the narrator
     * is forbidden to quote anyway.
     */
    private static void threat(Json j, IsoPlayer p) {
        try {
            var cell = zombie.iso.IsoWorld.instance == null
                    ? null : zombie.iso.IsoWorld.instance.getCell();
            if (cell == null) return;
            var zs = cell.getZombieList();
            if (zs == null) return;

            int near = 0, close = 0, onMe = 0;
            for (int i = 0; i < zs.size(); i++) {
                var z = zs.get(i);
                if (z == null) continue;
                float d = z.DistTo(p);
                if (d > 45) continue;
                near++;
                if (d <= 12) close++;
                if (z.getTarget() == p) onMe++;
            }

            // The ones that are already down. The room census counts bodies
            // indoors, but outdoors and inside a vehicle it never runs - so a
            // page written from a bus had no idea two of them were lying in
            // the grass, and had to guess where the ones he killed had fallen.
            int bodies = 0;
            try {
                int px = (int) p.getX(), py = (int) p.getY(), pz = (int) p.getZ();
                var cell2 = zombie.iso.IsoWorld.instance.getCell();
                for (int dx = -10; dx <= 10 && bodies < 60; dx++) {
                    for (int dy = -10; dy <= 10 && bodies < 60; dy++) {
                        var sq = cell2.getGridSquare(px + dx, py + dy, pz);
                        if (sq == null) continue;
                        var bs = sq.getDeadBodys();
                        if (bs != null) bodies += bs.size();
                    }
                }
            } catch (Throwable t) {
                note("bodies", t);
            }

            if (near == 0 && bodies == 0) return;   // silence is its own answer
            if (near == 0) {
                j.objKey("theDead");
                j.put("onTheGroundNearby", band(bodies));
                j.put("note", "None of them standing. What is here is already "
                        + "down - you do not know which of these they put down "
                        + "themselves unless the change block says so, and you "
                        + "do not know exactly where any of them fell.");
                j.endObj();
                return;
            }

            j.objKey("theDead");
            j.put("withinSight", band(near));
            if (close > 0) j.put("closeEnoughToHear", band(close));
            if (onMe > 0)  j.put("comingForHer", band(onMe));
            if (bodies > 0) j.put("onTheGroundNearby", band(bodies));
            j.put("note", onMe > 0
                    ? "They have been noticed. This outranks everything else in the state."
                    : "In view but not yet aware. This is the most important thing on the page.");
            j.endObj();
        } catch (Throwable t) {
            note("threat", t);
        }
    }

    /** How big a room FEELS. Never a number - see the caller. */
    private static String roomWord(int squares) {
        if (squares <= 9)  return "cramped";
        if (squares <= 20) return "small";
        if (squares <= 40) return "an ordinary size";
        if (squares <= 80) return "big";
        return "cavernous";
    }

    /** Bands rather than counts - a person at a window does not tally. */
    private static String band(int n) {
        if (n <= 1) return "one";
        if (n <= 3) return "a few";
        if (n <= 8) return "several";
        if (n <= 20) return "a lot";
        if (n <= 50) return "a crowd";
        return "more than she can count";
    }

    // ----------------------------------------------------------- timepiece

    /**
     * What the survivor can actually tell about the time.
     *
     * The device must not know the hour unless he is carrying something that
     * knows it. Showing a clock he does not own is the interface committing
     * exactly the sin the charter forbids the narrator: asserting a fact the
     * world has not given the player.
     *
     * @return "none", "analog" or "digital".
     */
    public static String timepiece() {
        try {
            IsoPlayer p = IsoPlayer.getInstance();
            if (p == null) return "none";
            ItemContainer inv = p.getInventory();
            if (inv == null) return "none";
            boolean analog = false;
            for (InventoryItem it : inv.getItems()) {
                if (it == null) continue;
                String ft = it.getFullType();
                if (ft == null) ft = "";
                String probe = (ft + " " + displayName(it)).toLowerCase();
                if (!probe.contains("watch")) continue;
                // A digital watch shows the date; an analogue one cannot.
                if (probe.contains("digital")) return "digital";
                analog = true;
            }
            return analog ? "analog" : "none";
        } catch (Throwable t) {
            return "none";
        }
    }

    // ------------------------------------------------------- surroundings

    /** Level 1: he knows where he is standing, and nothing about it. */
    private static void basicPlace(Json j, IsoPlayer p) {
        try {
            IsoGridSquare sq = p.getCurrentSquare();
            if (sq == null) return;
            j.objKey("here");
            j.put("outside", sq.isOutside());
            var room = sq.getRoom();
            if (room != null && room.getName() != null) j.put("room", room.getName());
            j.endObj();
        } catch (Throwable t) {
            note("place", t);
        }
    }

    /**
     * What the room offers AT A GLANCE.
     *
     * Elkin's rule: the narrator sees only what a person standing here could
     * take in, plus what this character has already seen. So:
     *
     *  - Furniture is named by its CONTAINER TYPE ("wardrobe", "fridge",
     *    "counter") because that is what your eye reports. What is INSIDE any
     *    of them is never read. You can see a chest of drawers; you cannot see
     *    the drawers' contents until you open them, and neither can the page.
     *  - Items lying on the floor ARE visible - they are in plain sight.
     *  - Corpses are counted and placed, never NAMED. A name comes off an ID
     *    card, which means searching the body. Until then he is a stranger.
     *  - Windows and doors report their state, because smashed glass and a
     *    barricade are the first things anyone notices about a room.
     */
    private static void surroundings(Json j, IsoPlayer p) {
        try {
            IsoGridSquare sq = p.getCurrentSquare();
            if (sq == null) return;

            j.objKey("here");
            j.put("outside", sq.isOutside());

            var room = sq.getRoom();
            if (room == null) {
                j.endObj();
                return;
            }
            if (room.getName() != null) j.put("room", room.getName());

            var squares = room.getSquares();
            if (squares == null) { j.endObj(); return; }
            j.put("roomFeels", roomWord(squares.size()));

            Map<String, int[]> furniture = new LinkedHashMap<>();
            Map<String, int[]> floor = new LinkedHashMap<>();
            int bodies = 0;
            int winTotal = 0, winSmashed = 0, winBarricaded = 0, winOpen = 0;
            int winCurtained = 0, winDrawn = 0;
            int doorTotal = 0, doorOpen = 0, doorLocked = 0, doorBarricaded = 0;

            // A very large room should not cost a frame. 600 squares is far
            // beyond any interior; past that the census is representative.
            int limit = Math.min(squares.size(), 600);
            for (int i = 0; i < limit; i++) {
                IsoGridSquare s = squares.get(i);
                if (s == null) continue;
                try {
                    var bs = s.getDeadBodys();
                    if (bs != null) bodies += bs.size();

                    var wo = s.getWorldObjects();
                    if (wo != null) {
                        for (var o : wo) {
                            if (o == null || o.getItem() == null) continue;
                            bump(floor, displayName(o.getItem()));
                        }
                    }

                    var objs = s.getObjects();
                    if (objs == null) continue;
                    for (int k = 0; k < objs.size(); k++) {
                        var o = objs.get(k);
                        if (o == null) continue;

                        if (o instanceof zombie.iso.objects.IsoWindow win) {
                            winTotal++;
                            if (win.isSmashed() || win.isDestroyed()) winSmashed++;
                            if (win.isBarricaded()) winBarricaded++;
                            if (win.IsOpen()) winOpen++;
                            // Curtains. Drawing them is a deliberate, meaningful
                            // act in this world - it is how you stop being seen
                            // from the street and how you hide a light at night -
                            // and the state could not see it at all.
                            try {
                                var cur = win.HasCurtains();
                                if (cur != null) {
                                    winCurtained++;
                                    if (!cur.isCurtainOpen()) winDrawn++;
                                }
                            } catch (Throwable ignored) { }
                            continue;
                        }
                        if (o instanceof zombie.iso.objects.IsoDoor dr) {
                            doorTotal++;
                            if (dr.IsOpen()) doorOpen++;
                            if (dr.isLocked()) doorLocked++;
                            if (dr.isBarricaded()) doorBarricaded++;
                            continue;
                        }
                        // Container TYPE only - never its contents.
                        var c = o.getContainer();
                        if (c != null && c.getType() != null && !c.getType().isEmpty()) {
                            bump(furniture, c.getType().toLowerCase());
                        }
                    }
                } catch (Throwable ignored) {
                    // One bad square must not lose the room.
                }
            }

            emit(j, "furniture", furniture);
            emit(j, "onTheFloor", floor);
            if (bodies > 0) {
                // Unsearched: he can see there is a body, not who it was.
                j.put("bodiesInSight", bodies);
            }
            if (winTotal > 0) {
                j.objKey("windows");
                j.put("total", winTotal);
                if (winSmashed > 0) j.put("smashed", winSmashed);
                if (winBarricaded > 0) j.put("barricaded", winBarricaded);
                if (winOpen > 0) j.put("open", winOpen);
                if (winCurtained > 0) {
                    j.put("withCurtains", winCurtained);
                    j.put("curtainsDrawn", winDrawn);
                    if (winDrawn >= winCurtained) {
                        j.put("note", "Every curtain in here is drawn. Nothing "
                                + "outside can see in, and no light gets out.");
                    } else if (winDrawn > 0) {
                        j.put("note", "Some of the curtains are drawn, not all.");
                    }
                }
                j.endObj();
            }
            if (doorTotal > 0) {
                j.objKey("doors");
                j.put("total", doorTotal);
                if (doorOpen > 0) j.put("open", doorOpen);
                if (doorLocked > 0) j.put("locked", doorLocked);
                if (doorBarricaded > 0) j.put("barricaded", doorBarricaded);
                j.endObj();
            }
            j.endObj();
        } catch (Throwable t) {
            note("surroundings", t);
        }
    }

    // --------------------------------------------------------------- utils

    private static void note(String section, Throwable t) {
        lastErrors.add(section + ": " + t.getClass().getSimpleName()
                + (t.getMessage() != null ? " " + t.getMessage() : ""));
    }

    private static void errors(Json j) {
        if (lastErrors.isEmpty()) return;
        j.arrKey("readErrors");
        for (String e : lastErrors) j.val(e);
        j.endArr();
    }

}
