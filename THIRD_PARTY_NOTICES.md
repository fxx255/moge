# Third-party notices

The MIT license at the repository root applies to Moge's own source code. Third-party dependencies, tools and fonts retain their upstream licenses. Dependencies are resolved through Gradle; their source is not copied into this repository.

Major runtime dependencies include AndroidX and Jetpack Compose, Kotlin and kotlinx libraries, Dagger/Hilt, OkHttp, Markwon, and JLaTeXMath Android. Their coordinates and pinned versions are recorded in [gradle/libs.versions.toml](gradle/libs.versions.toml).

## JLaTeXMath Android

Markwon's LaTeX extension depends on [noties/jlatexmath-android](https://github.com/noties/jlatexmath-android). Its upstream license is GPL version 2 or later with an explicit exception permitting linking with independent modules under their own license terms. Bundled fonts have separate licenses listed by upstream.

The unchanged upstream notices are preserved in:

- [JLaTeXMath LICENSE, including the linking exception and font notices](docs/licenses/jlatexmath-LICENSE.txt)
- [GNU GPL version 2](docs/licenses/jlatexmath-COPYING.txt)

Upstream library and font source, including detailed font licenses, is available in the linked project. Moge does not modify this dependency.

## Other projects

- [AndroidX](https://android.googlesource.com/platform/frameworks/support/) and [Dagger](https://github.com/google/dagger)
- [Kotlin](https://github.com/JetBrains/kotlin), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), and [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)
- [OkHttp](https://github.com/square/okhttp)
- [Markwon](https://github.com/noties/Markwon)
- [Gradle](https://github.com/gradle/gradle), including the Gradle Wrapper

When distributing a binary, preserve the applicable dependency and font notices and comply with each upstream license.
