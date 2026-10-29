# RansomScope

## Download and try

- [Windows desktop package](https://github.com/johnnyychiu/ransomscope/releases/download/v0.1.0/RansomScope-Windows-x64.zip)
- [Browser build](https://github.com/johnnyychiu/ransomscope/releases/download/v0.1.0/RansomScope-Web.zip)
- [Three-minute demo video](https://github.com/johnnyychiu/ransomscope/releases/download/v0.1.0/RansomScope-demo.mp4) (edited real screenshots, not continuous recording)
- [Measured results and limitations](docs/TEST-REPORT.md)

This source and release are published for review. The contest form has not been submitted. Historical local-build status notes in the evidence describe the time those artifacts were prepared.

**Inspect suspicious file activity. Understand the alert. Verify the recovery.**

RansomScope is an experimental ransomware analysis and recovery-readiness workbench built for the Kotlin Multiplatform Contest. The Windows desktop application observes file activity and runs a contained, disposable-file lab. The browser application replays evidence and compares detection rules locally. Both compile the same Kotlin analysis engine and synthetic memory-lab scanner.

It is **not a universal ransomware vaccine, antivirus product, or real-ransomware-validated prevention system**. The demo includes no real malware. Every replay and memory fixture is labelled synthetic. Ordinary folder notifications do not reliably identify the process that changed a file, so unknown attribution remains unknown.

## What you can try

| Capability | Desktop / JVM | Browser / JavaScript |
| --- | --- | --- |
| Inspect events and explanations | Yes | Yes |
| Run the shared detection baselines | Yes | Yes |
| Observe a selected local folder | Yes, passive observation | No |
| Create a disposable lab and pre-change snapshot | Yes | No |
| Cooperatively pause the owned lab worker | Yes | No |
| Restore lab files to a new directory and verify hashes | Yes | No |
| Import/export event JSON | Platform workflow | Local browser workflow |
| Scan generated AES memory fixtures | Yes | Yes |
| Read arbitrary process or system memory | No | No |

The interface separates the incident timeline, recovery evidence, and experimental memory analysis. It never turns an absent alert into a guarantee that files are safe, or a changed-path list into a claim that backups exist.

## Requirements

- A **JDK 17 or later**, with `JAVA_HOME` pointing to the JDK and its `bin` directory on `PATH`.
- Windows 10/11 for the tested desktop workflow. Source-level compatibility with another desktop OS is not a claim of validation on that OS.
- Internet access for the first Gradle build to download the pinned Kotlin, Compose and other dependencies. The Gradle wrapper downloads its configured Gradle version if it is not already cached.
- A modern browser for the JavaScript application. Python 3 is used below only as a simple local static-file server.

The application does not require a GPU, API key, cloud account, or AI service. It contains no analytics, remote upload service, external font loading, or telemetry endpoint. Building dependencies and opening research links are separate network operations.

## Run the desktop app from source

Open a terminal in this project directory.

Windows PowerShell:

```powershell
.\gradlew.bat :desktop:run
```

On systems with an executable Gradle wrapper:

```sh
./gradlew :desktop:run
```

The initial dashboard has no incident evidence. To observe existing files, use the folder selection control: this is passive monitoring, not permission to modify or encrypt those files. Folder events have unknown writer attribution unless an independent source supplies it.

For the end-to-end demonstration:

1. Follow the guide at the top and select **Create safe demo**. The app creates its own disposable workspace and snapshots its generated files before any test changes.
2. Select **Start demo**, then **Review alert** (or **Review changes**). The worker transforms only the app-created lab files. The guide shows the current stage and its next action.
3. The worker can acknowledge a cooperative pause request over its control channel. This is **not** forced suspension of an arbitrary process or evidence that all ransomware can be stopped.
4. Select **Restore & verify**, then **Open restored folder**. The app validates snapshot integrity, restores into a fresh output directory, and checks SHA-256 hashes against the pre-change manifest. Read the actual result shown by your run.

Lab copies are local and writable by the same user. They are **not tamper-proof, immutable, or offline backups**. Keep independent, tested backups for important data. Do not use this prototype as a replacement for established endpoint protection.

## Build and run the browser app

Windows PowerShell:

```powershell
.\gradlew.bat :web:jsBrowserDistribution
python -m http.server 8766 --bind 127.0.0.1 --directory web/build/dist/js/productionExecutable
```

Unix-like shell:

```sh
./gradlew :web:jsBrowserDistribution
python3 -m http.server 8766 --bind 127.0.0.1 --directory web/build/dist/js/productionExecutable
```

Open **http://127.0.0.1:8766/**. The local server is bound to loopback, not your LAN. Stop it with `Ctrl+C` when finished.

Start with **Rapid file changes** and press **Run replay**. Compare **Combined signals**, **Write rate only**, **Entropy only**, and **Canary only** against exactly the same evidence. The expandable settings let you change the time window, distinct-path threshold and entropy-change threshold. These are heuristic settings, not calibrated probabilities.

Try **Normal archive creation**, **Bulk rename**, **Authorized encryption**, **Slow changes**, **Partial content changes**, and **Missing telemetry**. They illustrate false-positive ambiguity, blind spots, and incomplete collection. They are teaching traces, not a representative malware benchmark.

### Import a sample

Run the evaluation generator, then import its sample in the browser:

```powershell
.\gradlew.bat :core:benchmark
```

Choose **Import events** and select `docs/evaluation/scenarios/burst.json`. Processing stays in browser memory; the file is not uploaded. Browser imports are limited to 5 MB and 20,000 events, with schema and numeric bounds checked before analysis. Review or redact filenames before sharing an export with anyone. The four-step browser guide shows whether replay is ready, running, paused, or complete and offers a context-specific next action. Technical settings and evidence details expand on demand.

An event-only import has no independently verified snapshot evidence. The browser Recovery page therefore reports that missing evidence rather than claiming it can restore your files. Browser downloads are local JSON files, not uploads to another service.

## Experimental memory lab

The shared memory lab generates a seeded byte buffer containing, or deliberately lacking, an AES-128 expanded-key schedule. It searches for the structure at an unknown position and validates candidates against the generated encrypted sample. It also provides erased, raw-key-only, and corrupted cases.

- A successful fixture demonstrates the supported scanner and sample-validation path.
- It does **not** demonstrate acquisition of live RAM, support for a particular cryptographic provider, a ransomware family decryptor, or recovery of user files.
- A random raw key has no universal signature. “No supported candidate” is an honest result when the required structure is absent or unsupported.
- The application does not scan LSASS, unrelated processes, or full-system memory, and does not upload memory content.

The memory feature is independent of the desktop snapshot recovery path.

## Tests and evaluation

Run the focused checks with the same Gradle wrapper:

```powershell
.\gradlew.bat :core:jvmTest :core:jsNodeTest :desktop:test
.\gradlew.bat :core:benchmark
.\gradlew.bat :desktop:integrationCheck
.\gradlew.bat :desktop:benignWorkloads
```

The core tests cover rule behaviour, input validation, missing telemetry, ordering, synthetic fixture negatives, and standard AES test vectors. JVM and JavaScript execute the shared tests. The benchmark task writes evaluation artifacts under `docs/evaluation`; inspect the generated results and their stated scope rather than treating these instructions as a claim that a run has passed.

The desktop integration check exercises the actual lab workflow, including pre-change snapshots, worker control, observation and restored-file integrity. Test artifacts are generated from disposable files. A restore result must be read from that particular run; no fixed success count is a guarantee.

The JDK-only benign workload task performs real ZIP creation, bulk renaming, Java compilation and ordinary editing inside application-created temporary folders. It records actual observation duration and each baseline's alerts; four short controls do not establish a production false-alert rate. Repeated packaged checks can be collected with `python scripts/measure_packaged.py <path-to-RansomScope.exe> docs/packaged-evidence/repeated --runs 12`, using `scripts/requirements-measurements.txt`. Its sampled CPU, process-tree RSS and I/O counters include the entire check, not idle monitoring overhead. No user documents are used or removed.

Useful evaluation distinctions:

- Time to alert and time to cooperative pause are separate measurements.
- Observed affected-file sizes are not the number of bytes actually encrypted or lost.
- A false alert during authorized encryption can reflect ambiguous observable behaviour.
- Synthetic replay results cannot establish accuracy against current real ransomware.
- Threshold tuning and held-out workload evaluation must be kept separate.

## Project structure

```text
core/src/commonMain/       Shared event schema, rules, explanations, scenarios and memory lab
core/src/commonTest/       Platform-independent behaviour and cryptographic fixture tests
desktop/src/main/          Compose desktop UI, folder observer, lab worker and snapshot recovery
desktop/src/test/          Desktop backend checks
web/src/jsMain/kotlin/     Kotlin/JS browser interface using the shared core
web/src/jsMain/resources/  Local HTML and CSS, with no CDN dependencies
docs/evaluation/          Generated evaluation results and sample event collections
docs/DEMO-SCRIPT.md        Reproducible recording outline
```

The desktop UI uses Compose. The browser UI uses Kotlin/JS DOM APIs. Shared code is compiled for JVM and JavaScript; the web application does not duplicate the detector in handwritten JavaScript or call a remote analysis service.

## Research and scope

RansomScope builds on established ideas, with attribution rather than a claim of a new universal detection algorithm:

- [ShieldFS, ACSAC 2016](https://trustial.org/publications/continella_shieldfs_2016/): file-system activity analysis and copy-on-write recovery.
- [Redemption, RAID 2017](https://theseclab.org/publications/raid2017redemption.pdf): mediated writes and preservation of original files.
- [PayBreak, ASIACCS 2017](https://discovery.ucl.ac.uk/id/eprint/1541227/): proactive cryptographic key capture, distinct from generic RAM scanning.
- [Evaluation of live forensic techniques in ransomware attack mitigation, 2020](https://doi.org/10.1016/j.fsidi.2020.300979): conditional memory-based key recovery and the importance of timing.
- [ERW-Radar, NDSS 2025](https://www.ndss-symposium.org/wp-content/uploads/2025-349-paper.pdf): contextual behaviour and fine-grained content analysis, including limitations of simple indicators.

Windows directory notifications can lose events and do not provide reliable writer PID attribution. A watcher is not a pre-write kernel minifilter. See Microsoft's [notification structure](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-file_notify_information) and [minifilter callback documentation](https://learn.microsoft.com/en-us/windows-hardware/drivers/ifs/writing-preoperation-and-postoperation-callback-routines).

## Competition materials and distribution

This is new source for the Kotlin Multiplatform Contest. Entry requirements and eligibility remain subject to the [official contest rules](https://kotlinconf.com/contest-rules/). The final entry is submitted by the entrant; creating or running this repository does not submit an application.

Source builds are documented above. Any portable desktop package, static web distribution, recording, or published repository URL must be checked against the actual accompanying release files. This README does not imply that an installer has been signed, a public deployment exists, or a submission has been accepted.

## License

Original RansomScope code is released under the [MIT License](LICENSE), copyright 2026 Chun Yin Chiu. Dependency libraries, the JDK, Gradle, Kotlin, Compose and cited research retain their own licenses and notices. The project license does not relicense third-party dependencies or research material.

## Verified local release (29 October 2026)

The accompanying release contains RansomScope-Windows-x64.zip (portable application with bundled Java runtime), RansomScope-Web.zip (static browser build and samples), RansomScope-Source.zip, and the three-minute RansomScope-demo.mp4 with captions. See START-HERE.txt for launch instructions and docs/TEST-REPORT.md for actual results and limitations. The recording is an edited walkthrough of real application screenshots; it has no audio track and does not demonstrate live memory capture.

The verified automated suite ran 13 common tests on JVM, the same 13 on JavaScript/Node, and seven desktop tests. Five further checks exercised the packaged executable, including an actual disposable-file pause and 24/24 hash-verified restores. These are bounded prototype tests, not proof of protection against current malware.

Development and drafting were assisted by AI tools. The entrant must review the code, materials and licensing before publication/submission. The copy-ready essay is docs/SUBMISSION-ESSAY-300-WORDS.txt; the separate author-review note is not part of its 300-word count. No public GitHub repository has been created and no contest entry has been submitted by this local release process.
