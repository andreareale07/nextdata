# UX test report

Environment: Android emulator `nd_pixel` (API 34, x86_64, 1080x2400, real engine `libnd_engine.so`), Windows 11 desktop (real `nd_engine.dll` and `ND_FAKE_ENGINE=1`). Local synthetic servers on 127.0.0.1 (SFTP asyncssh, WebDAV node, S3 moto). Screenshots in `docs-screens/`. "PASS" = executed and observed.

## Results

| Test | Env | Result | Evidence |
|---|---|---|---|
| Create WebDAV profile with the 3-step guide, URL paste fixed with "Use the host only", HTTPS-off warning, test connection | Android | PASS | phone-wizard-urlfix.png, phone-wizard-insecure.png, phone-wizard-test-ok.png |
| Create S3 profile + test (failed first without path-style: hint shown) | Android | PASS (after fix) | phone-wizard-s3.png |
| Create SFTP profile on desktop with the guide, onboarding first run | Desktop real engine | PASS (after fix 1) | desktop-onboarding.png, desktop-wizard-step1..3.png, desktop-wizard-test-ok.png |
| Connect SFTP / WebDAV / S3, browse | Android | PASS | phone-light-list.png, phone-webdav-list.png, phone-s3-list.png |
| TOFU new host (dialog, reject, accept) | Android + desktop | PASS | dialog-hostkey-new-phone.png, desktop-wizard-tofu.png |
| Host key CHANGED blocks and shows alarm; cancel -> localized error; replace needs checkbox | Android | PASS | dialog-hostkey-changed-phone.png, dialog-connect-failed-hostkey.png |
| 450 entries, automatic load of following pages | Android | PASS (reached file_450) | phone-light-bottom.png |
| Quick filter, selection (long press), contextual bar | Android | PASS | phone-filter2.png, phone-selection.png |
| Sort, mkdir, rename | Android | PASS (server verified) | phone-newfolder-dialog.png, phone-rename.png |
| Delete 2 files; delete folder recursively | Android | PASS (server verified) | phone-delete-dialog.png, phone-delete-folder-dialog.png |
| Upload picked files (SFTP, WebDAV) with "preparing" feedback | Android | PASS (see defect 5) | phone-upload-preparing.png |
| Download to SAF folder, permission persisted, 25 s in the picker (connection dropped) then success | Android | PASS (after fix 6) | phone-dl-after-picker.png |
| Cancel a 200 MB upload mid-way, no partial left on the server | Android | PASS | phone-transfer-cancelled.png |
| Network off during transfer -> localized error with technical detail; network on -> Retry | Android | PASS | phone-transfer-network-lost.png |
| App in background 20 s -> automatic reconnect on return | Android | PASS (server saw a new connection) | phone-after-background.png |
| Restart (`force-stop`): profiles and last folder restored | Android | PASS | phone-restore-lastfolder.png |
| Favorites + recents | Android | PASS | phone-places-menu.png |
| Dark theme, font scale 2.0, landscape, tablet (wm size) | Android | PASS (layout fixes 7-9) | phone-dark-list.png, phone-font200-*.png, phone-landscape.png, tablet-landscape.png |
| 3 zone layout, command bar, sortable/resizable columns, details pane, grid, filter, F2, Del, arrows, Alt+Enter, Ctrl+F | Desktop | PASS | desktop-dark.png, desktop-light.png, desktop-details-pane.png, desktop-grid-view.png, desktop-filter.png, desktop-colresize-multiselect.png |
| Context menu (right click) | Desktop | PASS after fix 2 | desktop-context-menu.png |
| Drag and drop of a file onto the window (real engine, SFTP) | Desktop | PASS (file on the server, list refreshed) | desktop-after-drop.png |
| Width < 600 dp -> single pane | Desktop | PASS | desktop-narrow-profiles.png |
| Theme / density / view settings | Desktop | PASS | desktop-settings.png |
| Unit/E2E: `:shared:allTests`, `:desktopApp:test` with `ND_ENGINE_E2E=1` (fake + real SFTP upload 10 B/20 KB/700 KB, list, download, delete) | JVM | PASS | gradle output |

## Defects found and fixed

1. First-run "Add my first profile" did not open the guide (request emitted while no collector existed): now a consumed state flag.
2. Right-click menu did nothing / opened at the wrong place (`awaitFirstDown` ignores secondary button; anchor was the whole list): raw pointer events + 1 dp anchor at the pointer.
3. Test connection created a duplicate profile after a failed test: result now always returns the saved profile id.
4. Failing listing trapped Back (session dead): Back now leaves the connection; "Reconnect" action added.
5. Upload from the picker gave no feedback while copying 200 MB: "Preparing..." banner; uploads now have a total (progress bar, speed, ETA).
6. Android closes the app sockets after ~15-20 s in the background (every transfer after the system picker failed with "session closed"): probe on resume + automatic reconnect + transfers re-targeted and restarted; foreground service while transfers are active; persisted SAF permission, reuse of the last folder, specific errors (permission lost / disk full) and "choose folder again" on retry.
7. Bottom sheets were cut at 200% font: scrollable. Dialog actions of trust dialogs scroll together with the body.
8. Command bar clipped on narrow wide-layout (phone landscape): overflow menu below 900 dp; navigation pane is a single scroll list.
9. Table header misaligned with touch rows (checkbox column): aligned.
10. FAB collapsed had no accessible name; filter did not scroll to top; selection kept hidden items; favorites at root were named "/"; details location duplicated the profile name.
11. Contrast: dark `onError` on `error` was 3.6:1: fixed (contrast test added).

## Not fixed / limits (honest)

- Engine (not touched): the 20 s connect timeout also covers the time the user needs to read a trust-on-first-use fingerprint, so the connection fails with TIMEOUT if the user takes longer. The UI explains it after the fact ("confirm within about 20 seconds"); the engine should stop the timer while a prompt is open.
- Engine: a dead SFTP session is reported as `IO` with detail "session closed"; the UI recognises it by that detail.
- One initial SFTP upload failure against a long-running test server instance was never reproduced after the server was restarted; the later failures were the background/socket issue above.
- Not implemented: rubber-band selection, real pause of a running transfer (engine has no pause; only "pause queue" for waiting items), "open file" after download on Android depends on the file manager handling the folder URI, persisted column widths, RTL and TalkBack were not tested, Windows narrator not tested, MSI/EXE installers not built, reduce-motion tested only by code path (no device toggle run), predictive-back animation not visually verified.
- Android language switch IT/EN and `.ndprofile` export/import were not re-run on the emulator in this round (desktop language is IT from the system and verified visually; export/import covered by earlier unit tests only).
