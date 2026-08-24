package de.fricke.pzstory;

/**
 * The single source of truth for what this build is.
 *
 * TWO NUMBERS, BECAUSE THEY ANSWER DIFFERENT QUESTIONS.
 *
 * RELEASE is for people: it changes when anything ships, including a comment
 * or a prompt tweak, and it is what appears in mod.info and in the log.
 *
 * API is for the Lua/Java bridge: it changes ONLY when the surface Lua calls
 * changes - a method added, removed, renamed, or given different semantics.
 * Lua refuses to run against a JAR whose API version it does not recognise,
 * because the alternative is a NullPointerException once per frame with the
 * real cause buried in console.txt.
 *
 * WHY THEY ARE SEPARATE. They used to be one string, so every cosmetic release
 * forced a firmware mismatch and a full restart of Project Zomboid - Lua
 * reloads when a save loads, Java does not. Worse, at c097a5f the repository
 * shipped Main.VERSION="1.23.0-notnow" while the Lua wanted "1.23.1" and the
 * committed JAR reported "1.23.1-public": a clean build from the committed
 * source produced a JAR the committed Lua rejected, which quietly broke the
 * README's promise that you can rebuild the binary yourself.
 *
 * build.sh now verifies these against mod.info and the Lua, and fails the
 * build rather than shipping that disagreement again.
 */
public final class Version {

    private Version() {}

    /**
     * Human-facing release. Bump for any shipped change.
     * MUST equal modversion in mod/42/mod.info.
     */
    public static final String RELEASE = "1.24.0";

    /**
     * Lua/Java bridge compatibility. Bump ONLY on a breaking bridge change.
     * MUST equal NEEDS_API in PZStoryBook.lua, compared for EXACT equality.
     *
     * History:
     *   1  everything up to and including release 1.23.1
     *   2  release 1.24.0 - added apiVersion(), archiveTitle(), archiveText(),
     *      archiveStamp(), streamError(), streamFailKind(), streamRetryAfter()
     */
    public static final String API = "3";
}
