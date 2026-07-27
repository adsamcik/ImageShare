# Play Console Submission Checklist — ImageShare v1.0

Use this first-time submission walkthrough to move from repository artifacts to a Play Store release.

## 1. One-time Google Play Console setup

1. Go to https://play.google.com/console.
2. Register for a developer account.
3. Pay the registration fee shown for the selected account type and country.
4. Complete identity verification.
5. Add tax information if prompted.
6. Add payment profile details if needed.
7. Add banking details only if future paid apps or payouts are planned. ImageShare v1.0 is expected to be free.

## 2. Create new app

1. Click **Create app**.
2. App name: `ImageShare`.
3. Default language: English (United States), matching `fastlane/metadata/android/en-US/`.
4. App or game: App.
5. Free or paid: Free.
6. Confirm declarations and Play policies.

## 3. App content section

Complete **Policy and programs → App content**:

- Privacy policy URL: use the hosted URL from `docs/PRIVACY_POLICY_HOSTING.md`.
- Ads: No ads.
- App access: No restricted login or gated app access.
- Content rating: likely Everyone / 3+.
- Target audience: 13+; the app is not designed for children.
- News app: No.
- COVID-19 contact tracing or status: No.
- Data safety: follow `docs/DATA_SAFETY.md`.
- Permissions justification: explain local notifications if Play asks about `POST_NOTIFICATIONS`.
- Foreground service declaration: select `mediaProcessing`; describe user-initiated batch image conversion, explain that interruption leaves the requested batch incomplete, and provide a public reviewer-accessible video showing batch start, the ongoing notification, progress, and cancellation.

## 4. Main store listing

Use `docs/PLAY_STORE_COPY_TEMPLATE.md` and `docs/SCREENSHOTS_CAPTURE_GUIDE.md`.

Required fields and assets:

- App title: max 30 characters.
- Short description: max 80 characters.
- Full description: max 4000 characters.
- Feature graphic: 1024×500 PNG.
- App icon: 512×512 PNG.
- Phone screenshots: 2–8 images.
- Optional 7-inch tablet screenshots: 1–8 images.
- Optional 10-inch tablet screenshots: 1–8 images.

Use the finalized icon and feature graphic in `docs/store-assets/`; upload the approved screenshots from `fastlane/metadata/android/en-US/images/phoneScreenshots/`. Run `python3 tools/verify_play_readiness.py` before every upload to validate metadata lengths, image dimensions, permissions, SDK levels, and release wiring.

## 5. Production track configuration

Recommended rollout path:

1. Start with **Internal testing**.
   - Invite tester emails.
   - Upload a signed build.
   - Internal testing usually does not require full public review before tester access.
2. Graduate to **Closed testing** after smoke testing. Personal accounts created after November 13, 2023 must keep at least 12 testers opted in continuously for 14 days before applying for production access.
3. Use **Open testing** only if broader pre-launch feedback is wanted.
4. Promote to **Production** after policy checks, data safety review, and release confidence.

## 6. Upload signed app bundle

**Publication policy:** every ImageShare Google Play release is uploaded, reviewed, and
rolled out manually in Play Console. The GitHub Actions workflow only builds and stores a
signed AAB as a GitHub artifact; it has no Play publishing credentials or deployment step.

Prefer Android App Bundle (`.aab`) over APK for Play Store distribution:

```powershell
.\gradlew.bat :app:bundleRelease
```

Use `docs/RELEASE_SIGNING_SETUP.md` before building. Enroll in Play App Signing during first upload so Google signs distributed artifacts with managed keys.

Alternatively, manually run **Build Play bundle (manual)** in GitHub Actions, download its
artifact, verify the included SHA-256 checksum, and upload the AAB in Play Console yourself.

## 7. Release rollout

Use staged rollout for production:

1. 1%
2. 5%
3. 20%
4. 50%
5. 100%

Monitor:

- Android vitals crash rate.
- ANR rate.
- User reviews.
- Policy emails.
- Device-specific issues.

Hold each rollout stage for at least 48 hours and longer when install volume is too low to make crash and ANR signals meaningful.

## 8. Rollback if needed

If a serious issue appears:

1. Open the active production release.
2. Select **Halt rollout** to stop expansion.
3. Prepare and upload a fixed version with a higher version code.
4. Roll forward with the fix. Play generally does not support reinstalling an older version over a newer version.

## 9. Common rejection reasons

- Missing or inaccessible privacy policy URL.
- Data safety form contradicts the app behavior.
- Permissions not justified, especially `POST_NOTIFICATIONS`.
- Target SDK below Play policy requirements. Starting August 31, 2026, new mobile apps and updates must target Android 16 / API 36; ImageShare already targets API 36.
- Native libraries incompatible with 16 KB page sizes. Play requires 16 KB compatibility for submissions targeting Android 15+; re-run the release artifact check if native codecs are enabled.
- Misleading screenshots or descriptions.
- Broken app bundle, startup crash, or inaccessible core flow.

## 10. Post-submission

- Review time can range from hours to days.
- Respond promptly to Google policy emails.
- Keep release notes and support contact available.
- Save screenshots of final declarations for future updates.
- Record the Play Console owner account and at least one backup administrator in the team's private credential inventory.
- Complete Android developer identity and package-name registration prompts before the September 30, 2026 enforcement date.
