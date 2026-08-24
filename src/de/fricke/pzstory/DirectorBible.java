package de.fricke.pzstory;

import java.util.ArrayList;
import java.util.List;

/** Private, frozen campaign structure for the opt-in Director mode. */
final class DirectorBible {
    record Snapshot(boolean frozen, String truth, String resolution,
                    String objective, String objectiveState, String transitionReason,
                    String evidenceType, String evidenceKeyword, int evidenceRequired,
                    List<Long> evidenceEventIds,
                    List<String> objectiveHistory,
                    List<String> hiddenRevelations, List<String> revealedFacts) {}

    private boolean frozen;
    private String truth = "";
    private String resolution = "";
    private String objective = "";
    private String objectiveState = "";
    private String transitionReason = "";
    private String evidenceType = "";
    private String evidenceKeyword = "";
    private int evidenceRequired;
    private final List<Long> evidenceEventIds = new ArrayList<>();
    private final List<String> objectiveHistory = new ArrayList<>();
    private final List<String> hiddenRevelations = new ArrayList<>();
    private final List<String> revealedFacts = new ArrayList<>();

    void clear() { restore(new Snapshot(false, "", "", "", "", "", "", "", 0,
            List.of(), List.of(), List.of(), List.of())); }

    boolean frozen() { return frozen; }

    void freeze(Scenario scenario, String premise) {
        if (frozen) return;
        String story = scenario == null ? "survival" : scenario.name.toLowerCase();
        String why = clean(premise, 500);
        truth = "The campaign has a fixed underlying " + story
                + " conflict whose answer will not be rewritten to fit later events.";
        resolution = "Resolve the original conflict through evidence and player action; "
                + "if its direct route becomes impossible, preserve the truth and fail forward.";
        configureObjective(scenario == null ? "" : scenario.id);
        objectiveState = "active";
        transitionReason = "Campaign objective established with the first page.";
        evidenceEventIds.clear();
        objectiveHistory.clear();
        hiddenRevelations.clear();
        hiddenRevelations.add("The first apparent explanation is incomplete.");
        hiddenRevelations.add("A later discovery must connect back to the campaign's beginning.");
        if (!why.isEmpty()) hiddenRevelations.add("The final choice must test this motive: " + why);
        revealedFacts.clear();
        frozen = true;
    }

    private void configureObjective(String scenario) {
        switch (scenario) {
            case "road" -> {
                objective = "Get into a working vehicle and begin the journey.";
                evidenceType = StoryEvent.VEHICLE_ENTERED; evidenceKeyword = "";
                evidenceRequired = 1;
            }
            case "survival" -> {
                objective = "Secure a defensible shelter.";
                evidenceType = StoryEvent.DOOR_SECURED; evidenceKeyword = "";
                evidenceRequired = 1;
            }
            case "conspiracy" -> {
                objective = "Find a written clue that may explain what happened.";
                evidenceType = StoryEvent.ITEM_ACQUIRED; evidenceKeyword = "newspaper";
                evidenceRequired = 1;
            }
            case "character" -> {
                objective = "Sleep through a night and wake in the same refuge.";
                evidenceType = StoryEvent.WOKE_UP; evidenceKeyword = "";
                evidenceRequired = 1;
            }
            default -> {
                objective = "Establish a safe position and survive the immediate danger.";
                evidenceType = StoryEvent.PURSUIT_ENDED; evidenceKeyword = "";
                evidenceRequired = 1;
            }
        }
    }

    boolean observe(long eventId, String type, String summary) {
        if (!frozen || !"active".equals(objectiveState) || eventId <= 0
                || !evidenceType.equals(type) || evidenceEventIds.contains(eventId)) return false;
        String text = summary == null ? "" : summary.toLowerCase(java.util.Locale.ROOT);
        if (!evidenceKeyword.isEmpty() && !text.contains(evidenceKeyword)) return false;
        evidenceEventIds.add(eventId);
        if (evidenceEventIds.size() >= evidenceRequired) {
            revealNext();
            transition("succeeded", "Observed " + evidenceType
                    + " evidence in the game event journal.");
        }
        return true;
    }

    private void revealNext() {
        if (hiddenRevelations.isEmpty()) return;
        revealedFacts.add(hiddenRevelations.remove(0));
        while (revealedFacts.size() > 12) revealedFacts.remove(0);
    }

    boolean transition(String nextState, String reason) {
        if (!frozen || !"active".equals(objectiveState)) return false;
        if (!"succeeded".equals(nextState) && !"failed".equals(nextState)
                && !"impossible".equals(nextState)) return false;
        String why = clean(reason, 500);
        if (why.isEmpty()) return false;
        objectiveState = nextState;
        transitionReason = why;
        return true;
    }

    boolean failForward(String reason) {
        String why = clean(reason, 500);
        if (why.isEmpty() || !transition("impossible", why)) return false;
        objectiveHistory.add("impossible: " + objective + " — " + why);
        while (objectiveHistory.size() > 8) objectiveHistory.remove(0);
        String oldEvidence = evidenceType;
        if (StoryEvent.VEHICLE_ENTERED.equals(oldEvidence)) {
            objective = "Reach a different named place by another route.";
            evidenceType = StoryEvent.PLACE_CHANGED; evidenceKeyword = "";
        } else if (StoryEvent.DOOR_SECURED.equals(oldEvidence)) {
            objective = "Find another shelter and enter it.";
            evidenceType = StoryEvent.PLACE_CHANGED; evidenceKeyword = "";
        } else if (StoryEvent.ITEM_ACQUIRED.equals(oldEvidence)) {
            objective = "Find a working radio as another source of evidence.";
            evidenceType = StoryEvent.ITEM_ACQUIRED; evidenceKeyword = "radio";
        } else {
            objective = "Survive the setback and reach a different safe place.";
            evidenceType = StoryEvent.PLACE_CHANGED; evidenceKeyword = "";
        }
        evidenceRequired = 1;
        evidenceEventIds.clear();
        objectiveState = "active";
        transitionReason = "Fail-forward replacement: " + why;
        return true;
    }

    String publicPrompt() {
        if (!frozen) return "";
        StringBuilder out = new StringBuilder(800);
        out.append("### CAMPAIGN DIRECTOR\n")
                .append("One major objective is ").append(objectiveState)
                .append(". Do not invent a second major objective.\n")
                .append("- ").append(objective).append("\n");
        if (!objectiveHistory.isEmpty()) {
            out.append("Previous objective outcomes:\n");
            for (String row : objectiveHistory) out.append("- ").append(row).append('\n');
        }
        if (!revealedFacts.isEmpty()) {
            out.append("Revealed planned facts (these are now canon):\n");
            for (String fact : revealedFacts) out.append("- ").append(fact).append('\n');
        }
        return out.append('\n').toString();
    }

    String statusJson() {
        return statusJson("");
    }

    String statusJson(String mode) {
        Json j = new Json().obj();
        if (mode != null && !mode.isEmpty()) j.put("mode", mode);
        j.put("frozen", frozen)
                .put("objective", objective).put("objectiveState", objectiveState)
                .put("transitionReason", transitionReason)
                .put("evidenceType", evidenceType)
                .put("evidenceCount", evidenceEventIds.size())
                .put("evidenceRequired", evidenceRequired)
                .put("previousObjectives", objectiveHistory.size())
                .put("revealed", revealedFacts.size());
        return j.endObj().toString();
    }

    Snapshot snapshot() {
        return new Snapshot(frozen, truth, resolution, objective, objectiveState,
                transitionReason, evidenceType, evidenceKeyword, evidenceRequired,
                List.copyOf(evidenceEventIds), List.copyOf(objectiveHistory),
                List.copyOf(hiddenRevelations), List.copyOf(revealedFacts));
    }

    void restore(Snapshot s) {
        frozen = s.frozen(); truth = s.truth(); resolution = s.resolution();
        objective = s.objective(); objectiveState = s.objectiveState();
        transitionReason = s.transitionReason();
        evidenceType = s.evidenceType(); evidenceKeyword = s.evidenceKeyword();
        evidenceRequired = s.evidenceRequired();
        evidenceEventIds.clear(); evidenceEventIds.addAll(s.evidenceEventIds());
        objectiveHistory.clear(); objectiveHistory.addAll(s.objectiveHistory());
        hiddenRevelations.clear(); hiddenRevelations.addAll(s.hiddenRevelations());
        revealedFacts.clear(); revealedFacts.addAll(s.revealedFacts());
    }

    void write(Json j) {
        j.objKey("directorBible").put("frozen", frozen).put("truth", truth)
                .put("resolution", resolution).put("objective", objective)
                .put("objectiveState", objectiveState)
                .put("transitionReason", transitionReason)
                .put("evidenceType", evidenceType).put("evidenceKeyword", evidenceKeyword)
                .put("evidenceRequired", evidenceRequired).arrKey("evidenceEventIds");
        for (Long id : evidenceEventIds) j.val(String.valueOf(id));
        j.endArr().arrKey("objectiveHistory");
        for (String row : objectiveHistory) j.val(row);
        j.endArr().arrKey("hiddenRevelations");
        for (String row : hiddenRevelations) j.val(row);
        j.endArr().arrKey("revealedFacts");
        for (String row : revealedFacts) j.val(row);
        j.endArr().endObj();
    }

    void load(Object value) {
        load(value, null);
    }

    void load(Object value, Scenario scenario) {
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
        String nextReason = field(value, "transitionReason", 500);
        String nextEvidenceType = field(value, "evidenceType", 48);
        String nextEvidenceKeyword = field(value, "evidenceKeyword", 80);
        int nextEvidenceRequired = JsonParse.num(value, "evidenceRequired", 0);
        List<Long> nextEvidenceIds = ids(map.get("evidenceEventIds"));
        List<String> nextObjectiveHistory = strings(map.get("objectiveHistory"), 8, 1000);
        List<String> hidden = strings(map.get("hiddenRevelations"), 12, 1000);
        List<String> revealed = strings(map.get("revealedFacts"), 12, 1000);
        // Alpha 7 froze a schema-6 private bible before evidence rules existed.
        // Preserve its private truth and planned reveals, but replace its old
        // lens-style first task with the deterministic objective introduced in
        // alpha 8. This is a one-way migration on the next successful save.
        if (nextFrozen && nextEvidenceType.isBlank() && scenario != null) {
            frozen = true; truth = nextTruth; resolution = nextResolution;
            configureObjective(scenario.id);
            objectiveState = "active";
            transitionReason = "Migrated to the evidence-based Director objective.";
            hiddenRevelations.addAll(hidden);
            revealedFacts.addAll(revealed);
            return;
        }
        if (nextFrozen && (nextTruth.isBlank() || nextResolution.isBlank()
                || nextObjective.isBlank() || !validState(nextState)
                || nextEvidenceType.isBlank() || nextEvidenceRequired < 1
                || nextEvidenceRequired > 20))
            throw new IllegalStateException("frozen directorBible is incomplete");
        restore(new Snapshot(nextFrozen, nextTruth, nextResolution, nextObjective,
                nextState, nextReason, nextEvidenceType, nextEvidenceKeyword,
                nextEvidenceRequired, nextEvidenceIds, nextObjectiveHistory, hidden, revealed));
    }

    private static boolean validState(String value) {
        return "active".equals(value) || "succeeded".equals(value)
                || "failed".equals(value) || "impossible".equals(value);
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

    private static List<Long> ids(Object value) {
        List<Long> out = new ArrayList<>();
        if (value == null) return out;
        if (!(value instanceof List<?> rows) || rows.size() > 20)
            throw new IllegalStateException("director evidence ids are invalid");
        for (Object row : rows) {
            if (!(row instanceof String text))
                throw new IllegalStateException("director evidence id is not a string");
            try {
                long id = Long.parseLong(text);
                if (id <= 0 || out.contains(id)) throw new NumberFormatException();
                out.add(id);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("director evidence id is invalid");
            }
        }
        return out;
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String text = value.strip().replaceAll("\\s+", " ");
        return text.length() <= max ? text : text.substring(0, max).strip();
    }
}
