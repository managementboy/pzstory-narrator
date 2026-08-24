# Local narrator evaluation

This directory is a reproducible, synthetic benchmark for local PZStory
narrators. It does not read a save, profile, API key or installed mod.

`scenes.json` contains development and held-out scenes with explicit grounding
rules. `LocalModelBenchmark.java` uses the production `Prompt` builder and
`PageResult` validator, sends requests only to Ollama loopback, and appends one
JSON record after every generation. Interrupted runs therefore remain useful.

Each result records the model digest, prompt variant, seed, timing, complete
reply, structural parse result and deterministic grounding violations. The
scorer is deliberately conservative and its output must be reviewed beside the
raw reply before a model is accepted.

## Compile

Compile the harness against the JAR from the exact checkout being evaluated:

```powershell
$jdk = 'C:\path\to\jdk-25\bin'
New-Item -ItemType Directory -Force build\benchmark | Out-Null
& "$jdk\javac.exe" -encoding UTF-8 `
  -cp mod\42\media\java\PZStory.jar `
  -d build\benchmark dev\LocalModelBenchmark.java
```

## Run

```powershell
& "$jdk\java.exe" `
  -cp 'build\benchmark;mod\42\media\java\PZStory.jar' `
  de.fricke.pzstory.LocalModelBenchmark `
  --scenes dev\local-model-eval\scenes.json `
  --out dev\local-model-eval\runs\example `
  --variants baseline,ledger-cold,compact-cold,compact-repair `
  --split dev `
  --repetitions 3 `
  --seed 240824 `
  --model hermes3:8b
```

Available variants are `baseline`, `ledger`, `ledger-cold`,
`compact-ledger`, `compact-cold` and `compact-repair`. Use `--split holdout`
only after selecting a strategy on the development split. `--scene` accepts a
comma-separated list of exact scene ids for a short diagnostic run.

Never tune against the held-out replies and then continue calling them held
out. Add new fixtures when a real failure is found, and periodically replace
the holdout set.

