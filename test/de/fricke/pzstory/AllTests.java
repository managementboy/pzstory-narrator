package de.fricke.pzstory;

/** Every test that runs without Project Zomboid on the classpath. */
public final class AllTests {
    public static void main(String[] args) {
        System.out.println("PZStory test suite");
        JsonParseTest.run();
        FileSafetyTest.run();
        EndpointTest.run();
        BridgeContractTest.run();
        VersionTest.run();
        System.exit(T.report());
    }
}
