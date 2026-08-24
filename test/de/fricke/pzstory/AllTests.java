package de.fricke.pzstory;

/** Every test that runs without Project Zomboid on the classpath. */
public final class AllTests {
    public static void main(String[] args) {
        System.out.println("PZStory test suite");
        JsonParseTest.run();
        PageResultTest.run();
        NarrativeStateTest.run();
        PromptFreshStartTest.run();
        DeltaTest.run();
        ActionEventPolicyTest.run();
        FactMemoryTest.run();
        RepetitionGuardTest.run();
        EventJournalTest.run();
        WorldMemoryTest.run();
        CampaignEventTest.run();
        CampaignTest.run();
        FileSafetyTest.run();
        EndpointTest.run();
        ProviderCompatibilityTest.run();
        BridgeContractTest.run();
        VersionTest.run();
        System.exit(T.report());
    }
}
