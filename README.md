<img width="2750" height="1536" alt="Gemini_Generated_Image_aax1qlaax1qlaax1 (1)" src="https://github.com/user-attachments/assets/97838a5c-393b-4b32-969e-7a240ab9cd62" />

# Water Polo Jury

Run a water polo jury table with cheap Android devices linked over Bluetooth:
one device controls the match, three tablets act as the scoreboard and the two
shot clocks. No internet, no extra hardware.

```
   [ shot clock ]        [ main scoreboard ]        [ shot clock ]
       tablet           score · clock · period           tablet
            \                     |                     /
             \_______ Bluetooth (RFCOMM) ______________/
                                  |
                        [ jury table controller ]
                           phone or tablet
```

One app, three roles — pick the role when the app starts on each device:

| Role | Shows |
|------|-------|
| **Jury table (controller)** | Clock control, event entry, match log |
| **Main scoreboard** | Score, game clock, period, timeouts, running exclusions, timeout/interval countdown |
| **Shot clock** | Possession clock (red in the last 5 s), small game clock and score |

## What the controller does

- **One big START/STOP** drives game clock, shot clock and exclusion timers together.
- **Shot clock reset** buttons for full (28 s) and reduced (18 s) possession.
- **Edit any clock at any time, running or stopped**: ±1 s buttons, or tap a clock to type a value.
- **Log events** with team (white/blue), cap number, period and clock time:
  goal, exclusion, penalty foul, misconduct (exclusion with substitution),
  brutality, timeout, yellow card, red card.
- **Personal fouls** are counted per player; the cap picker shows the count and
  marks players who are out (3 fouls, misconduct, brutality, red card).
- **Exclusion timers** count down on game time; a goal clears them (brutality's
  4 minutes stays); tap one to clear it manually.
- **Horn** on every device when the shot clock or period expires, and when a timeout ends.
- **Period handling**: interval / half-time countdown starts automatically, then
  one tap sets up the next period; after the last period you can go to a penalty shootout.
- **Every log entry is editable** (type, team, cap, period, time, note) or deletable; score and foul counts follow.
- **Export** the match log as text (CSV-style) through any app.
- The match is saved on the controller after every action, so a crash or restart does not lose it.

Automatic clock behaviour worth knowing:

- Goal: clock stops, shot clock resets to full.
- Exclusion / misconduct: shot clock is raised to the reduced time if less was left. Press the full reset if possession changed instead.
- Timeout: clock stops and the timeout countdown starts.
- Shot clock reaching 0: horn and the clock stops.

## Rule defaults

Everything is adjustable under **Menu → Match settings**. Defaults: 4 × 8 min,
28 s / 18 s possession, 18 s exclusion, 4 min brutality, 2 timeouts of 60 s per
team, 2 min intervals, 3 min half time, 3 personal fouls, 14 caps.

Check these against the rules of your own competition (World Aquatics,
European Aquatics, national federation and age-group rules differ) before a
match. This app is a jury aid, not an official rules implementation.

## Setting up the devices

1. Install the app on all four devices.
2. On the controller, pair each tablet in **Android Settings → Bluetooth** (once).
3. On each tablet open the app and choose **Main scoreboard** or **Shot clock**.
   The tablet remembers its role and shows "Waiting for the jury table…".
4. On the controller choose **Jury table**, tap **Displays** and tick the tablets.
   They connect within a few seconds and reconnect on their own after a drop.

Tips: keep tablets on chargers, turn media volume up for the horn, and disable
battery optimisation / screen lock on the display tablets. A red dot in a
display's corner means it has lost the controller; its clocks keep counting
locally until the link returns. Classic Bluetooth reaches roughly 10 m poolside;
keep the controller in line of sight of the tablets.

## Building

Requires JDK 17 and the Android SDK (or just open the folder in Android Studio).

```bash
./gradlew testDebugUnitTest assembleDebug   # APK in app/build/outputs/apk/debug/
```

Every push is built by GitHub Actions; the debug APK is attached to the run as
an artifact.

- `core/` — match rules and clocks, pure Kotlin with unit tests
- `bt/` — Bluetooth RFCOMM transport (newline-delimited JSON snapshots)
- `ui/` — Jetpack Compose screens

## Releasing (Google Play)

Release builds are signed with your own upload key, which is never committed.

1. Create the key once and keep it safe (losing it means asking Google to reset it):
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Local release build: create `keystore.properties` in the repo root (git-ignored):
   ```properties
   RELEASE_STORE_FILE=release.jks
   RELEASE_STORE_PASSWORD=...
   RELEASE_KEY_ALIAS=upload
   RELEASE_KEY_PASSWORD=...
   ```
   then `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
3. CI release build: add repository secrets `RELEASE_KEYSTORE_BASE64`
   (`base64 -i release.jks`), `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
   `RELEASE_KEY_PASSWORD`. Every build then also produces a signed AAB and APK
   (version code = run number), and pushing a tag like `v0.1.0` publishes them
   as a GitHub release.
4. Upload the AAB in the Play Console. Privacy policy: [docs/PRIVACY.md](docs/PRIVACY.md).

## License

MIT — see [LICENSE](LICENSE).
