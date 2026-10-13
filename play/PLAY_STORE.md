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
| In-app purchases | Yes (optional tips) |

## App content answers

- **Data safety:** No data collected, no data shared. (Data is encrypted in transit: yes — all HTTPS.)
- **Content rating:** IARC questionnaire → category "Music/streaming"; no user interaction, no UGC, no purchases.
  Note: the station may play songs with explicit lyrics occasionally — answer honestly if asked about crude language.
- **Target audience:** 18+ (avoids Families policy requirements).
- **Foreground service declaration:** type `mediaPlayback` — "Plays a user-started internet radio stream in the
  background with a media notification." Attach a short screen recording of starting playback and locking the phone.
- **News app / health / financial / government:** No.

## Tip jar (in-app products)

1. Play Console → **Monetize → Payments profile**: set up the merchant account (free).
2. Upload an AAB that includes billing (v1.1.0+) to any track first — products can't be created before that.
3. **Monetize → Products → In-app products** → create three **one-time** products, then *Activate* them:

| Product ID | Name | Suggested price |
| --- | --- | --- |
| `tip_small` | Small tip | ₹49 / $0.99 |
| `tip_medium` | Big tip | ₹149 / $2.99 |
| `tip_large` | Huge tip | ₹399 / $6.99 |

The app sorts them by price and shows ☕ 🍕 🎧. If the products don't exist yet (or there's no Play Store, e.g. the
GitHub APK on a de-Googled phone), the tip buttons simply don't appear.

To test without being charged: Play Console → **Settings → License testing** → add your Gmail, then install from
the internal/closed testing track.

## Signing

Use Play App Signing. When asked, choose **"Use a key exported from Android Studio / an existing key"** and upload
`~/.android/paradise-flac-release.jks` via Google's PEPK tool if you want Play builds and GitHub-release APKs to
update each other. Otherwise let Google generate the app signing key and keep this keystore as the **upload key**
(sideloaded installs then need uninstalling once before installing from Play).

## New personal developer accounts

Google requires a **closed test with at least 12 testers opted in for 14 continuous days** before you can apply for
production access.
