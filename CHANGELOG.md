# Changelog

## 1.3.0

Fixed two things that were wired up but never called, which is why they looked
like features that didn't work.

- Tapping an album opened an empty screen. `openAlbum` existed but nothing ever
  called it; the `LaunchedEffect` that kicked off the fetch got lost when I
  rewrote navigation for the animated transitions. The id changed and the screen
  opened, but no request was ever made. Loaders now run off the navigation
  target, so the detail screens also survive process death and a restored back
  stack.
- Tapping a track did nothing. `TrackRow` only had a download button, no play
  action. It plays now, and queues the results you can see.

Also:

- Album pagination stopped after one page when Qobuz left out `total`. The loop
  condition was `all.size < total`, which is false straight away when `total` is
  missing. It follows the desktop app's handling now and is bounded by page count
  instead of offset.
- The album screen's catch-all "nothing here" message is gone. It now says
  whether the release couldn't load or genuinely has no tracks.
- Added an artist screen (their releases) and a playlist screen (tracks, play
  all, download).
- Detail navigation is one typed target instead of a bare album id. That typing
  is what showed two screens were reachable without their loaders hooked up.

## 1.2.0

- Added the player. Media3 ExoPlayer inside a `MediaSessionService`, so audio
  keeps going when the app is backgrounded or the screen locks, with lock screen
  and notification controls. Tapping a track plays it and queues the album. Mini
  player above the nav bar, full screen player with seek.
- Stream URLs are signed and expire, so they're resolved close to play time and
  a few tracks ahead. Resolution falls back through the same quality chain as
  downloads.
- Added a playlist search tab.

## 1.1.0

- Four themes: Qobuz, AMOLED Black, Midnight, Daylight, plus Match wallpaper.
  A preset only declares a few colours and the full Material 3 set is derived
  from them, so light and dark can't drift apart.
- Light/dark/system toggle, and a corner rounding slider applied everywhere
  through `LocalShapes`.
- Opening an album can tint the whole app from its cover art.
- Album screen redone with a blurred header and a bigger cover, plus animated
  transitions.
- Dynamic colour is no longer forced on. It was overriding the palette on
  Android 12+, which is why the app looked undesigned on newer phones.
- Accents are checked against WCAG contrast and only changed if they're under
  4.5:1. The brand purple is 3.66:1 on the dark background so it gets lifted;
  the dark mode primary is 9.3:1 and is left alone.

## 1.0.7

- Artwork was being probed per track. Resolving the largest rendition means
  trying candidates until one works, so a 20 track album could make about 200
  requests for one cover. The first track resolves it now and the rest reuse it.
  Failures aren't cached, so a flaky CDN response can't pin a smaller size.

## 1.0.6

- Embedded cover art uses the largest rendition Qobuz has. It was capped around
  `_1400` falling back to `_600`, and the fallback ran the wrong way, going down
  instead of up. Candidate URLs are now ordered largest first and probed until
  one returns real image bytes.
- A missing rendition doesn't always 404 cleanly, so responses are checked for
  being actual JPEG/PNG data of a plausible size. A placeholder can't get
  embedded as your artwork any more.

## 1.0.5

Security cleanup.

- Removed a live `app_id`/`app_secret` pair from the README. I'd used a real one
  as an example. It's a placeholder now.
- Removed a real credential from the test suite. The signature test used a
  genuine `app_secret` as a fixture; it's zeroed out now. The test still guards
  the concatenation order, which doesn't depend on the values.
- Removed my account name from the publish scripts. It's resolved from whoever
  is authenticated.
- Checked the whole repo for `app_id`, `app_secret`, tokens, keys and owner
  names.

## 1.0.2

- Tagging was being skipped on every download. The engine writes to
  `qbdlx_<id>.part` and the tagger's working copy was `qbdlx_<id>.tagged`.
  JAudioTagger picks its reader from the extension, so it threw
  `CannotReadException: No Reader associated with this extension: tagged` and
  published the untagged file. The working copy gets the real extension now,
  sniffed from the magic bytes.
- Cover art came from a single URL and gave up after one failure, logging
  `cover art: 0 bytes`. It walks the renditions now.
- Album folders came out as `[FLAC 24-0.0kHz]` because the template used only
  the album's sample rate, which Qobuz often omits or sends as `0`. The track
  value wins, with the album as fallback.
- Writing `Cover.jpg` twice raced and the second one threw. It overwrites
  cleanly and never fails the download.

## 1.0.1

- A tagging failure could throw away a finished download. Tagging ran before the
  file was published, so any tagger exception meant you got nothing. It runs on
  a copy and is best-effort now: if it fails or damages the file, the untouched
  original is published with a warning.
- FLAC cover art never worked on Android. `FlacTag.createField` decodes through
  `javax.imageio.ImageIO` and `java.awt.image.BufferedImage`, and Android has
  neither, so it failed with
  `NoClassDefFoundError: Failed resolution of: Ljavax/imageio/ImageIO`.
  `FlacPicture` writes the FLAC PICTURE block directly instead. MP3 still uses
  JAudioTagger, whose ID3 path doesn't need AWT.
- Two workers could claim the same track and download it twice. Queue claiming
  is atomic now.
- A track titled `Bad/Name` could add a folder level. Templates are sanitised
  before being split on `/`.

## 1.0.0

First release. Kotlin and Compose port of the QBDLX desktop app.

- Email/password and auth token login.
- Live `app_id`/`app_secret` discovery from the Qobuz web player bundle.
- Search albums, tracks and artists.
- Album and single track downloads at MP3 320, FLAC 16-bit, FLAC 24-bit up to
  96kHz, and Hi-Res.
- Quality falls back down `27 → 7 → 6 → 5` when a release isn't licensed at the
  quality you asked for.
- Tags and cover art written into the files, `%placeholder%` naming templates.
- Storage through MediaStore, the system folder picker, or legacy public paths.
- Foreground service downloads with a progress notification.
