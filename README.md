# QobuzDLX for Android

An Android app for downloading music from Qobuz. It's a port of the
[QobuzDownloaderX](https://github.com/ImAiiR/QobuzDownloaderX) desktop app, which is C# and
still maintained.

## Why it exists

The old mobile app, [JemPH/QobuzDownloaderX-Mobile](https://github.com/JemPH/QobuzDownloaderX-Mobile),
is archived and its repo never had any source in it. Just a README, two images, and prebuilt APKs
on the releases page. So there's nothing to patch. If it breaks, it stays broken.

That's why this exists. I ported the working desktop app's logic to Kotlin so there's something
I can actually fix.

What came from the desktop app and its `Qo(penAPI)` library:

| Area | Source |
|---|---|
| API endpoints, auth | `Qo(penAPI).cs` |
| `app_id` / `app_secret` discovery | `Service.GetAppID`, `Service.GetAppSecret` |
| `track/getFileUrl` signing | `Service.TrackGetFileUrl` |
| Quality presets (`format_id`) | `qbdlxForm` quality buttons |
| Naming templates | `Helpers/RenameTemplates.cs` |
| Tag fields | `Helpers/TagFile.cs` |
| Download strategy | `Helpers/Download/DownloadFile.cs` |
| Pagination | `Helpers/GetInfo.cs` |

## What works

Sign in with an email and password or an auth token, search albums/tracks/artists/playlists, open
an album or playlist, play or download it. Downloads go up to 24-bit FLAC if your account allows
it, with cover art, tags and lyrics written into the files. There's a background player with lock
screen controls, synced lyrics, four colour themes, and a corner rounding slider.

Tested by me on a Pixel 8 running Android 17. 162 unit tests and 11 on-device tests.

I have no Qobuz account, so I've never run an authenticated download or heard the player play
anything. Both are built and tested as far as I can without one. The parts I could verify on-device
are verified; the rest is on you to try.

## Things that bit me, in case you hit them too

Most of these pass on the JVM and fail on a phone, which is how a broken build looks fine. That's
why the on-device test suite exists.

**JAudioTagger picks its reader from the file extension.** The downloader streams to
`qbdlx_<id>.part` and the tagger's working copy was `qbdlx_<id>.tagged`, so JAudioTagger threw
`CannotReadException: No Reader associated with this extension: tagged` and skipped tagging. Every
download saved fine, just with no tags. The working copy now gets the real extension, sniffed from
the magic bytes.

**FLAC cover art can't use JAudioTagger on Android.** `FlacTag.createField` decodes the image
through `javax.imageio.ImageIO` and `java.awt.image.BufferedImage`, and Android has neither. It
failed with `NoClassDefFoundError: Failed resolution of: Ljavax/imageio/ImageIO`. `FlacPicture`
writes the FLAC PICTURE block directly instead, no AWT involved. MP3 still goes through
JAudioTagger since its ID3 path doesn't need AWT.

**Tagging used to be able to lose a download.** It ran before the file was published, so any
tagger exception meant you got nothing at all. Now it runs on a copy and is best-effort: if it
fails, or the file shrinks by more than half, the untouched original is published and you get a
warning.

**Artist names come from a role string, not a name field.** Qobuz sends credits like
`"Radiohead, MainArtist - Nigel Godrich, Producer - ..."`. Splitting that on commas puts producers
and engineers in the ARTIST tag. `PerformersParser` is a port of the desktop app's version and
only keeps entries whose roles are main or featured artist.

**Two download workers can grab the same track.** `DownloadQueue.claimNext()` marks an item started
under one lock, so the read and the claim can't interleave.

**A slash in metadata creates directories.** Templates are sanitised before being split on `/`, so
a track actually titled `Bad/Name` can't add a folder level.

## Where the credentials come from

Qobuz doesn't publish API credentials. The desktop app scrapes them from the web player and so does
this: fetch `https://play.qobuz.com/login`, find `/resources/<version>/bundle.js`, read the pair
out of it, then log in with `user/login` using an email and password or an existing token.

The app secret is the fiddly part. The older method, from `Qo(penAPI)`, rebuilds it from the
bundle's timezone table: concatenate `seed + info + extras`, drop the last 44 characters, base64
decode. As of bundle 8.x that's outdated. The bundle now has the pair sitting in plaintext:

```js
production:{api:{appId:"<9-digit id>",appSecret:"<32 hex chars>",…
```

`QobuzCredentials` uses that and only falls back to the timezone trick if it's missing. Worth
knowing because the two methods currently return different strings.

If discovery fails, the login screen's Advanced section takes a manually entered pair.

The extracted pair is a live credential for Qobuz's own web player. It's read on your device at
runtime and isn't in this repo. Don't paste your own into a README, a test, or an issue.

`scripts/check-secrets.ps1` checks tracked files for tokens, keys and literal `app_id`/`app_secret`
values, and exits non-zero if it finds any. Worth using as a pre-commit hook:

```bash
cp scripts/check-secrets.ps1 .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
```

```powershell
pwsh -File scripts/check-secrets.ps1
```

## Building

JDK 17 and the Android SDK (platform 35, build-tools 35.0.0).

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

### Signing

It reads `keystore/local.keystore` if it's there, otherwise it falls back to the standard Android
debug key. To make your own:

```bash
keytool -genkeypair -v -keystore keystore/local.keystore -storetype PKCS12 \
  -storepass android -keypass android -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Your Name, O=Your Org, C=US"
```

The `QBdlxKeyStorePassword`, `QBdlxKeyAlias` and `QBdlxKeyPassword` environment variables override
the defaults.

The keystore is git-ignored for a reason. A published keystore with a known password means anyone
can sign an update for this application id. If you fork this, use your own key.

### Tests

```bash
./gradlew testDebugUnitTest           # unit tests
./gradlew connectedDebugAndroidTest   # on-device tests, needs a device
```

The on-device suite is the one that matters for tagger changes, since that's where the JVM tests
lie to you. It reuses the fixtures in `src/test/resources` as assets.

The fixtures are generated rather than downloaded, see `tools/gen_flac.py` and `tools/gen_jpeg.py`.
The JPEG is a real baseline JPEG, not a stub, because JAudioTagger rejects anything it can't decode
into a `BufferedImage`.

### Testing on a phone

Wireless ADB, no IP to remember:

```powershell
pwsh -File scripts/connect-phone.ps1 -Setup          # plug the cable in once
pwsh -File scripts/connect-phone.ps1 -RegisterTask   # then it reconnects by itself
```

It finds the phone over mDNS so a new DHCP lease doesn't matter, and falls back to the last known
address or USB. The scheduled task reconnects at logon and every few minutes.

One caveat: `adb tcpip 5555` doesn't survive a phone reboot, so after a restart the phone is
USB-only until you run `-Setup` again. Enabling Developer options, Wireless debugging on the phone
does survive reboots if you'd rather avoid that.

```powershell
$env:ANDROID_SERIAL = "192.168.4.57:5555"
./gradlew connectedDebugAndroidTest
```

## Trying it with your account

1. Install the APK.
2. Sign in with your Qobuz email and password, or tap "Use an auth token instead" and paste a token.
3. Search for an album, open it, tap "Download album".
4. Progress shows in the Downloads tab and in a notification.

Qobuz tokens are 64 characters. A truncated or expired one comes back as `HTTP 401 Not authorised`.

Downloads go to `Download/<Artist>/<Album>/` by default through MediaStore, so no storage
permission is needed. Settings → Download folder lets you pick somewhere else, including an SD card.

## Layout

```
api/          QobuzCredentials  bundle scraping, app_id/app_secret
              QobuzClient       endpoints, auth, getFileUrl signing, pagination
              Models            kotlinx.serialization models
data/         SessionStore      saved login
download/     DownloadQueue     in-memory queue as a StateFlow
              DownloadEngine    resolve, stream to temp, tag, publish
              DownloadService   foreground service and notification
              StorageManager    MediaStore / SAF / legacy paths
              MetadataTagger    tags and cover art
              FlacPicture       FLAC PICTURE block, written by hand
              PerformersParser  credits string to artist names
              RenameTemplates   %placeholder% naming
playback/     PlayerController  Media3 controller, queue, state
              PlaybackService   MediaSessionService for background audio
settings/     SettingsStore     quality, templates, tags, theme
ui/           Compose screens: login, search, album, artist, playlist, downloads, settings
ui/theme/     presets, shapes, artwork-derived accent
```

A few decisions worth explaining:

Downloads never write straight to a public folder. They stream into the app cache, get tagged
there, and are published once they're good. A cancelled or failed download can't leave a
half-written file in your library.

If a track isn't licensed at the quality you picked, the downloader walks down
`27 → 7 → 6 → 5` instead of failing.

Cover art gets the largest rendition available. Qobuz serves each cover at several sizes and the
embedded copy is permanent, so it tries them largest first and caches which one worked for the
rest of the album. Responses are checked for being real image data so a placeholder can't end up
as your artwork.

Dynamic colour is off by default. It replaces the palette with wallpaper colours, which on Android
12+ makes the app look like it has no design at all. It's there under Settings → Appearance if you
want it.

Accent colours get checked against WCAG contrast and only adjusted if they fall below 4.5:1, so
the brand purple and artwork colours stay as intended.

## Not done yet

- Favourite albums and artists. Only favourite tracks.
- Playback quality uses the download quality setting rather than having its own.
- No gapless playback or queue reordering.

## Lyrics

Lyrics come from [LRCLIB](https://lrclib.net), a free community database with no account and no
API key. Qobuz does not expose lyrics anywhere in its own API, so there was no first-party source
to use.

The lookup is deliberately strict about which record it accepts. Timed lyrics are worth much more
than a plain block, so a timed match wins, but a candidate whose length is more than a few seconds
off is rejected outright even when it is the only one. The search is already scoped by artist and
title, so a large length difference means a live take, an extended mix or a different song with the
same name, and lyrics for the wrong recording are worse than no lyrics.

If the service has nothing, is rate limiting, or is simply down, the app says so in the player and
writes your download without lyrics. A lyrics lookup can never cost you a file.

By default the timing goes into the file itself: `LYRICS` and `SYNCEDLYRICS` comments for FLAC, a
`USLT` frame plus a `SYLT` frame for MP3. There is also an option to write a `.lrc` next to each
track for players that look for one.

## Legal

Not affiliated with, endorsed by, or approved by Qobuz. The Qobuz name and brand belong to their
respective owner. You're responsible for following the
[Qobuz API Terms of Use](http://static.qobuz.com/apps/api/QobuzAPI-TermsofUse.pdf) and your local
law, and for using an account you're entitled to use.

Read [LICENSING.md](LICENSING.md) before you publish or distribute this. None of the upstream
projects declare a licence, which limits redistribution even though no upstream source was copied.
