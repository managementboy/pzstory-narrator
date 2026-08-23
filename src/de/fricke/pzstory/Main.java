package de.fricke.pzstory;

/**
 * PZStory - Java entry point.
 *
 * ZombieBuddy runs main(String[]) automatically when the JAR is loaded,
 * at regular Java-mod load time (after the agent has attached).
 */
public class Main {

    public static final String VERSION = "1.23.0-notnow";

    public static void main(String[] args) {
        System.out.println("[PZStory] ============================================");
        System.out.println("[PZStory] Java mod loaded, version " + VERSION);
        System.out.println("[PZStory] jvm=" + System.getProperty("java.version")
                + " classloader=" + Main.class.getClassLoader());
        System.out.println("[PZStory] ============================================");

        // Belt and braces: ZombieBuddy's package scan should pick up the
        // @Exposer.LuaClass annotation on its own, but an explicit call is
        // harmless (exposeClass() is idempotent) and tells us in the log
        // whether the API class is reachable at this point in the lifecycle.
        try {
            me.zed_0xff.zombie_buddy.Exposer.exposeClass(StoryAPI.class);
            System.out.println("[PZStory] explicit exposeClass(StoryAPI) ok");
        } catch (Throwable t) {
            System.out.println("[PZStory] explicit exposeClass failed: " + t);
        }
    }
}
