package de.fricke.pzstory;

/**
 * PZStory - Java entry point.
 *
 * ZombieBuddy runs main(String[]) automatically when the JAR is loaded,
 * at regular Java-mod load time (after the agent has attached).
 */
public class Main {

    /**
     * Kept as an alias so nothing that already reads Main.VERSION breaks.
     * The value lives in {@link Version} - one place, verified by build.sh.
     */
    public static final String VERSION = Version.RELEASE;

    public static void main(String[] args) {
        System.out.println("[PZStory] ============================================");
        System.out.println("[PZStory] Java mod loaded, release " + Version.RELEASE
                + " (bridge API " + Version.API + ")");
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
