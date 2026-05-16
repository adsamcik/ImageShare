# Privacy Policy Hosting — ImageShare

Play Console requires a stable, public privacy policy URL before submission. Host the existing `docs/PRIVACY_POLICY.md` verbatim and update `docs/STORE_LISTING.md` with the final URL.

## Option A: GitHub Pages (recommended)

GitHub Pages is free and provides a stable HTTPS URL.

1. Make the repository public when ready. `<DECISION REQUIRED: confirm whether ImageShare will be public before Play submission.>`
2. Open the repository on GitHub.
3. Go to **Settings → Pages**.
4. Under **Build and deployment**, choose:
   - Source: **Deploy from a branch**
   - Branch: `main`
   - Folder: `/docs`
5. Save the settings.
6. Wait for GitHub Pages to publish.
7. Open the generated URL, usually:

```text
https://<TODO: github-username-or-org>.github.io/<TODO: repository-name>/PRIVACY_POLICY
```

If GitHub serves the Markdown page with a `.html` suffix, use the exact final browser URL.

### Optional custom domain

If using a custom domain:

1. Add the domain in **Settings → Pages → Custom domain**.
2. Configure DNS records as GitHub instructs.
3. Enable **Enforce HTTPS**.
4. Verify the URL from a private/incognito browser window.

Final custom URL example:

```text
https://<TODO: privacy policy domain or path>
```

## Option B: Personal site

Use this option if the maintainer already controls a stable website.

1. Convert `docs/PRIVACY_POLICY.md` to a public page without changing the wording.
2. Publish it under a durable URL, for example:

```text
https://<TODO: your-domain>/imageshare/privacy
```

3. Ensure the page loads without login, scripts, interstitials, geoblocking, or cookie walls.
4. Add redirects if the site structure changes later.

## Option C: Notion / Markdown hosting service

Use this only as a fallback.

1. Publish a read-only public page.
2. Disable edit access.
3. Confirm the content matches `docs/PRIVACY_POLICY.md`.
4. Prefer a custom domain or permanent redirect if the hosting service supports it.

## What the URL must do

- Load over HTTPS.
- Be accessible publicly at Play Store submission time.
- Stay stable for the lifetime of the app, or have a redirect plan.
- Match `docs/PRIVACY_POLICY.md` verbatim unless the repository copy is updated at the same time.
- Not require authentication, cookies, age gates, or JavaScript-only rendering.
- Be reachable from Google review systems.

## Update `STORE_LISTING.md` after hosting

After verifying the hosted page, replace:

```text
<TODO: insert hosted privacy policy URL after completing docs/PRIVACY_POLICY_HOSTING.md>
```

with the final URL in `docs/STORE_LISTING.md`.

Also paste the same URL into Play Console under **App content → Privacy policy**.

## Pre-submission verification

- [ ] Hosted page opens in a private/incognito browser window.
- [ ] URL uses HTTPS.
- [ ] Content matches `docs/PRIVACY_POLICY.md`.
- [ ] URL added to `docs/STORE_LISTING.md`.
- [ ] URL added to Play Console.
- [ ] `<TODO: set a calendar reminder to re-check the URL before production rollout.>`
