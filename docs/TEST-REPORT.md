# RansomScope test report

Recorded evidence: 29 October 2026. This report describes the current prototype, not a production ransomware-prevention product. Final visual review and demo verification are recorded in the last section. Original test XML reports are preserved in `test-evidence/`.

## Evidence and automated tests

The following XML reports were inspected directly. All recorded executions passed, with no skipped tests, failures, or errors.

| Suite | Recorded executions | Result | Evidence |
| --- | ---: | --- | --- |
| Shared core on JVM | 17 | Passed | `core/build/test-results/jvmTest/TEST-ransomscope.core.*.xml` |
| The same shared core on Kotlin/JS under Node | 17 | Passed | `core/build/test-results/jsNodeTest/TEST-ransomscope.core.*.xml` |
| Desktop backend and workflow guidance | 12 | Passed | `desktop/build/test-results/test/TEST-ransomscope.desktop.*.xml` |
| **Total** | **46** | **Passed** | 17 shared test functions run on two targets, plus 12 desktop-specific functions: 29 distinct test functions. |

Core coverage includes the FIPS-197 AES-128 known-answer block, randomly placed synthetic expanded-key fixtures and negative fixtures, strict event imports, entropy boundaries, duplicate-notification counts, event ordering, incomplete telemetry, unknown writer identity, and explicitly demonstrated false positives and missed detections.

Desktop coverage includes fresh-directory snapshot restoration, preserved original evidence, rejection of snapshot tampering and forged worker tokens, refusing special paths without traversing them, passive monitoring without fabricated PID/canary attribution, newly arriving directory registration with a disclosed coverage gap, and separate source-versus-packaged worker launch paths.

Four shared recovery tests additionally cover manifest schema validation, local file-readiness states, incomplete metadata, and imported declarations that forge verification flags/source strings. Five desktop guidance tests cover create/start, mandatory review before restore, stopped-worker handling, terminal completion, and switching out of a lab. Imported fields never promote themselves to locally verified evidence.

An initial packaged worker-launch defect was corrected. The packaged-launch regression test and the actual packaged executable self-test below cover the repaired path; successful source execution alone was not treated as proof that the packaged application worked.

## Packaged Windows integration run

Evidence: [`packaged-evidence/desktop-self-test.txt`](packaged-evidence/desktop-self-test.txt), recorded at `2026-10-29T09:42:59.995156400Z`. The packaged executable self-test completed with exit code 0 on Windows 11 / Java 17.0.20.1.

Five integration checks passed:

1. Created and validated pre-change snapshots for **24 disposable files**.
2. Received **4 real directory notifications** and obtained cooperative pause acknowledgement from the application's own worker. The worker had made **2 writes** before the acknowledgement.
3. Restored into a fresh directory with **24/24 SHA-256 matches**.
4. Rejected a tampered snapshot before restoration.
5. Rejected a forged lab identity/token.

The recorded first worker write was at epoch millisecond `1793266978929`, the first observed alert at `1793266979118`, and cooperative pause acknowledgement at `1793266979311`. Therefore this **single run** measured:

| Measurement | Observed value |
| --- | ---: |
| First worker write to observed alert | 189 ms |
| First worker write to cooperative pause acknowledgement | 382 ms |
| Evaluator worker writes timestamped at or before the alert | 2 |
| Evaluator worker writes timestamped at or before pause acknowledgement | 2 |

These are not p50/p95 distributions, bounds, or expected real-malware response times. Pausing is cooperative control of an application-owned lab worker, not forced suspension of arbitrary malware. Notification bytes describe observed whole-file sizes rather than precise overwritten byte ranges. Only generated disposable lab files were modified; these results do not establish protection of user documents or production data.

## Synthetic replay evaluation

### Repeated packaged measurements

An additional **12 sequential packaged executable runs**, repeated after the shared-manifest and UI update, completed all five integration checks per run (60 checks). Raw reports and observations are in `packaged-evidence/repeated/`; the reproducible collector is `scripts/measure_packaged.py` (Python 3 and psutil). These are cold executable launches of the same fixed 24-file workload, not independent ransomware families.

| End-to-end lab measurement | p50 | p95 (nearest rank) |
| --- | ---: | ---: |
| First worker write to observed alert | 198 ms | 208 ms |
| First worker write to cooperative pause acknowledgement | 380 ms | 389 ms |

Each run recorded two worker writes at or before the alert and pause, and 24/24 restored-file hash matches. Process-tree sampling every 20 ms recorded peak summed RSS of 141.8–143.4 MiB, CPU counters of 1.28–1.72 seconds, read counters of approximately 14.97 MB and write counters of 278.1–278.2 KB per complete check. Wall duration was 1.91–1.97 seconds. The host exposed 20 logical processors and approximately 63.8 GiB physical RAM.

Those resource figures include startup, snapshot creation, restoration and tamper checks. Summed RSS can double-count shared pages; sampling can miss short-lived processes and final counters. Process I/O counters are not physical disk traffic. These figures do not measure steady-state monitoring overhead or interactive UI latency. The JSON records the platform build; Python labels it Windows-10 although this Windows build reports Windows 11 through the JVM.

Evidence: [`evaluation/evaluation.json`](evaluation/evaluation.json), generated at `2026-10-29T09:29:20.900082900Z`.

Each detector mode analyzed **108 variants: 9 teaching templates × 12 held-out seeds (101–112)**. Seeds vary paths, file sizes and timing jitter. Development seeds were 1–3, and the evaluated configuration remained fixed: 2,000 ms window, 4 distinct changed files, and 1.5 bits/byte entropy-change threshold.

The templates are shared with development. This is seed holdout, **not unseen-template or ransomware-family holdout**. Each mode has 48 benign runs, 48 suspicious synthetic runs, and 12 incomplete/overflow runs. The overflow runs are excluded from both detection-rate denominators; they must produce a degraded-evidence state rather than a safe verdict.

| Mode | False-alert benign runs | Missed suspicious synthetic runs | Engine analysis p50 | Engine analysis p95 |
| --- | ---: | ---: | ---: | ---: |
| Rate only | 36/48 (75%) | 12/48 (25%) | 0.0611 ms | 0.2368 ms |
| Entropy only | 12/48 (25%) | 12/48 (25%) | 0.0242 ms | 0.0769 ms |
| Canary only | 0/48 (0%) | 36/48 (75%) | 0.0120 ms | 0.0477 ms |
| Combined | **12/48 (25%)** | **24/48 (50%)** | 0.0124 ms | 0.0447 ms |

All 12 combined-mode false-alert runs were authorized-encryption variants. All 24 combined-mode misses were slow-change or partial-change variants. Rate-only additionally alerted on benign compression and bulk renaming. Canary-only missed suspicious templates that did not touch a designated canary. On this small corpus, the combined rule does **not** dominate the entropy-only baseline.

Consequently RansomScope is presented as an **observation, explanation and recovery workbench**, not as a validated autonomous prevention system. These results are not product accuracy, current-ransomware coverage, or evidence that a particular real attack would be blocked.

The timing columns measure only the pure shared engine processing an in-memory trace after development warm-up. They exclude filesystem observation, UI rendering, process control, disk I/O and restoration. Short teaching traces cannot establish production false alerts per hour. JVM-visible processor count and maximum heap are recorded in the JSON, but system CPU utilization, physical RAM consumption and GPU performance were not measured.

## Synthetic memory fixtures

The evaluation JSON contains **48 fixture runs**:

| Fixture | Runs | Sample-verified candidates |
| --- | ---: | --- |
| Standard expanded AES-128 schedule present | 12 | One verified candidate in each run |
| Schedule erased | 12 | Zero in every run |
| Raw key only | 12 | Zero in every run |
| Corrupted schedule | 12 | Zero in every run |

Each fixture scans 8,192 synthetic bytes. Success means a structural expanded-key candidate reproduced an encrypted 16-byte sample block. The 36 negative fixture runs yielded no sample-verified candidate. This is not live RAM capture, authenticated file decryption, a guarantee about real crypto-provider layouts, or ransomware key recovery. No-result fixtures demonstrate the supported method's limits rather than proving that every recovery method is impossible.

## Browser interaction checks

Manual browser observations recorded during integration:

- Burst replay displayed **12 events and 5 explanation records**.
- The normal-compression scenario displayed **zero alerts** under the selected combined rule.
- The present memory fixture displayed **one verified candidate**; the erased fixture displayed **zero**.
- A JSON event import with a negative timestamp was rejected.

These interaction checks complement the Kotlin/JS Node tests. They are not an exhaustive accessibility, responsive-layout, browser-compatibility, or end-to-end parity certification. Final screenshots and demo provenance are tracked separately when captured.

## Actual benign filesystem controls

`desktop:benignWorkloads` completed four source/JDK controls on newly generated temporary documents. Their actual observation exposures were 5.03, 5.03, 5.04 and 5.04 seconds respectively (about 20.14 seconds total), with no reported collection gaps. Events and resource measurements are preserved in `benign-evidence/`.

| Actual operation | Notifications | Rate-only alerted | Entropy-only alerted | Canary-only alerted | Combined alerted |
| --- | ---: | --- | --- | --- | --- |
| ZIP 24 documents | 2 | No | Yes | No | No |
| Rename 24 documents | 48 | Yes | No | No | No |
| Compile 12 Java classes | 24 | Yes | No | No | No |
| Append 20 ordinary edits to 4 documents | 20 | Yes | No | No | No |

These are known-benign runs, so their alerts are false alerts for these specific operations. Repeated notifications, old/new rename paths and observed file counts are different quantities. In this short exposure, combined mode had zero alerting runs; the separate synthetic evaluation still exposes legitimate-encryption false alerts and slow/partial-change misses. Neither result cancels the other. No per-hour rate is extrapolated from these four brief runs. Longer, representative production observation remains unverified. Harness CPU time includes the benign workload and monitor; JVM heap is not resident process RAM, and logical workspace sizes are not physical disk allocation.

## Remaining validation limits

- No Linux or macOS desktop execution is claimed.
- No real ransomware, arbitrary process-memory acquisition, LSASS access, kernel minifilter, or system-wide pre-write blocking was tested.
- Local snapshots are not protected against a same-user or privileged attacker deleting them.
- Cooperative lab containment and synthetic replay results must remain visibly distinct from passive real filesystem observations.
- Reproducible success on the supplied fixtures does not remove the documented false positives, missed detections, or incomplete-observation states.

## Final integration and demo verification

The final Windows ZIP was also extracted into a separate workspace directory and its extracted executable ran all five self-test checks successfully at `2026-10-29T10:29:29Z`, including 24/24 restored hashes. Evidence: `packaged-evidence/extracted-zip-self-test.txt` and its event JSON. The tested Windows ZIP SHA-256 is `a38120a7b1c32fad2b53607794598542e534edeb2445f6ce2fc5e6e18daa6a6d`.

The final UI revision was rebuilt and exercised on 29 October 2026:

1. Desktop preparation: the guide changed from Create safe demo to Start demo after verified copies were ready. Busy controls were disabled during setup.
2. Desktop observation/review: actual notifications triggered a cooperative pause; the guide offered Review alert, then Restore & verify. The incident screen showed its real rule reasons.
3. Desktop restoration: 24/24 last-checked copies matched; the screen showed Demo complete, the check timestamp and Open restored folder. Moving to Memory lab did not carry over the restore message.
4. Memory comparisons: desktop and browser positive examples produced a sample-verified candidate; erased examples reported no verified candidate. The UI kept the synthetic-only limitation visible.
5. Browser replay: run/reset while running returned progress to zero; burst replay completed with five explanation updates, and normal archive replay completed with eight paths and zero combined-rule alerts. The final incident screen shows actual reasons before expandable controls.
6. Browser import/error/parity: negative-time JSON was rejected while preserving the prior 12-event collection. Four actual packaged-lab observations replayed to one alert; exporting them produced exact JSON event-array equality. The bounded console-error check returned an empty list.
7. Keyboard/reflow: Enter opened replay settings and Tab moved from rule selection to replay pace. At a 390px viewport override, document scroll width equalled its 375px client width (scrollbar consumes the remainder); no page-level horizontal overflow was observed. The temporary viewport override was reset. This is not complete accessibility certification.

Before/after screenshots and review limits are indexed in UX-REVIEW.txt. The refreshed 180-second demo uses these actual screens and was fully decoded without errors; its frame at 65 seconds was inspected against the restored-file screen. It remains an explicitly disclosed edited still-screen walkthrough.

- The packaged desktop UI itself was exercised after the launcher fix: generated lab, started child, observed cooperative pause after two writes, displayed incident explanations and restored 24/24 files with matching SHA-256 hashes.
- Its four exported observations were imported through the browser file chooser, replayed, and produced one combined-mode alert. The browser export was parsed and compared with the original desktop JSON: exact event-array equality (4 events). The elapsed-time display was fixed and checked on this epoch-timestamp input; original timestamps remain in JSON exports.
- Browser console check returned no error entries during that workflow. This is a bounded interactive check, not an exhaustive cross-browser audit.
- The 180.00-second 1920x1080 H.264 demo was decoded completely with FFmpeg without decode errors. A decoded frame at 65 seconds was visually checked against the captured restore screen.
- Video is an edited sequence of real application screenshots with captions, without audio; it is explicitly labelled as such. It is not a continuous recording or evidence of real ransomware execution. Source screenshots, hashes and scene durations are in VIDEO-PROVENANCE.json.
- Clean copy-ready essay: SUBMISSION-ESSAY-300-WORDS.txt (exactly 300 whitespace-separated words). AI assistance disclosure is retained separately in the original author-review file and README.
