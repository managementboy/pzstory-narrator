package de.fricke.pzstory;

/** Movement and room identity must never contradict the live snapshot. */
public final class DeltaTest {

    public static void run() {
        T.group("Delta - exact movement semantics");
        String a = snapshot(10, 20, 0, "livingroom", "41", 7);
        T.ok("opening page has no previous standing interval",
                !Delta.stillStanding("", a));
        T.ok("identical position is still standing", Delta.stillStanding(a, a));
        T.ok("one tile is movement",
                !Delta.stillStanding(a, snapshot(11, 20, 0, "livingroom", "41", 7)));
        String movement = Delta.between(a,
                snapshot(20, 20, 0, "livingroom", "41", 7));
        T.ok("provider delta keeps movement semantic", movement.contains("moved a short way"));
        T.ok("provider delta omits exact paces", !movement.contains("paces"));
        T.ok("a floor change is movement",
                !Delta.stillStanding(a, snapshot(10, 20, 1, "livingroom", "41", 7)));

        String duplicateName = snapshot(11, 20, 0, "livingroom", "99", 7);
        String change = Delta.between(a, duplicateName);
        T.ok("same-named rooms with different ids stay distinct",
                change.contains("different room: livingroom"));

        T.group("Delta - stable bag identity");
        String bagsBefore = """
                {"bags":[{"name":"Duffel Bag","id":"1","contents":["Bandage"]}]}
                """;
        String bagsAfter = """
                {"bags":[{"name":"Duffel Bag","id":"1","contents":["Bandage"]},
                           {"name":"Duffel Bag","id":"2","contents":["Wallet","Comb"]}]}
                """;
        String bagChange = Delta.between(bagsBefore, bagsAfter);
        T.ok("one same-named bag is recognised as new",
                bagChange.contains("picked up a Duffel Bag"));
        T.ok("contents are described as already inside",
                bagChange.contains("ALREADY FULL") && bagChange.contains("Wallet"));
        T.ok("contents are not described as separately gathered",
                !bagChange.contains("They now carry: Wallet"));

        String legacyBefore = """
                {"bags":[{"name":"Duffel Bag","contents":["Bandage"]},
                           {"name":"Duffel Bag","contents":["Wallet","Comb"]}]}
                """;
        String firstAfterUpgrade = """
                {"bags":[{"name":"Duffel Bag","id":"1","contents":["Bandage"]},
                           {"name":"Duffel Bag","id":"2","contents":["Wallet","Comb"]}]}
                """;
        String upgradeChange = Delta.between(legacyBefore, firstAfterUpgrade);
        T.ok("legacy snapshots do not rediscover existing bags after upgrade",
                !upgradeChange.contains("picked up a Duffel Bag"));

        T.group("Delta - outbound telemetry is semantic");
        String telemetryBefore = """
                {"time":{"worldAgeHours":1},
                 "character":{"zombieKills":1},
                 "skills":{"Cooking":{"level":1,"xp":10}}}
                """;
        String telemetryAfter = """
                {"time":{"worldAgeHours":2.5},
                 "character":{"zombieKills":8},
                 "skills":{"Cooking":{"level":2,"xp":48}}}
                """;
        String telemetry = Delta.between(telemetryBefore, telemetryAfter);
        T.ok("elapsed time is banded", telemetry.contains("A few hours"));
        T.ok("skill progress omits level and XP", telemetry.contains("noticeably improved")
                && !telemetry.contains("level 2") && !telemetry.contains("points of practice"));
        T.ok("kill progress is banded", telemetry.contains("several more")
                && !telemetry.contains("7 more"));
        T.eq("malformed snapshots are never retained", null, Delta.keep("{broken"));
    }

    private static String snapshot(int x, int y, int z, String room,
                                   String roomId, int buildingId) {
        return "{\"position\":{\"x\":" + x + ",\"y\":" + y
                + ",\"z\":" + z + ",\"room\":\"" + room
                + "\",\"roomId\":\"" + roomId
                + "\",\"building\":{\"id\":" + buildingId + "}},"
                + "\"time\":{\"worldAgeHours\":1}}";
    }
}
