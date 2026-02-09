# Episode 3: Phase 6 Security Buildout and Phase 7 Validation

## 1. Context for This Episode
- This episode focused on implementing and stabilizing the server-side Security Layer (Phase 6), while validating Export Engine behavior (Phase 7) through real device + curl testing.
- The rough goal was to make auth/session/network protections production-usable, verify export behavior under auth/rate-limit constraints, and update project tracking (`DEVPLAN.md`) without breaking existing logic.

## 2. Main Problems We Faced

### Issue: Test helper JVM signature clash
- Symptoms:
  - Kotlin compile errors in `ServerInfrastructureTest`:
    - `Platform declaration clash ... getPasscodeHash()`
    - `Platform declaration clash ... setPasscodeHash(String)`
- Why it mattered:
  - Blocked all server unit tests and compile verification for the new security code.

### Issue: Dashboard start failures were opaque
- Symptoms:
  - User reported dashboard not starting, but logs were mostly vendor/device noise.
  - No direct app-side error trace for server startup failures.
- Why it mattered:
  - Hard to diagnose runtime failures; user could not tell whether server actually started.

### Issue: Request body parsing failed for auth endpoints from PowerShell/curl
- Symptoms:
  - Repeated `400 INVALID_REQUEST` for passcode/open-access calls:
    - "Request body must include a passcode."
    - "Request body must include open access toggle details."
  - `Invoke-RestMethod` also showed protocol/header behavior issues in user environment.
- Why it mattered:
  - Core auth setup flows (set passcode / toggle open access) were unreliable in real testing.

### Issue: Auth behavior looked inconsistent because Open Access stayed enabled
- Symptoms:
  - `/api/v1/auth/session` showed `"open_access_enabled": true`.
  - Protected endpoints returned `200` even with wrong/no cookie file.
- Why it mattered:
  - Masked session behavior and made it look like cookies were not enforced.

### Issue: Export verification confusion (invalid ZIP / empty listing)
- Symptoms:
  - ZIP appeared invalid or did not list entries.
  - Export call after logout returned `429` instead of expected `401`.
- Why it mattered:
  - Prevented clean Phase 7 verification and mixed auth-vs-rate-limit expectations.

### Issue: Middleware ordering caused post-logout export checks to hit rate limit first
- Symptoms:
  - Export endpoint returned `429 EXPORT_RATE_LIMITED` after logout in some test orderings.
- Why it mattered:
  - Obscured auth correctness and made negative auth tests ambiguous.

### Issue: Git push race during finalization
- Symptoms:
  - Commit/push run in parallel caused:
    - temporary `index.lock` race
    - first push reporting "Everything up-to-date" before commit finished
- Why it mattered:
  - Risk of assuming changes were pushed when branch was still ahead locally.

## 3. Debugging Path & Options Considered

### For test signature clash
- Debugging path:
  - Ran full Gradle checks and read compiler output.
  - Isolated clash to test double property name vs interface methods.
- Options considered:
  - Keep property name and add suppressions (not chosen).
  - Rename backing property to avoid generated accessor collision (chosen).
- Outcome:
  - Helpful discovery: issue was in test helper, not production security code.

### For opaque dashboard startup
- Debugging path:
  - Inspected service/controller/startup path and manifest wiring.
  - Reviewed real log snippet and identified missing app-level failure logging.
- Options considered:
  - Ask only for more logs (partial).
  - Add explicit service error logging + startup status polling in UI (chosen).
  - Improve LAN host/IP candidate handling to reduce false host rejections (chosen).
- Outcome:
  - Helpful discovery: needed observability and host/IP robustness, not just one crash fix.

### For body parsing failures
- Debugging path:
  - Reproduced `INVALID_REQUEST` behavior from user commands.
  - Reviewed `receiveJsonBody()` and how PowerShell payload forms arrive.
- Options considered:
  - Force one canonical client command only (not sufficient).
  - Expand parser tolerance (escaped JSON, quoted JSON, form-urlencoded) (chosen).
- Dead ends:
  - Some JSON payload styles still failed before parser hardening.
- Outcome:
  - Helpful discovery: user environment differences required tolerant server-side decoding.

### For open-access confusion
- Debugging path:
  - Checked `/auth/session` output and security middleware conditions.
  - Confirmed open-access state is persisted and bypasses auth.
- Options considered:
  - Debug cookies first (misleading while open access is on).
  - Explicitly set open access false and re-verify auth (chosen).
- Outcome:
  - Helpful discovery: endpoint behavior was correct for current config, not a cookie bug.

### For export/zip/auth-after-logout behavior
- Debugging path:
  - Reviewed export route streaming/deletion logic and user command usage.
  - Traced request pipeline order for auth vs rate limit.
- Options considered:
  - Keep current order (rate limit before auth) (rejected).
  - Move auth before rate limit for clearer unauthorized behavior (chosen).
  - Clarify client download commands (`-i` corrupts saved binary) (chosen).
- Outcome:
  - Helpful discovery: two separate factors: command usage (`-i`) and middleware ordering.

### For push race
- Debugging path:
  - Checked branch divergence with `git status -sb` and rev-list counts.
- Options considered:
  - Assume push succeeded (dead end).
  - Re-run push sequentially after commit completion (chosen).
- Outcome:
  - Helpful discovery: parallelized git commit/push can race and mislead status.

## 4. Final Solution Used (For This Chat)

### Issue: Test helper JVM clash
- Fix used:
  - Renamed in-memory test store property (`passcodeHash` -> `storedPasscodeHash`) in tests.
- Files/layers:
  - `server/src/test/java/com/heliolan/server/ServerInfrastructureTest.kt`
- Conceptual change:
  - Prevented generated getter/setter collisions; restored test compile stability.

### Issue: Startup diagnostics + runtime robustness
- Fix used:
  - Added explicit startup failure logging in service.
  - Moved service startup coroutine work to IO dispatcher.
  - Added UI polling to reflect actual server startup status.
  - Improved LAN address selection and host candidate resolution.
- Files/layers:
  - `app/.../DashboardForegroundService.kt`
  - `app/.../MainActivity.kt`
  - `server/.../LanAddressResolver.kt`
  - `server/.../DashboardServerApplication.kt`
- Conceptual change:
  - Better observability and fewer false negatives for host/IP validation.

### Issue: Auth body parsing reliability
- Fix used:
  - Hardened request parsing to accept multiple body forms (escaped JSON, quoted JSON, form-urlencoded).
- Files/layers:
  - `server/src/main/java/com/heliolan/server/DashboardServerApplication.kt`
- Conceptual change:
  - Endpoint input handling became resilient to PowerShell/curl variations.

### Issue: Auth vs rate-limit ambiguity
- Fix used:
  - Reordered middleware so auth executes before API rate limit.
- Files/layers:
  - `server/src/main/java/com/heliolan/server/DashboardServerApplication.kt`
- Conceptual change:
  - Unauthorized requests now fail as auth failures first, improving test clarity.

### Issue: Phase tracking and validation
- Fix used:
  - Updated Phase 6 and security checklist progress in `DEVPLAN.md`.
  - Added/expanded infrastructure and export tests.
  - Re-ran compile/tests across `server`, `app`, and `sync`.
- Files/layers:
  - `DEVPLAN.md`
  - `server/src/test/...`
- Conceptual change:
  - Phase state now reflects implemented security/export behavior and tested paths.

### Issue: Release/push completion
- Fix used:
  - Committed with short message: `Harden auth and export`.
  - Pushed commit `4facb16` to `origin/main` after sequential retry.
- Conceptual change:
  - Final code state published to remote after resolving git race artifacts.

## 5. Tools, APIs, and Concepts Used
- **Ktor pipeline phases**: Implemented and reordered security/auth/rate-limit middleware for deterministic behavior.
- **Kotlinx serialization + manual body parsing**: Added tolerant request decoding for real-world client payloads.
- **SharedPreferences security state**: Persisted `open_access_enabled` and passcode hash behavior.
- **Session + lockout managers**: Enforced token lifecycle and brute-force throttling in-memory.
- **curl on PowerShell**: Used to validate auth/export endpoints and exposed quoting/header edge cases.
- **Gradle test/build tasks**: Verified no syntax regressions (`:server:test`, `:app:compileDebugKotlin`, `:sync:test`).
- **Git (commit/push/status divergence checks)**: Managed publish flow and corrected a push race.

## 6. Lessons Learned (For This Episode)
- Always verify feature flags/config state (`open_access_enabled`) before debugging auth tokens.
- Server-side body parsing should tolerate realistic client encoding differences, especially in Windows shells.
- For API security testing, middleware order matters as much as middleware logic.
- Binary download checks must avoid `curl -i` when writing files, or artifacts get corrupted.
- Add app-side startup diagnostics early; vendor log noise can hide the real fault.
- Negative tests (logout -> protected route) are only meaningful when bypass modes are explicitly disabled.
- Run commit and push sequentially when finalizing; parallel git operations can create false confidence.
