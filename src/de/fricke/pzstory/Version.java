package de.fricke.pzstory;

/**
 * The single source of truth for what this build is.
 *
 * TWO NUMBERS, BECAUSE THEY ANSWER DIFFERENT QUESTIONS.
 *
 * RELEASE is for people: it changes when anything ships, including a comment
 * or a prompt tweak, and it is what appears in mod.info and in the log.
 *
 * API is for the Lua/Java bridge: it changes when the surface Lua calls
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
    public static final String RELEASE = "2.0.0";

    /**
     * Lua/Java bridge compatibility. Bump when production Lua depends on a
     * new method, payload field, status, or changed method semantics.
     * MUST equal NEEDS_API in PZStoryBook.lua, compared for EXACT equality.
     *
     * History:
     *   1  everything up to and including release 1.23.1
     *   2  release 1.24.0 - added apiVersion() and exact compatibility checks
     *   3  release 1.24.0 audit hardening - strict structured bridge payloads
     *      and normalised stream metric field names
     *   4  release 1.25.0-rc1 - transactional completion states, STOP handling,
     *      and providerPreview()
     *   5  release 2.0.0-alpha.1 - local event observer, transactional event
     *      consumption, and structured world-memory diagnostics
     *   6  release 2.0.0-alpha.2 - completed-action event bridge
     *   7  release 2.0.0-alpha.3 - structured fact-memory diagnostics
     *   8  release 2.0.0-alpha.4 - thread and continuity diagnostics
     *      release 2.0.0-alpha.5 - debug-only Lua Test Lab; API unchanged
     *   9  release 2.0.0-alpha.6 - non-destructive Test Lab scenarios
     *   10 release 2.0.0-alpha.7 - opt-in Campaign Director foundation
     *   11 release 2.0.0-alpha.8 - Director objectives, evidence, reveals,
     *      diagnostics and automated scenarios
     *   12 release 2.0.0-alpha.9 - experimental validated narrator and
     *      buffered planner output
     *      release 2.0.0-alpha.10 - transactional LM Studio stateful sessions;
     *      release 2.0.0-alpha.11 - narrator-scoped sessions and one repair turn;
     *      release 2.0.0-alpha.12 - recorded-pronoun Safe prose and paragraphs;
     *   13 release 2.0.0-alpha.13 - atmospheric grounded openings, Safe trace,
     *      and pre-page narrator-history bootstrap status
     *   14 KnoxOS read-only boot telemetry
     *   15 dynamic downloaded-model selection from LM Studio
     *   16 explicit KnoxOS history retry; failed seeds no longer auto-resend;
     *      release 2.0.0 - stable promotion after acceptance testing
     */
    public static final String API = "16";
}
