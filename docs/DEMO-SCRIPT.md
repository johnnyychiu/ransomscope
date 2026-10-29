# RansomScope demo — three-minute recording plan

This is a shot and narration outline for an **edited recording of actual application screens**, using synthetic incident traces and app-generated disposable files. It is not a live ransomware attack, a staged animation presented as actual memory capture, or a claim of universal protection. Record the real run and adapt every result to what the application actually reports.

## Before recording

1. Build the desktop and browser applications from the same source revision.
2. Run the tests and evaluation described in the README. Keep the resulting report with the submission materials. Do not narrate a pass that has not been verified.
3. Start the browser server on `127.0.0.1:8766`; use an ordinary fresh browser session with no personal tabs visible.
4. Open the desktop application on its initial empty dashboard. Use the app's generated lab only; do not select real documents for the destructive lab workflow.
5. Close notifications, private filenames and unrelated windows. Do not show an account, private contact information, token, or full memory dump.
6. Capture at a readable resolution, preferably 1440×900 or 1920×1080, with no time-lapse implying faster detection than measured.

Keep the label **“Edited application demo · Synthetic data”** visible in the opening and whenever the source changes. Use short cuts to skip setup or waiting, and label those cuts. Never replace a missing function with an animated success screen.

## 0:00–0:20 — Product and empty desktop dashboard

**Actions:** Show the desktop Overview with no events, then highlight the monitoring status and the separation between Overview, Incidents, Recovery and Memory lab.

**Narration:**

> RansomScope helps you inspect suspicious file changes and understand your recovery options. This is a Kotlin Multiplatform prototype, not a universal ransomware vaccine. We will use synthetic events and a disposable desktop lab. No real malware is included.

## 0:20–0:50 — Browser burst replay and explanations

**Actions:** Switch to the browser. Choose **Rapid file changes**, leave **Combined signals** selected, and run the replay. Open **Incident replay**, select a changed path and show its attributes and the engine's reasons.

**Narration:**

> This browser compiles the same Kotlin analysis engine as the desktop. It replays a synthetic event collection and explains which signals triggered an alert. It does not monitor the operating system. An unknown writer remains unknown; a file event is not proof of which process caused it.

Show event provenance and real engine output. Avoid saying “attack stopped” here: a browser replay does not stop anything.

## 0:50–1:10 — Benign comparison

**Actions:** Select **Normal archive creation**. Run it with **Combined signals** and briefly show **Entropy only** or **Write rate only** for comparison. If the chosen baseline does not alert in the actual run, do not claim that it did; narrate the observed result.

**Narration:**

> Normal software can also produce many files or high-entropy data. Baseline controls let us compare rules against the same evidence. These settings are heuristics, not a probability that a process is malicious. A missing alert is not proof of safety.

## 1:10–1:45 — Actual desktop observation and worker pause

**Actions:** Return to the desktop. Select **Create demo with snapshot** and show its successful pre-change snapshot status. Start the owned lab worker, show actual filesystem events, and show its pause acknowledgement. A cut may skip snapshot setup, but keep the acknowledgement and resulting state readable.

**Narration:**

> The desktop creates its own test files and copies them before any changes. Its watcher observes the worker modifying those disposable files. On an alert, the owned worker can acknowledge a cooperative pause request. This is control of our test worker, not forced suspension of arbitrary ransomware.

Do not report the replay timestamps as desktop detection latency. If a watcher overflow or error occurs, show the incomplete state and rerun from a fresh lab after diagnosing it; do not hide it behind a fabricated success.

## 1:45–2:10 — Verified recovery to a new directory

**Actions:** Open **Recovery**, choose **Restore to new folder**, and show the actual restored-file verification result and new output directory.

**Narration, only if the run confirms 24/24:**

> In this run, all 24 lab files were restored into a new directory, and their SHA-256 hashes match the pre-change manifest. Existing files were not overwritten. These same-user local copies are not tamper-proof or offline backups.

If another count is shown, use that count. If verification fails, do not use this success narration. Explain and correct the failure before recording the successful workflow.

## 2:10–2:40 — Shared memory lab, positive and negative

**Actions:** Open the browser **Memory lab**. Run **Expanded AES schedule present**, then **Schedule erased** using the same seed. Keep the synthetic-fixture notice and each actual result visible.

**Narration:**

> The experimental memory lab searches a generated byte buffer for a supported AES key-schedule structure and validates candidates against the sample. The erased case demonstrates an honest negative. This is a scanner test, not live RAM acquisition or proof that a real ransomware family can be decrypted.

If the fixture does not verify, show the real failure and investigate it. Never substitute a prerecorded green result from a different code revision without disclosure.

## 2:40–3:00 — Cross-platform value and boundaries

**Actions:** Show **Methods & limits**, then finish with the actual desktop and browser side by side or two clearly labelled cuts. Add the final project title and the repository URL only after that URL exists and has been verified.

**Narration:**

> Kotlin shares the evidence model, detection rules, explanations, replay and fixture scanner across JVM and JavaScript. Desktop-only capabilities stay separate. RansomScope's goal is a clear, reproducible path from suspicious changes to verified recovery evidence, with limitations visible at every step.

## Recording acceptance checklist

- [ ] Both desktop and browser screens are from the build shipped with the source.
- [ ] Opening label states edited recording and synthetic data.
- [ ] Every shown button invokes a functioning feature; no static mock success is substituted.
- [ ] Snapshot exists before the lab worker starts.
- [ ] Pause is described as cooperative worker control.
- [ ] Restore count and SHA-256 statement match the actual verified run.
- [ ] Memory positive and negative are actual shared-scanner results, labelled synthetic.
- [ ] No personal files, credentials, arbitrary process memory, or real malware appear.
- [ ] Any published source/demo link is real and works without the author's private account.
- [ ] Captions, cuts and narration do not imply real-malware validation or universal prevention.
