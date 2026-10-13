# Play Store submission checklist

Everything in this folder is ready to paste/upload into Play Console.

| Play Console field | Use |
| --- | --- |
| App bundle | `ParadiseFLAC.aab` from the latest GitHub release |
| App name | `listing/title.txt` |
| Short description | `listing/short_description.txt` |
| Full description | `listing/full_description.txt` |
| Release notes | `listing/release_notes.txt` |
| App icon (512×512) | `icon-512.png` |
| Feature graphic (1024×500) | `feature-graphic.png` |
| Phone screenshots | `screenshots/` (better: retake on your own phone) |
| Privacy policy URL | https://deveshb-1.github.io/paradise-flac/privacy.html |
| Category | Music & Audio |
| Contains ads | No |

## App content answers

- **Data safety:** No data collected, no data shared. (Data is encrypted in transit: yes — all HTTPS.)
- **Content rating:** IARC questionnaire → category "Music/streaming"; no user interaction, no UGC, no purchases.
  Note: the station may play songs with explicit lyrics occasionally — answer honestly if asked about crude language.
- **Target audience:** 18+ (avoids Families policy requirements).
- **Foreground service declaration:** type `mediaPlayback` — "Plays a user-started internet radio stream in the
  background with a media notification." Attach a short screen recording of starting playback and locking the phone.
- **News app / health / financial / government:** No.

## Signing

Use Play App Signing. When asked, choose **"Use a key exported from Android Studio / an existing key"** and upload
`~/.android/paradise-flac-release.jks` via Google's PEPK tool if you want Play builds and GitHub-release APKs to
update each other. Otherwise let Google generate the app signing key and keep this keystore as the **upload key**
(sideloaded installs then need uninstalling once before installing from Play).

## New personal developer accounts

Google requires a **closed test with at least 12 testers opted in for 14 continuous days** before you can apply for
production access.
