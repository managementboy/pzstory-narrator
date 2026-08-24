package de.fricke.pzstory;

import java.util.ArrayList;
import java.util.List;

/** In-memory gameplay scenarios used by the debug Test Lab. */
final class TestLabDiagnostics {
    private record Check(String name, boolean pass, String detail) {}
    private TestLabDiagnostics() {}

    static String run(String requested) {
        String scenario = requested == null ? "all" : requested.strip().toLowerCase();
        List<Check> checks = new ArrayList<>();
        if (scenario.equals("all") || scenario.equals("place")) place(checks);
        if (scenario.equals("all") || scenario.equals("door")) door(checks);
        if (scenario.equals("all") || scenario.equals("vehicle")) vehicle(checks);
        if (scenario.equals("all") || scenario.equals("noise")) noise(checks);
        if (scenario.equals("all") || scenario.equals("kill")) kill(checks);
        if (scenario.equals("all") || scenario.equals("time")) time(checks);
        if (scenario.equals("all") || scenario.equals("continuity")) continuity(checks);
        if (checks.isEmpty()) checks.add(new Check("known scenario", false, scenario));
        Json out = new Json().obj().put("scenario", scenario).arrKey("checks");
        int passed = 0;
        for (Check check : checks) {
            if (check.pass) passed++;
            out.obj().put("name", check.name).put("pass", check.pass)
                    .put("detail", check.detail).endObj();
        }
        return out.endArr().put("passed", passed).put("total", checks.size())
                .put("saveChanged", false).endObj().toString();
    }

    private static void place(List<Check> out) {
        WorldMemory memory = new WorldMemory();
        String garage = state("garage", "room-a", 1, 0, "", false);
        String kitchen = state("kitchen", "room-b", 1, 0, "", false);
        memory.observe(garage, "day 1");
        memory.observe(kitchen, "day 1");
        memory.observe(garage, "day 1");
        String prompt = memory.prompt();
        out.add(new Check("same-place return", prompt.contains("SAME place")
                && prompt.contains("confirmed as the same place"), "visit count promoted"));
    }

    private static void door(List<Check> out) {
        String closed = state("garage", "room-a", 1, 0, "", false);
        String opened = state("garage", "room-a", 1, 0, "", true);
        out.add(new Check("door opened", Delta.between(closed, opened).contains("standing open"), "closed to open"));
        out.add(new Check("door closed", Delta.between(opened, closed).contains("has been closed"), "open to closed"));
    }

    private static void vehicle(List<Check> out) {
        String outside = state("road", "road-a", 1, 0, "", false);
        String inside = state("road", "road-a", 1, 0, "Chevalier Nyala", false);
        out.add(new Check("vehicle entered", Delta.between(outside, inside).toLowerCase().contains("got into"), "outside to seated"));
        out.add(new Check("vehicle exited", Delta.between(inside, outside).toLowerCase().contains("got out"), "seated to outside"));
    }

    private static void noise(List<Check> out) {
        String quiet = state("road", "road-a", 1, 0, "", false);
        String loud = quiet.substring(0, quiet.length() - 1) + ",\"noise\":{\"what\":\"a noise close by\"}}";
        out.add(new Check("noise started", Delta.between(quiet, loud).contains("noise close by"), "quiet to audible"));
        out.add(new Check("noise stopped", Delta.between(loud, quiet).contains("quiet again"), "audible to quiet"));
    }

    private static void kill(List<Check> out) {
        String delta = Delta.between(state("road", "road-a", 1, 0, "", false),
                state("road", "road-a", 1, 1, "", false));
        out.add(new Check("zombie kill", delta.toLowerCase().contains("killed one more"), "kill count increased"));
    }

    private static void time(List<Check> out) {
        String delta = Delta.between(state("road", "road-a", 1, 0, "", false),
                state("road", "road-a", 3.5, 0, "", false));
        out.add(new Check("controlled elapsed time", delta.contains("few hours"), "2.5 hours banded semantically"));
    }

    private static void continuity(List<Check> out) {
        ContinuityMemory memory = new ContinuityMemory();
        memory.record("weapon", "item-7", "an axe", "day 1");
        memory.record("weapon", "item-7", "an axe", "day 1");
        memory.record("routine", "repair@room-a", "made repairs at the garage", "day 1");
        memory.record("routine", "repair@room-a", "made repairs at the garage", "day 2");
        memory.record("routine", "repair@room-a", "made repairs at the garage", "day 3");
        String prompt = memory.prompt();
        out.add(new Check("weapon familiarity", prompt.contains("becoming familiar"), "two observations"));
        out.add(new Check("repeated routine", prompt.contains("repeatedly"), "three observations"));
    }

    private static String state(String room, String roomId, double hour, int kills,
                                String vehicle, boolean doorOpen) {
        StringBuilder s = new StringBuilder();
        s.append("{\"time\":{\"worldAgeHours\":").append(hour)
                .append("},\"character\":{\"zombieKills\":").append(kills)
                .append("},\"position\":{\"x\":10,\"y\":20,\"z\":0,\"room\":\"")
                .append(room).append("\",\"roomId\":\"").append(roomId)
                .append("\",\"building\":{\"id\":41}},\"here\":{\"doors\":{\"total\":1");
        if (doorOpen) s.append(",\"open\":1");
        s.append("}}");
        if (!vehicle.isEmpty()) s.append(",\"inAVehicle\":{\"model\":\"")
                .append(vehicle).append("\",\"vehicleId\":\"vehicle-1\"}");
        return s.append('}').toString();
    }
}
