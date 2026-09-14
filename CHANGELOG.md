# Changelog

## 1.9.1

Lyrics fixes, and playback now recovers when the service goes away.

### Lyrics

Two real faults, both found by checking what the app actually did rather than
what it was supposed to do.

- A failure from the lyrics service was cached like an answer. One bad moment,
  a 503 or a dropped connection, stuck to that track for the rest of the session
  and every later look at it said the lyrics were unavailable with no way to
  retry. Failures are no longer remembered.
- A failure on the exact-match endpoint aborted the whole lookup instead of
  falling through to the search endpoint. A 503 on one says nothing about the
  other. It now carries on, retries a server-side failure once, and makes one
  looser query before giving up, because the credited artist on a compilation or
  a feature is often not the one the track is filed under.

The message when there is nothing now names the source, "No lyrics for this
track on LRCLIB". An empty result is not a broken feature, it is a community
database that does not have that particular track, and the old wording made it
read like one.

I also tried adding a second lyrics source for tracks LRCLIB does not have.
Every free one I could find is unusable: one is gone, one rate limits the shared
instance, one now needs a key. None of it is in the app.

### Playback

A MediaController does not reconnect on its own, and the app never noticed the
playback service going away. It kept issuing commands to a dead controller,
which accepts them and applies them to its own copy of the playlist, so the app
happily showed a queue and a current track while the service held nothing and no
sound was produced. The only way out was restarting the app. Disconnection is
handled now, and every command either finds a live controller or starts a
reconnect.

Stream URLs are also retried once, and only tracks that actually resolved are
handed to the player. One unplayable item anywhere in a playlist set from a
controller is enough to make it fall back to the first track again, which is why
a queue of fifty that lost four URLs still started on the wrong one.

## 1.9.0

Smoother throughout, and playback no longer loses the queue.

### Playback

Starting a track used to leave the queue holding only the few tracks that had
been resolved so far, so next and previous ran out after two or three. The cause
turned out to be the same one behind the wrong-track bug: a media item with no
stream URL is not playable, and a playlist handed to a MediaController silently
drops the items it cannot resolve. Every stream URL is now resolved before the
queue is handed over, in small batches so a long playlist does not fire hundreds
of requests at once. The player then gets a complete, playable queue and starts
on the track that was asked for.

The trade-off is a short wait before the first track starts, which grows with the
length of the queue. The player shows its buffering state while that happens.

### Animation

- The player and the queue slide in over the list instead of replacing it, and
  the list fades out underneath. Because the list stays composed, its scroll
  position and a half-typed search now survive a trip to the player.
- The mini player slides up when something starts playing.
- Switching tabs crossfades rather than cutting.
- Tapping a lyric line or moving a queue row animates; search results animate in
  and out as they change.
- The artwork and the lyrics panel crossfade instead of swapping.

### Stability

- The app no longer redraws itself on every playback tick. The position updates
  about once a second and the whole tree, theme and backdrop included, used to
  recompose with it. Only the tint, the mini player and the player read the
  playback state now, and only the parts of it they need.
- The queue shown in the app can no longer end up shorter than the player's. The
  queue screen passes row positions straight back to the player, so a short list
  meant removing, moving or jumping to the wrong track.
- A tap that misses the player's controls can no longer reach the list underneath
  it and start another track.

## 1.8.1

- Tapping a track in the search results plays it. It opened the album instead.
  The row had a second clickable over the title and subtitle that opened the
  release, and that hotspot covered most of the row, so the row's own handler
  almost never fired. Every other track list in the app already played on tap,
  so search was also the odd one out. Opening an album is still one tap away in
  the Albums tab.
- Compose UI tests pull Espresso 3.5.0 transitively, which injects touches
  through `InputManager.getInstance()`. That method no longer exists on Android
  17, so every UI test click threw on the phone while passing on an older
  emulator. Espresso is pinned to 3.7.0 so the new tap test actually runs where
  it matters.

## 1.8.0

Synced lyrics, and lyrics embedded in the files you download.

Qobuz exposes no lyrics through its own API, so these come from
[LRCLIB](https://lrclib.net), which needs no account and returns both a plain
block and an LRC-timed one. Two settings control it, both on by default: embed
lyrics in each file, and write a `.lrc` next to the track.

- The player has a lyrics button. It shows the timed lines, highlights the
  current one, scrolls itself, and jumps to a line when you tap it. Lyrics for
  the next track load on their own as the queue advances. When only a plain
  block exists that is shown instead, rather than pretending to sync.
- Downloads carry the lyrics. FLAC gets `LYRICS` and `SYNCEDLYRICS` comments;
  MP3 gets a `USLT` frame for the text and a real `SYLT` frame for the timing.
  The optional sidecar is written as `.lrc`.
- A lookup never costs you a download: if the service is down or has nothing,
  the file is written without lyrics and everything else proceeds.

Three things here only showed up on a device:

- A `.lrc` sidecar written as `text/plain` landed on disk as
  `track.lrc.txt`. Android's document providers append the extension registered
  for a MIME type when the file name does not already end with it. It now goes
  out as `application/x-lrc`, which nothing claims, so the name is left alone.
- The first version of the lyrics view read the playback position straight off a
  `StateFlow` inside a composable. Compose cannot observe that, so the highlight
  only moved when some unrelated player event forced a redraw and it drifted
  several seconds out of step. It is collected now.
- A freshly tagged MP3 gets an ID3v2.3 tag, and `ID3v23Tag` has no public
  `addFrame`. The `SYLT` frame goes in through `addField` with a v2.3 frame
  instead, because a v2.4 frame inside a v2.3 tag produces a file no reader can
  parse.

## 1.7.0

New downloads had no cover art and landed in the wrong folder. There were three
separate causes behind that, and the first two only showed up on a device.

- Sample rate was divided by 1000 twice. Qobuz reports `maximum_sampling_rate`
  in kHz, which is why the desktop app appends "kHz" straight onto the raw
  number. A 24/96 release was named `[FLAC 24-0.1kHz]` and the album header read
  `24-bit / 0.1 kHz`.
- The folder-name cleanup used a regular expression with a bare `}` in it. The
  JVM accepts that, Android throws `PatternSyntaxException`, so every download
  started from the player failed immediately. There is now a device test for the
  naming templates, because unit tests cannot see this kind of difference.
- Downloads started from the player had no album. Queue entries for tracks that
  came from `album/get` carry no album of their own, since the album is the
  parent of that response rather than a field on each track, so there was
  nothing to pull artwork from and the file was written under "Unknown Artist".
  A queue entry without an album is now re-fetched as a full track first. The
  per-track download button on the album screen had the same hole and now passes
  the album it is already showing.

Verified on a Pixel 8: both paths now write
`ARJN, KDS, FIFTY4 & ronn/KALYANI (2025) [FLAC 24-96kHz]/01 - KALYANI.flac` with a
front-cover PICTURE block and the full tag set.

## 1.6.0

- The app follows the system light and dark setting. It used to keep its own
  idea of the theme, which is why it stayed light in a dark system.
- Back navigates inside the app instead of closing it, so search results, an
  open album and the queue survive a trip to another screen.
- The full player has a download button.

## 1.5.0

- Translucent, tinted surfaces throughout, with the tint taken from the artwork
  of the playing album. The window background itself stays opaque: making that
  role translucent washed the whole app grey.

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
