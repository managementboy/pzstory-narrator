package zombie;

/** Minimal test double for pure campaign-persistence tests. */
public final class ZomboidFileSystem {
    public static final ZomboidFileSystem instance = new ZomboidFileSystem();

    public String getFileNameInCurrentSave(String name) {
        return java.nio.file.Path.of(testRoot(), name)
                .toString();
    }

    public String getCacheDir() {
        return testRoot();
    }

    private static String testRoot() {
        return System.getProperty("pzstory.test.root",
                System.getProperty("java.io.tmpdir"));
    }
}
