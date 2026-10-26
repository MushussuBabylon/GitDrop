# Changelog

## 1.0

**New**
- **Review before upload.** A new screen lists every file as *new*, *changed* or *same* (compared with the target branch), with sizes, filters and a tick box per file. Nothing is changed on GitHub until you confirm.
- **Secret check.** Passwords, API tokens, private keys, `.env` files and keystores are detected on the device. Flagged files start unticked, are never ticked by *Select all*, and need a second confirmation.
- **Project check.** Warns when `applicationId` in an uploaded `build.gradle(.kts)` differs from the repository's copy.
- **Verified result.** After uploading, GitDrop asks GitHub again and confirms the branch points to the last commit.
- **New branch…** on the repository screen creates a branch from the selected one and selects it.
- **Share to GitDrop.** The app appears in the share sheet; shared files join the selection.
- **New icon and look.** Launcher icon (adaptive, round, themed/monochrome) and in-app branding from the GitDrop logo; blue accent; top bar with back arrow and a pinned bottom action button on every screen; Android 12+ splash screen in the brand colour.
- 46 new strings in all 10 languages.

**Changed**
- *Upload to GitHub* is now *Review and upload*; the upload itself starts from the review screen.
- The final report also lists files you left out and files kept out because they may contain secrets.
- The long explanation on the selection screen is folded to three lines (tap to unfold).

**Unchanged**
- The upload engine (batching, rate limits, retries, resume, stop button) is untouched. The app still needs only the `INTERNET` permission and still has no dependencies.

**Developer**
- JVM unit tests (`gradle testDebugUnitTest`) for the secret scanner, Base64 decoder, branch-name rules and Gradle parser.
- `tools/make_icons.py` regenerates all artwork from `art/gitdrop-logo.png`.
- The GitHub Actions workflow now runs only when you start it (Actions tab, Run workflow), builds a release APK and signs it with your own key, kept in four repository secrets (see the README, section Signing).
