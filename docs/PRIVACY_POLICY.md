---
layout: default
title: Privacy Policy — ImageShare
permalink: /privacy/
---

# Privacy Policy — ImageShare

Last updated: 2026-07-27

## Overview
ImageShare is a privacy-first Android app for resizing and sharing images.
It processes everything **locally on your device** and does not collect,
transmit, store on a server, sell, or share your data.

## What we don't do
- We do not collect any personal information.
- We do not use analytics or crash reporting.
- We do not include advertising.
- We do not have user accounts or a cloud service.
- We do not access the network. Our app has no INTERNET permission.

## What stays on your device
- Your default preset choice
- A list of up to 12 recently-opened files (when you use the Files-app entry)
- A batch processing log (auto-cleared after 7 days)
- Cached copies of incoming and processed images (auto-cleared after 24 hours)

All of the above lives in the app's private storage and is excluded from
Google's Drive backup with the exception of your default preset, which is
included so you don't have to reconfigure ImageShare on a new device.

## Permissions we request
- **Notifications** (Android 13+) — to show batch progress when you background the app.
- **Foreground service** (Android 14+) — to keep batch processing running when you switch away from the app.

We do NOT request photo, location, camera, microphone, or contacts permissions.

## EXIF metadata
By default, ImageShare strips ALL metadata from processed images, including
GPS coordinates, camera serial numbers, and timestamps. You can opt into a
"Preserve safe metadata" mode that keeps only date/time and color information;
camera identifiers and GPS are not included in that safe subset. A separate
"Preserve all" option is available when you explicitly choose to retain other
metadata; orientation is still normalized after processing.

## Children
ImageShare does not target users under 13 and does not knowingly collect any
information from anyone — children or otherwise.

## Changes to this policy
We may update this policy when the app's behavior changes. The "Last updated"
date at the top of this page reflects the most recent revision. We do not
notify users of changes through the app.

## Contact
If you have questions about this policy, file an issue on the project's
GitHub repository: https://github.com/adsamcik/ImageShare
