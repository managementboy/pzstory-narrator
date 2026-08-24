package de.fricke.pzstory;

/** Pure, testable policy for events reported by Lua completion hooks. */
final class ActionEventPolicy {
    record Result(String type, String summary, int importance) {}

    private ActionEventPolicy() {}

    static Result resolve(String action, String detail) {
        String label = safeLabel(detail);
        return switch (action == null ? "" : action) {
            case "item_used" -> new Result(StoryEvent.ITEM_USED,
                    "They used " + label + ".", 28);
            case "crafted" -> new Result(StoryEvent.CRAFTED,
                    "They crafted " + label + ".", 58);
            case "repaired" -> new Result(StoryEvent.REPAIRED,
                    "They repaired " + label + ".", 54);
            case "farmed" -> new Result(StoryEvent.FARMED,
                    "They " + label + ".", 42);
            case "fire_started" -> new Result(StoryEvent.FIRE_STARTED,
                    "They lit " + label + ".", 72);
            case "door_opened" -> new Result(StoryEvent.DOOR_OPENED,
                    "They opened a door.", 14);
            case "door_closed" -> new Result(StoryEvent.DOOR_CLOSED,
                    "They closed a door.", 14);
            default -> null;
        };
    }

    static String safeLabel(String value) {
        if (value == null) return "something";
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (clean.isEmpty()) return "something";
        return clean.length() <= 80 ? clean : clean.substring(0, 80).strip();
    }
}
