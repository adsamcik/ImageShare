# Play Console Submission Checklist — ImageShare v1.0

Use this first-time submission walkthrough to move from repository artifacts to a Play Store release.

## 1. One-time Google Play Console setup

1. Go to https://play.google.com/console.
2. Register for a developer account.
3. Pay the one-time registration fee, currently USD $25. `<TODO: confirm current fee in your country before registration.>`
4. Complete identity verification.
5. Add tax information if prompted.
6. Add payment profile details if needed.
7. Add banking details only if future paid apps or payouts are planned. ImageShare v1.0 is expected to be free.

## 2. Create new app

1. Click **Create app**.
2. App name: `ImageShare`.
3. Default language: `<DECISION REQUIRED: choose English (United States) or English (United Kingdom).>`
4. App or game: App.
5. Free or paid: Free.
6. Confirm declarations and Play policies.

## 3. App content section

Complete **Policy and programs → App content**:

- Privacy policy URL: use the hosted URL from `docs/PRIVACY_POLICY_HOSTING.md`.
- Ads: No ads.
- App access: No restricted login or gated app access.
- Content rating: likely Everyone / 3+.
- Target audience: likely 13+. `<DECISION REQUIRED: confirm whether ImageShare is intended for children or only general users 13+.>`
- News app: No.
- COVID-19 contact tracing or status: No.
- Data safety: follow `docs/DATA_SAFETY.md`.
- Permissions justification: explain local notifications if Play asks about `POST_NOTIFICATIONS`.

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

`<TODO: choose final brand tagline, screenshots, and feature graphic artwork before submission.>`

## 5. Production track configuration

Recommended rollout path:

1. Start with **Internal testing**.
   - Invite tester emails.
   - Upload a signed build.
   - Internal testing usually does not require full public review before tester access.
2. Graduate to **Closed testing** after smoke testing.
3. Use **Open testing** only if broader pre-launch feedback is wanted.
4. Promote to **Production** after policy checks, data safety review, and release confidence.

## 6. Upload signed app bundle

Prefer Android App Bundle (`.aab`) over APK for Play Store distribution:

```powershell
.\gradlew.bat :app:bundleRelease
```

Use `docs/RELEASE_SIGNING_SETUP.md` before building. Enroll in Play App Signing during first upload so Google signs distributed artifacts with managed keys.

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

`<TODO: decide minimum observation window between rollout increases, e.g. 24 or 48 hours.>`

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
- Target SDK below Play policy requirements. As of August 2024, new apps must target at least Android 14 / API 34; ImageShare targets compileSdk 36.
- Misleading screenshots or descriptions.
- Broken app bundle, startup crash, or inaccessible core flow.

## 10. Post-submission

- Review time can range from hours to days.
- Respond promptly to Google policy emails.
- Keep release notes and support contact available.
- Save screenshots of final declarations for future updates.
- `<TODO: record Play Console owner account and backup admin account.>`
