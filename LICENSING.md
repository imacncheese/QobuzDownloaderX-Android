# Licensing

Short version: **this project contains no code from the projects it was ported from, but it does
reimplement their algorithms, and none of them declare a license.** Read the note below before
publishing or distributing.

## This project

The Android application in this repository is original Kotlin code written for this project. It was
informed by reading the following upstream sources to understand the Qobuz API and the desktop
application's behaviour:

| Upstream | Declared license |
|---|---|
| [`ImAiiR/QobuzDownloaderX`](https://github.com/ImAiiR/QobuzDownloaderX) | **None** — no `LICENSE` file, GitHub reports no license |
| [`ImAiiR/Qo-penAPI-`](https://github.com/ImAiiR/Qo-penAPI-) | **None** |
| [`JemPH/QobuzDownloaderX-Mobile`](https://github.com/JemPH/QobuzDownloaderX-Mobile) | **None** (and it contains no source code) |

### What "no license" means

A repository with no license is **not** public domain. By default the author retains all rights,
and others have no legal permission to copy, modify or redistribute the work. There is no license
here granting permission to create derivative works.

This matters because, although no source file was copied, several components are close ports of
upstream algorithms rather than independent implementations:

- `download/PerformersParser.kt` — a port of `Helpers/QobuzDownloaderXMOD/PerformersParser.cs` and
  `InvolvedPersonRoleMapping.cs`
- `api/QobuzClient.kt` — follows `Qo(penAPI).cs` for endpoint shapes, pagination loops and the
  `track/getFileUrl` signature recipe
- `download/RenameTemplates.kt` — the `%placeholder%` naming vocabulary originates upstream
- `download/MetadataTagger.kt` — the tag field set mirrors `Helpers/TagFile.cs`

Using these privately is one thing. **Publishing them publicly, or shipping binaries, is a
distribution that the upstream authors have not licensed.** If you intend to publish this, the
pragmatic steps are:

1. Ask `ImAiiR` for permission, or for a license to be added upstream.
2. Or treat this as a private/personal project and do not distribute builds.
3. Or have a lawyer look at it — this file is a plain-language summary, not legal advice.

## Third-party dependencies

These are the app's actual dependencies; all are permissively licensed and fine to redistribute.

| Dependency | License |
|---|---|
| AndroidX / Jetpack Compose | Apache-2.0 |
| Kotlin, kotlinx.serialization | Apache-2.0 |
| OkHttp / Okio | Apache-2.0 |
| Coil | Apache-2.0 |
| JAudioTagger (`net.jthink:jaudiotagger`) | LGPL-2.1 |

JAudioTagger is LGPL-2.1. It is used here as an unmodified library dependency, which the LGPL
permits. If you ever modify JAudioTagger itself, the LGPL's relinking and source-availability
obligations apply.

## Qobuz

This project is not affiliated with, endorsed by, or approved by Qobuz. The Qobuz name and brand
are trademarks of their respective owner. Use of the Qobuz API is governed by the
[Qobuz API Terms of Use](http://static.qobuz.com/apps/api/QobuzAPI-TermsofUse.pdf), which you are
responsible for complying with, along with your local law.
