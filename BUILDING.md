# Building PZStory core

## Requirements

- Project Zomboid Build 42 with `projectzomboid.jar` in the game directory.
- ZombieBuddy with `ZombieBuddy.jar` in the same directory.
- JDK 25 or newer. Build 42 game classes use Java class-file version 69.
- On Windows, Git Bash. Do not use WSL with a Windows JDK path.

No Gradle, Maven or network download is used by the core build.

## Build

Linux/macOS, or a machine where `javac` 25+ is on `PATH`:

```bash
PZ=/path/to/ProjectZomboid ./build.sh
```

Windows Git Bash with explicit paths:

```bash
JDK='/c/path/to/jdk-25/bin' \
PZ='/c/Program Files (x86)/Steam/steamapps/common/ProjectZomboid' \
./build.sh
```

The output is `mod/42/media/java/PZStory.jar`. A successful build finishes by
running `tools/verify.sh`, which checks version/API consistency, JAR integrity,
source-to-class completeness, and tracked-file privacy boundaries.

## Tests and reproducibility

```bash
JDK='/c/path/to/jdk-25/bin' ./tools/test.sh
JDK='/c/path/to/jdk-25/bin' \
PZ='/c/Program Files (x86)/Steam/steamapps/common/ProjectZomboid' \
./tools/rebuild-and-compare.sh
```

At release `2.1.0-alpha.1`, the unit suite reports `642 passed, 0 failed` and
the committed JAR SHA-256 is:

```text
1E5997553247C555299C4A3157614ABA3342A7034BDA4D6759A28E67AC0307AE
```

The hash should change after Java source changes. Before publishing, the built
JAR, source release, `mod.info`, Lua bridge API and tests must agree.

## Managed-agent troubleshooting

If Git Bash exits with `couldn't create signal pipe, Win32 error 5`, rerun it
using the coding agent's approved/elevated sandbox override. This is a Windows
automation-sandbox restriction, not a source failure.

If a command tool yields a process/session identifier, poll that session until
it exits. Do not start a second build over the first.

