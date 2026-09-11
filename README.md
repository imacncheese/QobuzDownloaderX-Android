# QobuzDLX for Android

An Android client for downloading music from Qobuz, ported from the **QobuzDownloaderX (QBDLX)**
desktop application by [ImAiiR](https://github.com/ImAiiR/QobuzDownloaderX).

## Why this exists

[`JemPH/QobuzDownloaderX-Mobile`](https://github.com/JemPH/QobuzDownloaderX-Mobile) is **closed
source and archived**. Its repository contains only a `README.md` and two PNG files — there is no
application code in it, and never was; the project shipped prebuilt APKs as release assets and
nothing else. The last release is `0.5.0` (June 2025).

That means the older app cannot be "fixed": there is no source to patch, and the APKs are stale.
This project is a clean reimplementation on top of the still-maintained C# desktop app, whose API
logic is the only working reference.

**What was ported from the desktop app:**

| Area | Source in `QobuzDownloaderX` |
|---|---|
| API endpoints, auth flow | `Qo(penAPI).cs` (`ImAiiR/Qo-penAPI-`) |
| `app_id` / `app_secret` discovery | `Service.GetAppID` / `Service.GetAppSecret` |
| `track/getFileUrl` signing | `Service.TrackGetFileUrl` |
| Quality presets (`format_id`) | `qbdlxForm.flacHighButton2_CheckedChanged` et al. |
| Naming templates | `Helpers/RenameTemplates.cs` |
| Tag field mapping | `Helpers/TagFile.cs` |
| Download strategy | `Helpers/Download/DownloadFile.cs` |
| Pagination loops | `Helpers/GetInfo.cs` |

## Status

Verified working:

- Builds clean (debug + R8-minified release) with `assembleRelease`.
- Installs and launches on a **Pixel 8, Android 17 (API 37), arm64-v8a** with no exceptions.
- Retrieves a live `app_id` + `app_secret` pair from the current Qobuz web-player bundle
  (`8.2.0-b034`) at startup.
- 59 JVM unit tests + 5 on-device instrumented tests, all passing. The on-device suite
  deliberately exercises the paths that behave differently on Android (see below), including
  tagging a file named the way the download engine actually names it.

**Not yet verified by the author:** an actual authenticated download, because that needs a real
Qobuz account. See *Testing* below.

## Android-specific traps this project works around

These are the reason the on-device test suite exists: each one passes on the JVM and fails on a
phone, which is exactly how a broken build can look healthy.

### 0. JAudioTagger dispatches readers on the file *extension*

This shipped broken in 1.0.0 and is the subtlest of the lot. The download engine streams audio to
`qbdlx_<id>.part`, and the tagger's working copy was `qbdlx_<id>.tagged`. JAudioTagger picks its
reader from the file name, so it threw:

```
CannotReadException: No Reader associated with this extension: tagged
```

Tagging was skipped, the warning was swallowed into a hint, and the **untagged** file was published
correctly — so downloads "worked" but had no metadata. `MetadataTagger.withAudioExtension` now
sniffs the container from magic bytes (`fLaC`, `ID3`/frame-sync, `ftyp`, `OggS`, `RIFF`) and gives
the working copy the real extension, and `Result.file` reports the path actually written so the
engine publishes the tagged file rather than guessing its name.

*Symptom if this regresses:* files download fine but every one is untagged.

### 1. FLAC cover art cannot use JAudioTagger on Android

`FlacTag.createField` decodes artwork through `javax.imageio.ImageIO` and
`java.awt.image.BufferedImage`. **Android ships neither.** On a device the call fails with:

```
NoClassDefFoundError: Failed resolution of: Ljavax/imageio/ImageIO;
```

`FlacPicture` therefore writes the FLAC `PICTURE` metadata block directly, which needs no AWT. It
rebuilds the metadata chain, removes any previous picture block, appends the new one as the single
final block, and re-reads the chain to confirm exactly one block carries the last-metadata flag.
MP3 still goes through JAudioTagger, whose ID3 artwork path does not touch AWT.

*Symptom if this regresses:* downloads complete but every file is missing cover art.

### 2. Tagging must never be able to lose a download

Tagging runs on a working copy and is treated as best-effort. If it fails or damages the file, the
**untouched original is published instead** and the user sees a warning. The output is also
rejected if it shrinks by more than half, which catches a tagger that truncated the stream.

*Symptom if this regresses:* the file downloads, then vanishes — nothing is saved at all.

### 5. Artist names must be parsed from roles, not joined

Qobuz reports credits in a role-annotated `performers` string:

```
"Radiohead, MainArtist - Nigel Godrich, Producer - Thom Yorke, AssociatedPerformer, Vocals"
```

Putting that string (or a naive comma-split of it) into the ARTIST tag is wrong — it drags
producers, engineers and mixers into the artist field. `PerformersParser` ports the desktop app's
`PerformersParser.cs` and `InvolvedPersonRoleMapping.cs`: only entries whose roles mark them as a
main artist or a featured artist are used, the two groups are merged, and featured artists are
dropped when the track title already advertises the feature.

Album artists come from `album.artists[].roles` (normally `["main-artist"]`), falling back to the
plain `album.artist` field. Some track titles genuinely wrap mid-name; a single newline is treated
as a line wrap while a blank line separates entries.

*Symptom if this regresses:* artist tags contain engineer/producer names or the whole credits blob.

### 6. Concurrent workers must claim work atomically

`DownloadQueue.claimNext()` takes a queued item and marks it started under a single lock. A plain
read-then-start lets two workers pick the same track and download it twice.

*Symptom if this regresses:* duplicated downloads at `concurrency > 1`.

### 4. A `/` in metadata must not create directories

Naming templates are sanitised *before* being split on `/`, so a track genuinely titled
`Bad/Name` cannot inject extra directory levels into the output path.


## Credentials

Qobuz does not publish API credentials. The desktop app scrapes them from the Qobuz web player,
and so does this one:

1. Fetch `https://play.qobuz.com/login`, locate `/resources/<version>/bundle.js`.
2. Read the credentials out of the bundle.
3. Log in (`user/login`) with e-mail + password, or an existing `user_auth_token`.

### A note on the `app_secret`

The historical technique (used by `Qo(penAPI)`) reconstructs the secret from the bundle's timezone
table: concatenate `seed + info + extras`, drop the last 44 characters, base64-decode.

**As of bundle 8.x that technique is outdated.** The bundle now ships the pair in the clear:

```js
production:{api:{appId:"<9-digit id>",appSecret:"<32 hex chars>",…
```

`QobuzCredentials` prefers that direct value and only falls back to the timezone de-obfuscation if
it is absent. This matters because the two techniques currently return **different** strings, and
the plaintext one is the pair Qobuz actually publishes.

If discovery ever fails, the login screen's **Advanced** section accepts a manually supplied
`app_id` / `app_secret` pair.

### Credential guard

`scripts/check-secrets.ps1` scans every tracked file for GitHub tokens, private keys and literal
`app_id` / `app_secret` values, and exits non-zero if it finds any. Install it as a pre-commit hook
so a credential cannot be committed by accident:

```bash
cp scripts/check-secrets.ps1 .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
```

```powershell
pwsh -File scripts/check-secrets.ps1
```

## Building

Requires JDK 17 and the Android SDK (platform 35, build-tools 35.0.0).

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (~1.9 MB).

### Signing

The build reads a local keystore from `keystore/local.keystore` if one exists, otherwise it falls
back to the standard Android debug key. Generate your own:

```bash
keytool -genkeypair -v -keystore keystore/local.keystore -storetype PKCS12 \
  -storepass android -keypass android -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Your Name, O=Your Org, C=US"
```

Override the defaults with the `QBdlxKeyStorePassword`, `QBdlxKeyAlias` and `QBdlxKeyPassword`
environment variables.

**The keystore is git-ignored on purpose.** A published keystore with a known password lets anyone
sign an update for this application id. If you fork this and publish APKs, use your own key and
keep it out of the repository.

Run the tests:

```bash
./gradlew testDebugUnitTest           # 21 JVM tests
./gradlew connectedDebugAndroidTest   # 3 on-device tests (needs a device/emulator)
```

The instrumented suite is the important one for tagger changes: it catches the
Android-only failures described above. It needs the `androidTest` source set, which
reuses the fixtures in `src/test/resources` as assets.

Test fixtures (`sample.flac`, `cover.jpg`, `sample.wav`) are generated, not downloaded —
see `tools/gen_flac.py` and `tools/gen_jpeg.py`. The JPEG is a real baseline JPEG
rather than a stub, because JAudioTagger rejects anything it cannot decode into a
`BufferedImage`.

## Testing with a real account

1. Install the APK.
2. Sign in with your Qobuz e-mail and password, or switch to **Use an auth token instead** and paste
   a token.
3. Search for an album, open it, and press **Download album**.
4. Watch progress in the **Downloads** tab and in the notification.

A `user_auth_token` must be the full token. Qobuz tokens currently have **64** characters;
a truncated or stale token is rejected with `HTTP 401 Not authorised`.

Downloads land in `Download/<Artist>/<Album>/` by default (via MediaStore, so no storage
permission is required). Use **Settings → Download folder** to pick any other folder, including on
an SD card, through the system folder picker.

## Architecture

```
api/          QobuzCredentials  bundle scraping → app_id/app_secret
              QobuzClient       endpoints, auth, getFileUrl signing, pagination
              Models            kotlinx.serialization models of the API payloads
data/         SessionStore      persisted auth session
download/     DownloadQueue     in-memory queue exposed as a StateFlow
              DownloadEngine    resolve → stream to temp → tag → publish
              DownloadService   foreground service + notification
              StorageManager    MediaStore / SAF / legacy destinations
              MetadataTagger    JAudioTagger tag + cover-art writing
              RenameTemplates   %placeholder% naming
settings/     SettingsStore     quality, templates, tag options
ui/           Compose screens: login, search, album, downloads, settings
```

### Design notes

- **Downloads never write directly to a public path.** They stream into the app cache, get tagged
  there, and are only then published to the destination, so a failed or cancelled download can
  never leave a half-written or untagged file in your library.
- **Quality degrades automatically.** If a track is not licensed at Hi-Res, the engine retries
  down the chain `27 → 7 → 6 → 5` rather than failing.
- **Embedded artwork uses the largest available rendition.** Qobuz publishes each cover at several
  sizes (`max`, `org`, `2048`, `1400`, `600`, …). The cover written into a file is permanent, so
  the candidate list is ordered largest-first and probed until one returns real image bytes.
  Change the size under *Settings → Embedded artwork size*; the desktop app offers the same list.
- **Truncated downloads are rejected.** The engine compares bytes written against
  `Content-Length` and fails loudly instead of saving a corrupt file.
- **Tags with no `FieldKey` in JAudioTagger** (copyright, full release date, ReplayGain) are written
  through format-native field ids. Unsupported formats skip them silently rather than failing.
- **Naming templates are sanitised before path splitting**, so a track genuinely titled
  `Bad/Name` cannot inject extra directory levels.

## Known limitations

- Favourite tracks only; favourite albums/artists are not surfaced in the UI yet.
- No lyrics fetching (the mobile 0.5.0 build had an LRCLib plugin; that is not ported).
- No gapless/offline playback — this is a downloader, not a player.
- Genre/artist browse pages beyond search-then-album are not implemented.

## Releasing

### Manual

`scripts/publish.ps1` (Windows) or `scripts/publish.sh` create the GitHub
repository, push `main`, tag the release and upload the APK as a release asset.
They use `$GITHUB_TOKEN` / `$GITHUB_TOKEN` if set, otherwise Git Credential
Manager.

```powershell
pwsh -File scripts/publish.ps1
```

```bash
./scripts/publish.sh --tag v1.0.5
```

### Automatic (tag-triggered CI)

`.github/workflows/release.yml` builds and publishes an APK whenever a `v*` tag is
pushed. It is **inert until you add one secret**, so it cannot publish anything by
accident.

1. Export your keystore as base64:

   ```bash
   base64 -w0 keystore/local.keystore > keystore.b64
   ```

2. Add repository secrets under *Settings → Secrets and variables → Actions*:
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (the last
   three can be omitted if you use the `android` / `androiddebugkey` / `android`
   defaults).

3. Push a tag:

   ```bash
   git tag -a v1.0.5 -m "v1.0.5" && git push origin v1.0.5
   ```

> **Signing keys and non-interactive use.** The keystore in this repo is
> git-ignored, and the workflow decodes it from a secret at build time. If you
> ever change the key, previously installed builds cannot be updated in place —
> Android rejects an update whose signing certificate differs, and the user has
> to uninstall first.

## Theming

Settings → **Appearance** controls the whole look:

| Control | Options |
|---|---|
| Theme | Qobuz, AMOLED Black, Midnight, Daylight, Match wallpaper (Android 12+) |
| Light or dark | Follow system, Dark, Light |
| Corner rounding | Square → pill slider, applied app-wide |
| Tint from cover art | Derives the accent from the album you open |

Two deliberate choices worth knowing:

- **Dynamic colour (Material You) is opt-in, not the default.** It replaces the palette with
  wallpaper colours, which made the app look unstyled on Android 12+. Select *Match wallpaper* to use it.
- **Accents are contrast-checked.** A colour is only adjusted when it falls below a 4.5:1 WCAG
  contrast target, so the brand purple and artwork colours stay true rather than being washed out.

A preset declares only a few anchor colours; `ThemePalette` derives the full Material 3 role set from
them, so a new preset cannot leave roles undefined or light/dark out of step.

## Legal

Not affiliated with, endorsed by, or approved by Qobuz. The Qobuz name and brand are trademarks of
their respective owner. You are responsible for complying with the
[Qobuz API Terms of Use](http://static.qobuz.com/apps/api/QobuzAPI-TermsofUse.pdf) and your local
law. Use with an account you are entitled to use.

**Read [`LICENSING.md`](LICENSING.md) before publishing or distributing this project.** None of the
upstream projects it was ported from declare a license, which limits redistribution even though no
upstream source code was copied.
