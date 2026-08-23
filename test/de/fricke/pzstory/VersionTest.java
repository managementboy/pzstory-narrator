package de.fricke.pzstory;

/** Release/API version consistency across Java, mod.info and Lua. */
public final class VersionTest {

    public static void run() {
        T.group("Version - shape and consistency");
        T.ok("RELEASE looks like a version",
                Version.RELEASE.matches("\\d+\\.\\d+\\.\\d+([-.][A-Za-z0-9.]+)?"));
        T.ok("API is a bare integer", Version.API.matches("\\d+"));
        // Read as text: Main pulls in StoryAPI and therefore the game jars,
        // which CI does not have. The point is that it delegates rather than
        // carrying a second copy of the number.
        T.ok("Main.VERSION delegates to Version.RELEASE",
                read("src/de/fricke/pzstory/Main.java")
                        .contains("VERSION = Version.RELEASE"));

        String modinfo = read("mod/42/mod.info");
        String lua     = read("mod/42/media/lua/client/PZStory/PZStoryBook.lua");

        T.ok("mod.info modversion == Version.RELEASE",
                modinfo.contains("modversion=" + Version.RELEASE));
        T.ok("Lua NEEDS_API == Version.API",
                lua.contains("local NEEDS_API = \"" + Version.API + "\""));

        // The bug this whole class exists to prevent: a prefix match let a JAR
        // reporting 1.23.10 satisfy a Lua that required 1.23.1.
        T.ok("Lua no longer prefix-matches", !lua.contains("v:sub(1, #NEEDS"));
        T.ok("Lua compares API exactly", lua.contains("api ~= NEEDS_API"));
    }

    private static String read(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Path.of(path)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
