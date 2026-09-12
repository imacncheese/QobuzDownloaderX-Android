# Licensing

Worth reading before you publish or distribute this.

The short version: no code was copied from the projects this was ported from, but it does
reimplement their algorithms, and none of those projects declare a licence.

## This project

The Kotlin in this repo was written for this project. I read the upstream sources to work out how
the Qobuz API behaves and what the desktop app does with it:

| Upstream | Licence |
|---|---|
| [ImAiiR/QobuzDownloaderX](https://github.com/ImAiiR/QobuzDownloaderX) | None. No LICENSE file, GitHub reports no licence |
| [ImAiiR/Qo-penAPI-](https://github.com/ImAiiR/Qo-penAPI-) | None |
| [JemPH/QobuzDownloaderX-Mobile](https://github.com/JemPH/QobuzDownloaderX-Mobile) | None, and it has no source in it anyway |

## What "no licence" means

No licence is not the same as public domain. The author keeps all rights by default, and nobody
else has permission to copy, modify or redistribute the work. Nothing upstream grants permission
to make derivative works.

That matters here because a few files are close ports of upstream algorithms rather than
independent implementations:

- `download/PerformersParser.kt` follows `Helpers/QobuzDownloaderXMOD/PerformersParser.cs` and
  `InvolvedPersonRoleMapping.cs`
- `api/QobuzClient.kt` follows `Qo(penAPI).cs` for endpoint shapes, pagination and the
  `track/getFileUrl` signature
- `download/RenameTemplates.kt` uses the `%placeholder%` naming vocabulary from upstream
- `download/MetadataTagger.kt` mirrors the tag fields in `Helpers/TagFile.cs`

Using this privately is one thing. Publishing it or shipping builds is a distribution the upstream
authors haven't licensed. If you want to publish it, the realistic options are:

1. Ask ImAiiR for permission, or for a licence to be added upstream.
2. Keep it personal and don't distribute builds.
3. Get a lawyer to look at it. This file is my plain reading, not legal advice.

## Dependencies

These are what the app actually depends on, and all of them are fine to redistribute.

| Dependency | Licence |
|---|---|
| AndroidX, Jetpack Compose | Apache-2.0 |
| Kotlin, kotlinx.serialization | Apache-2.0 |
| OkHttp, Okio | Apache-2.0 |
| Coil | Apache-2.0 |
| JAudioTagger (`net.jthink:jaudiotagger`) | LGPL-2.1 |

JAudioTagger is LGPL-2.1 and is used here as an unmodified library dependency, which the LGPL
allows. If you ever modify JAudioTagger itself, the LGPL's relinking and source availability
obligations apply.

## Qobuz

Not affiliated with, endorsed by, or approved by Qobuz. The Qobuz name and brand belong to their
respective owner. Using the Qobuz API is governed by the
[Qobuz API Terms of Use](http://static.qobuz.com/apps/api/QobuzAPI-TermsofUse.pdf), which is on you
to follow, along with your local law.
