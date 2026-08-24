package de.fricke.pzstory;

import java.util.Map;

/** Setups close only through explicit keyed payoff or abandonment records. */
public final class ThreadMemoryTest {
    public static void run() {
        T.group("Story threads - deliberate setup and payoff");
        ThreadMemory memory = new ThreadMemory();
        T.ok("well-formed setup opens",
                memory.apply("[thread] setup red-radio: the red radio repeats a name",
                        "narrator", 2));
        T.eq("one thread is open", 1, memory.openCount());
        T.ok("duplicate key is refused",
                !memory.apply("[thread] setup red-radio: a second version",
                        "narrator", 3));
        T.ok("unknown payoff is refused",
                !memory.apply("[thread] payoff missing-key: nothing",
                        "narrator", 3));
        T.ok("matching payoff closes exact key",
                memory.apply("[thread] payoff red-radio: the caller was identified",
                        "narrator", 6));
        T.eq("payoff leaves no setup abandoned", 0, memory.openCount());
        String json = memory.json();
        T.ok("resolution and page are durable",
                json.contains("\"status\":\"paid\"")
                        && json.contains("\"closedPage\":6"));

        T.group("Story threads - strict and recoverable representation");
        T.ok("malformed command is recognized but refused",
                ThreadMemory.looksLikeCommand("[thread] vague thought")
                        && !memory.apply("[thread] vague thought", "narrator", 7));
        T.ok("control characters are normalized",
                memory.apply("[thread] setup clean-key: line\tbreak", "player", 7));
        Map<String, Object> root = JsonParse.parseObject(memory.json());
        ThreadMemory loaded = new ThreadMemory();
        loaded.load(root.get("threadMemory"));
        T.eq("thread memory round trips", memory.json(), loaded.json());
        T.ok("prompt lists only open exact keys",
                loaded.prompt(12).contains("clean-key: line break")
                        && !loaded.prompt(12).contains("red-radio: the red radio"));
        T.ok("old open setup becomes explicitly overdue",
                loaded.prompt(12).contains("OVERDUE"));
    }
}
