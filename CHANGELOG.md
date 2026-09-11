# Changelog

## 1.0.6

### Changed

- **Embedded album art now uses the largest available rendition.** The previous code tried `_1400`
  then `_600`, so covers were capped well below what Qobuz publishes. Candidate URLs are now
  ordered largest-first (`max`, `org`, `2048`, `1400`, `1000`, `600`, …) and probed until one
  returns real image bytes, matching the size list the desktop app offers.
- Added a **Settings → Embedded artwork size** control, defaulting to Maximum available.

### Fixed

- **A placeholder response could be embedded as album art.** A rendition that does not exist does
  not always answer with a clean 404, so responses are now validated as real JPEG/PNG data of a
  plausible size before being written into a file.

## 1.0.5

### Security

- **Removed a live Qobuz `app_id`/`app_secret` pair from the documentation.** The README quoted a
  real credential extracted from the web-player bundle as an illustrative example. It is now a
  placeholder, and the README states plainly that the extracted pair is never committed.
- **Removed a real credential from the test suite.** The `track/getFileUrl` signature test used a
  genuine `app_secret` as a fixture; it now uses a zeroed placeholder. The signature algorithm is
  independent of the input values, so the test still guards against reordering the concatenation.
- **Removed the personal account name from the publish scripts**, which is now resolved from the
  authenticated GitHub user.
- Verified that no `app_id`, `app_secret`, token, private key or owner name remains anywhere in the
  repository.

## 1.0.4

### Fixed

- **Artist tags were derived from raw credits.** Qobuz sends a role-annotated performers string
  (`"Radiohead, MainArtist - Nigel Godrich, Producer - ..."`). The previous shortcut could put
  producers, engineers and mixers into the ARTIST tag. Ported the desktop app's `PerformersParser`
  and role mapping; only main/featured artist roles are used now.
- **Album artists** are now selected from `album.artists[].roles` instead of joining every credited
  name. The `roles` field was missing from the API model entirely.
- Featured artists are dropped when the track title already advertises the feature, avoiding
  duplicated names.
- A wrapped line inside a performers value is treated as a line wrap (single newline), while a blank
  line separates entries.

## 1.0.3

### Added

- **Download album directly from search results.** Album rows had no download action at all; the
  button only existed one level deeper on the album screen. Search results now show a per-album
  download button that fetches the full track list and queues it.

### Fixed

- The album screen's download button was disabled with no explanation when its track list had not
  loaded. It now shows progress while fetching and reports failures and empty releases.

## 1.0.2

### Fixed

- **Tagging was silently skipped for every download.** The download engine writes audio to
  `qbdlx_<id>.part` and the tagger's working copy was `qbdlx_<id>.tagged`. JAudioTagger dispatches
  its reader on the file extension, so it threw `CannotReadException: No Reader associated with this
  extension: tagged`, and the untagged file was published. The working copy now carries the real
  audio extension, detected from magic bytes.
- **Cover art was fetched from a single URL** and gave up after one failure (`cover art: 0 bytes`).
  It now walks every known rendition and stops at the first that returns bytes.
- **Album folders were named `[FLAC 24-0.0kHz]`** because the template used only the album's sample
  rate, which Qobuz frequently omits or sends as `0`. The track value now wins, with the album as
  fallback, and a missing value produces nothing.
- `Cover.jpg` writes raced and a second one threw; they now overwrite cleanly and never fail a
  download.

## 1.0.1

### Fixed

- **A tagging failure could discard a completed download.** Tagging ran before the file was
  published, so any tagger exception meant the user got nothing. Tagging is now best-effort and runs
  on a working copy: if it fails or damages the file, the untouched original is published with a
  warning.
- **FLAC cover art could never work on Android.** `FlacTag.createField` decodes artwork through
  `javax.imageio.ImageIO` and `java.awt.image.BufferedImage`, neither of which exists on Android —
  it failed with `NoClassDefFoundError: Failed resolution of: Ljavax/imageio/ImageIO`. Cover art is
  now written as a native FLAC `PICTURE` metadata block.
- **Concurrent workers could download the same track twice.** Queue claiming is now atomic.
- A track titled `Bad/Name` could inject extra directory levels into the output path; templates are
  sanitised before being split.

## 1.0.0

Initial release: Kotlin/Compose port of the QBDLX desktop application.

- Email/password and auth-token sign-in.
- Live `app_id` / `app_secret` discovery from the Qobuz web-player bundle.
- Search across albums, tracks and artists.
- Album and single-track downloads at MP3 320 / FLAC 16-bit / FLAC 24-bit ≤96 kHz / Hi-Res.
- Automatic quality fallback (`27 → 7 → 6 → 5`) when a release is not licensed at full quality.
- Metadata tagging with cover art, and `%placeholder%` file naming.
- Storage via MediaStore, the system folder picker, or legacy public storage.
- Foreground-service downloads with progress notification.
