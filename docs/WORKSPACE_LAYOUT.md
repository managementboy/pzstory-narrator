# Local workspace layout

The development workspace uses two independent Git repositories:

- `pzstory-core` owns the narrator mod, Java bridge, Lua interface, tests, and core documentation.
- `pzstory-voice` owns the optional local voice service.

They are kept as sibling directories so each project has its own history, releases, and remote repository. Neither repository contains or depends on the other repository's Git metadata.

The parent workspace also contains local orchestration material:

- `v2.1/build-all.ps1` is the canonical combined build entry point.
- `v2.1/refresh-deployment.ps1` refreshes the local deployment trees.
- `v2.1/install.ps1` installs the prepared deployment.
- `v2.1/deployment/` contains generated deployment output and its checksum manifest.
- `v2.1/toolchain/` contains the retained local JDK.
- `legacy/` contains historical material and is not a build input.
- Root-level `BUILDING.md` and `README.md` describe the combined local workflow.

These parent-workspace files are deliberately local and are not a third Git repository. Generated deployment payloads and the retained JDK should not be committed to either source repository.

For a standalone checkout of `pzstory-core`, use this repository's `build.sh`, `tools/test.sh`, and `tools/verify.sh`. The combined PowerShell workflow is available only when the repository is placed in the organized local workspace described above.

After changing either active project, run the combined build from the parent workspace when possible so the core mod, optional voice service, deployment copies, and checksums are verified together.
