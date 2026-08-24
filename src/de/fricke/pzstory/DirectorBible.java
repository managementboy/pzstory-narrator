package de.fricke.pzstory;

import java.util.ArrayList;
import java.util.List;

/** Private, frozen campaign structure for the opt-in Director mode. */
final class DirectorBible {
    record Snapshot(boolean frozen, String truth, String resolution,
                    String objective, String objectiveState,
                    List<String> hiddenRevelations, List<String> revealedFacts) {}

    private boolean frozen;
    private String truth = "";
    private String resolution = "";
    private String objective = "";
    private String objectiveState = "";
    private final List<String> hiddenRevelations = new ArrayList<>();
    private final List<String> revealedFacts = new ArrayList<>();

    void clear() { restore(new Snapshot(false, "", "", "", "", List.of(), List.of())); }

    boolean frozen() { return frozen; }

    void freeze(Scenario scenario, String premise) {
        if (frozen) return;
        String story = scenario == null ? "survival" : scenario.name.toLowerCase();
        String why = clean(premise, 500);
        truth = "The campaign has a fixed underlying " + story
                + " conflict whose answer will not be rewritten to fit later events.";
        resolution = "Resolve the original conflict through evidence and player action; "
                + "if its direct route becomes impossible, preserve the truth and fail forward.";
        objective = scenario != null && scenario.opening.length > 0
                ? clean(scenario.opening[0], 240)
                : "Establish a safe position and find evidence of what happened.";
        objectiveState = "active";
        hiddenRevelations.clear();
        hiddenRevelations.add("The first apparent explanation is incomplete.");
        hiddenRevelations.add("A later discovery must connect back to the campaign's beginning.");
        if (!why.isEmpty()) hiddenRevelations.add("The final choice must test this motive: " + why);
        revealedFacts.clear();
        frozen = true;
    }

    String publicPrompt() {
        if (!frozen) return "";
        StringBuilder out = new StringBuilder(800);
        out.append("### CAMPAIGN DIRECTOR\n")
                .append("One major objective is active. Do not invent a second major objective.\n")
                .append("- ").append(objective).append("\n");
        if (!revealedFacts.isEmpty()) {
            out.append("Revealed planned facts (these are now canon):\n");
            for (String fact : revealedFacts) out.append("- ").append(fact).append('\n');
        }
        return out.append('\n').toString();
    }

    String statusJson() {
        Json j = new Json().obj().put("frozen", frozen)
                .put("objective", objective).put("objectiveState", objectiveState)
                .put("revealed", revealedFacts.size());
        return j.endObj().toString();
    }

    Snapshot snapshot() {
        return new Snapshot(frozen, truth, resolution, objective, objectiveState,
                List.copyOf(hiddenRevelations), List.copyOf(revealedFacts));
    }

    void restore(Snapshot s) {
        frozen = s.frozen(); truth = s.truth(); resolution = s.resolution();
        objective = s.objective(); objectiveState = s.objectiveState();
        hiddenRevelations.clear(); hiddenRevelations.addAll(s.hiddenRevelations());
        revealedFacts.clear(); revealedFacts.addAll(s.revealedFacts());
    }

    void write(Json j) {
        j.objKey("directorBible").put("frozen", frozen).put("truth", truth)
                .put("resolution", resolution).put("objective", objective)
                .put("objectiveState", objectiveState).arrKey("hiddenRevelations");
        for (String row : hiddenRevelations) j.val(row);
        j.endArr().arrKey("revealedFacts");
        for (String row : revealedFacts) j.val(row);
        j.endArr().endObj();
    }

    void load(Object value) {
        clear();
        if (value == null) return;
        if (!(value instanceof java.util.Map<?, ?>))
            throw new IllegalStateException("directorBible is not an object");
        java.util.Map<?, ?> map = (java.util.Map<?, ?>) value;
        boolean nextFrozen = map.get("frozen") instanceof Boolean b && b;
        String nextTruth = field(value, "truth", 1000);
        String nextResolution = field(value, "resolution", 1000);
        String nextObjective = field(value, "objective", 500);
        String nextState = field(value, "objectiveState", 32);
        List<String> hidden = strings(map.get("hiddenRevelations"), 12, 1000);
        List<String> revealed = strings(map.get("revealedFacts"), 12, 1000);
        if (nextFrozen && (nextTruth.isBlank() || nextResolution.isBlank()
                || nextObjective.isBlank() || !"active".equals(nextState)))
            throw new IllegalStateException("frozen directorBible is incomplete");
        restore(new Snapshot(nextFrozen, nextTruth, nextResolution, nextObjective,
                nextState, hidden, revealed));
    }

    private static String field(Object value, String key, int max) {
        String text = JsonParse.str(value, key, "");
        if (text.length() > max) throw new IllegalStateException(key + " is too long");
        return text;
    }

    private static List<String> strings(Object value, int maxRows, int maxChars) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        if (!(value instanceof List<?> rows) || rows.size() > maxRows)
            throw new IllegalStateException("directorBible list is invalid");
        for (Object row : rows) {
            if (!(row instanceof String text) || text.length() > maxChars)
                throw new IllegalStateException("directorBible entry is invalid");
            out.add(text);
        }
        return out;
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String text = value.strip().replaceAll("\\s+", " ");
        return text.length() <= max ? text : text.substring(0, max).strip();
    }
}
