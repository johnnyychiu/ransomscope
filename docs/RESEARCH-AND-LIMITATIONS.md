# Research basis and limits

RansomScope is a new competition prototype, informed by established ransomware-defense work. It does **not** claim a novel detection algorithm, a universal vaccine, or results against contemporary ransomware families. Paper results are not RansomScope results.

## Primary research

| Work | Relevant mechanism | Boundary carried into this project |
| --- | --- | --- |
| [ShieldFS, ACSAC 2016](https://trustial.org/publications/continella_shieldfs_2016/) ([author PDF](https://trustial.org/publications/continella_shieldfs_2016/continella_shieldfs_2016.pdf)) | Low-level filesystem behavior modeling with copy-on-write preservation and rollback. | A post-change folder notification is not equivalent to its kernel-level mediation or protection. Our pre-lab snapshots are explicitly prepared copies. |
| [Redemption, RAID 2017](https://theseclab.org/publications/raid2017redemption.pdf) | Filesystem write redirection to buffered copies preserves consistent originals. | RansomScope does not implement transparent write redirection, kernel callbacks, or that paper's data-loss guarantees. |
| [PayBreak, ASIACCS 2017](https://discovery.ucl.ac.uk/id/eprint/1541227/) ([author PDF](https://seclab.bu.edu/papers/paybreak-asiaccs2017.pdf)) | Proactive interception of cryptographic operations escrows keys and parameters for supported implementations. | Instrumented escrow is different from retrospective memory scanning. RansomScope does not inject hooks into arbitrary software. |
| [Davies, Macfarlane and Buchanan, Digital Investigation 2020](https://doi.org/10.1016/j.fsidi.2020.300979) ([university record](https://napier-repository.worktribe.com/output/2657137/evaluation-of-live-forensic-techniques-in-ransomware-attack-mitigation)) | Examines live-memory key availability and validates recovery against selected historical samples. | Abstract, publication metadata and indexed manuscript excerpts were available; a complete manuscript download was not obtained in this research pass. No success-rate or timing guarantee is transferred to this prototype. |
| [ERW-Radar, NDSS 2025](https://www.ndss-symposium.org/wp-content/uploads/2025-349-paper.pdf) | Contextual behavior and fine-grained content analysis address evasive behavior and ambiguity in simple entropy/rate signals. | Our transparent rules and synthetic traces are not a reimplementation of its model, telemetry driver, corpus, or reported performance. |

This is a bounded literature review, not an exhaustive novelty search or a systematic review.

## Platform and privilege model

- Desktop filesystem monitoring observes a selected local directory with the current user's permissions. Ordinary notifications can be delayed, combined, or lost. Their payload does not reliably identify a writer PID. [Microsoft's notification structure](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-file_notify_information) contains actions and filenames, not process identity.
- A suspicious event is a review signal, not proof of malicious intent. Legitimate encryption can produce the same observable features. Unknown attribution must not become a guessed process identity.
- Demonstrated worker pausing is cooperative control of a disposable worker started by the application. It is not forced suspension of an uncooperative process or a system-wide prevention capability.
- Pre-write enforcement requires a different integration boundary. Microsoft's [minifilter callback documentation](https://learn.microsoft.com/en-us/windows-hardware/drivers/ifs/writing-preoperation-and-postoperation-callback-routines) describes such lower-level interception. RansomScope does not install a minifilter or disable operating-system security controls.
- Snapshots are local copies made before the lab changes. A same-user or privileged attacker may delete or modify them. They are not immutable, isolated, or a replacement for independently protected backups. Restoration uses a new destination and verifies recorded hashes; a matching hash establishes copy equality, not that the original was safe or current.
- Browser functionality is event import, local analysis, replay, and synthetic experimentation. It cannot monitor arbitrary desktop processes or read their memory. Core logic is compiled from shared Kotlin sources for both targets.

## Memory laboratory

The current laboratory generates synthetic byte arrays containing, or deliberately lacking, a standard forward AES-128 expanded-key schedule. The scanner receives bytes without the fixture offset or an expected-key marker. Structural candidates are checked by reproducing an encrypted 16-byte sample block using reference AES.

This establishes a narrowly defined fixture result. It is **not** live process-memory acquisition, evidence that any particular JCA provider retains that layout, authenticated file decryption, generic recovery of random raw keys, or real ransomware validation. Erased, corrupted and raw-key-only fixtures can legitimately produce no result. “Not found” means the supported method did not find a sample-verified candidate; it does not prove that every possible recovery route is unavailable.

The reference block implementation is for public laboratory fixtures, not production cryptography. No arbitrary-process, LSASS, or whole-system memory collection is part of this prototype. Memory fixtures and keys must not be confused with real incident evidence.

## Evaluation interpretation

- Demonstration traces are synthetic and their labels are evaluation metadata, never detector inputs. Writer labels supplied by a trace are not proof that a desktop watcher can recover attribution.
- Held-out seeds vary paths, event sizes and timing jitter on teaching templates. The templates are shared with development, so seed separation does not establish generalization to unseen ransomware families or real production workloads.
- Comparisons cover rate-only, entropy-only, canary-only and combined rules with fixed configuration. Benign compression, renaming, editing and authorized encryption expose false positives; slow and partial changes expose missed detections.
- Per-run false-alert counts are meaningful for these traces. Short trace durations must not be converted into claimed production false-alert rates per hour.
- The size aggregate uses the maximum observed file size for each changed path. It is not a measurement of exact overwritten byte ranges, irrecoverable loss, or every intermediate write.
- Replay analysis time is not end-to-end filesystem detection latency or containment time. Worker pause and restoration, when measured, need their own evidence and labels.
- Overflow or missing observations reduces completeness. “No threshold crossed” is never a safe or clean verdict. Synthetic memory fixture success is reported separately from snapshot restoration.

## Original work and assistance

Existing research and earlier architecture discussions inform the design, but their existence does not establish ownership of any old implementation, client material, or unverified experimental result. The competition codebase is new. Preserve third-party licenses and attribute ideas and dependencies.

Development and writing assisted by AI tools; the applicant must review the work, confirm original rights, and avoid claiming that every line was written unaided. The final entry must accurately describe the delivered version and the measurements actually collected.
