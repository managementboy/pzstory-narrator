package de.fricke.pzstory;

/** Completion-hook action events remain allow-listed, bounded and factual. */
public final class ActionEventPolicyTest {
    public static void run() {
        T.group("Action events - safe completion policy");
        check("item_used", StoryEvent.ITEM_USED, "They used bandage.", 28);
        check("crafted", StoryEvent.CRAFTED, "They crafted spear.", 58);
        check("repaired", StoryEvent.REPAIRED, "They repaired jacket.", 54);
        check("farmed", StoryEvent.FARMED, "They planted seeds.", 42);
        check("fire_started", StoryEvent.FIRE_STARTED, "They lit campfire.", 72);
        check("door_opened", StoryEvent.DOOR_OPENED, "They opened a door.", 14);
        check("door_closed", StoryEvent.DOOR_CLOSED, "They closed a door.", 14);
        T.ok("unknown action is refused",
                ActionEventPolicy.resolve("invented", "secret") == null);
        T.eq("control characters are removed", "line break",
                ActionEventPolicy.safeLabel("line\nbreak"));
        T.eq("blank labels get a neutral fallback", "something",
                ActionEventPolicy.safeLabel("\n\t"));
        T.eq("action labels are bounded", 80,
                ActionEventPolicy.safeLabel("x".repeat(200)).length());
    }

    private static void check(String action, String type, String summary,
                              int importance) {
        ActionEventPolicy.Result event = ActionEventPolicy.resolve(action,
                action.equals("farmed") ? "planted seeds" : switch (action) {
                    case "item_used" -> "bandage";
                    case "crafted" -> "spear";
                    case "repaired" -> "jacket";
                    case "fire_started" -> "campfire";
                    default -> "ignored";
                });
        T.eq(action + " type", type, event.type());
        T.eq(action + " summary", summary, event.summary());
        T.eq(action + " importance", importance, event.importance());
    }
}
