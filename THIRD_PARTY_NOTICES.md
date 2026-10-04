# Plyvanta third-party notices

This file records the third-party software included in the Plyvanta Android
runtime. Versions match the resolved `releaseRuntimeClasspath` used for the
`v1.0.0` stable build on 2026-07-26.

Applicable license texts, notices, and required license URIs are provided in
`LICENSE` and `licenses/`. The same files are embedded in the APK under
`assets/legal/`.

## GPL-licensed components

### NewPipe Extractor 0.26.4

- Modules: `com.github.TeamNewPipe:NewPipeExtractor:v0.26.4`
- Project and source:
  https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.4
- License: GNU General Public License, version 3 or later
- License text: `LICENSE`

Plyvanta uses NewPipe Extractor without source modification.

### Android desugared NIO libraries 2.1.5

- Modules: `com.android.tools:desugar_jdk_libs_nio:2.1.5`
- Copyright: Oracle America, Inc., Google LLC, and other OpenJDK contributors
- Project and source: https://github.com/google/desugar_jdk_libs
- License: GNU General Public License, version 2, with the Classpath Exception
- License text:
  `licenses/GPL-2.0-with-Classpath-Exception.txt`
- Additional upstream licensing information:
  `licenses/desugar_jdk_libs-ADDITIONAL_LICENSE_INFO.txt`
- OpenJDK assembly exception:
  `licenses/desugar_jdk_libs-ASSEMBLY_EXCEPTION.txt`

Plyvanta uses the published artifact without source modification.

## Mozilla Public License component

### Mozilla Rhino 1.8.1

- Module: `org.mozilla:rhino:1.8.1`
- Project and source: https://github.com/mozilla/rhino/tree/Rhino1_8_1_Release
- License: Mozilla Public License 2.0
- License text: `licenses/MPL-2.0.txt`
- Required upstream notice: `licenses/Rhino-NOTICE.txt`

Plyvanta uses the published artifact without source modification. Rhino source
is available at the project link above.

## MIT-licensed component

### jsoup 1.22.2

- Module: `org.jsoup:jsoup:1.22.2`
- Copyright: 2009-2026 Jonathan Hedley
- Project and source: https://github.com/jhy/jsoup/tree/jsoup-1.22.2
- License: MIT
- License text: `licenses/MIT-jsoup.txt`

## BSD-licensed component

### Protocol Buffers Java Lite 4.35.1

- Module: `com.google.protobuf:protobuf-javalite:4.35.1`
- Copyright: 2008 Google Inc.
- Project and source: https://github.com/protocolbuffers/protobuf/tree/v35.1
- License: BSD 3-Clause
- License text: `licenses/BSD-3-Clause-protobuf.txt`

### FindBugs JSR-305 annotations 3.0.2

- Module: `com.google.code.findbugs:jsr305:3.0.2`
- Copyright: 2007-2009 JSR305 expert group
- Corrected source provenance: https://github.com/amaembo/jsr-305
- Primary license: BSD 3-Clause
- License text: `licenses/JSR-305-BSD-3-Clause.txt`
- Concurrent annotations copyright: 2005 Brian Goetz
- Concurrent annotation license: Creative Commons Attribution 2.5
- Attribution and license URI: `licenses/JSR-305-CC-BY-2.5-NOTICE.txt`

The published artifact's Maven metadata labels the whole artifact Apache-2.0,
but the source provenance above applies BSD-3-Clause and CC BY 2.5. Plyvanta
uses the corrected source licenses.

## Kotlin standard library notices

Kotlin standard library 2.2.21 is Apache-2.0 and includes code derived from
other projects:

- Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language
  contributors. Exact notice: `licenses/Kotlin-COPYRIGHT.txt`.
- Collections derived from GWT, copyright 2007-2008 Google Inc.,
  Apache-2.0.
- Time code derived from ThreeTenBP, copyright 2007-present Stephen Colebourne
  and Michael Nascimento Santos, BSD 3-Clause. License:
  `licenses/Kotlin-ThreeTenBP-BSD-3-Clause.txt`.
- `UnsignedJVM` derived from Guava, copyright 2011 The Guava Authors,
  Apache-2.0.
- `MathJVM` derived from Boost special math functions, copyright Eric Ford and
  Hubert Holin 2001, Boost Software License 1.0. License:
  `licenses/Boost-1.0.txt`.

The corresponding upstream inventory is
https://github.com/JetBrains/kotlin/blob/v2.2.21/license/README.md.

Kotlin coroutines Android/Core 1.9.0 is Apache-2.0 and ships an Apache
section 4(d) notice. Exact notice:
`licenses/kotlinx-coroutines-NOTICE.txt`.

## OkHttp Public Suffix List data

OkHttp 5.4.0 bundles `PublicSuffixDatabase.list`, compiled from the Public
Suffix List. The data is MPL-2.0. The MPL text is in
`licenses/MPL-2.0.txt`; the exact upstream notice is in
`licenses/OkHttp-PublicSuffixDatabase-NOTICE.txt`.

## Apache License 2.0 components

The following resolved runtime components are distributed under the Apache
License 2.0. The complete text is in `licenses/Apache-2.0.txt`.

- AndroidX Activity 1.13.0, Annotation 1.10.0, Arch Core 2.2.0, Collection
  1.4.2, Compose Runtime Annotation 1.9.0, Concurrent Futures 1.1.0, Core
  1.18.0, CustomView 1.0.0, ExifInterface 1.3.6, Interpolator 1.0.0,
  Lifecycle 2.6.2, NavigationEvent 1.0.0, ProfileInstaller 1.4.0,
  RecyclerView 1.3.0, Room 2.7.0, SavedState 1.2.1, SQLite 2.5.0,
  Startup 1.2.0, Tracing 1.2.0, VersionedParcelable 1.1.1, and WorkManager
  2.11.2. This inventory includes their resolved Android, JVM, KTX, common,
  framework, service, and view-tree artifacts.
- AndroidX Media3 1.10.1: Common, Container, Database, DataSource (including
  the OkHttp extension), Decoder,
  ExoPlayer, DASH, HLS, Extractor, and UI.
- Kotlin standard library 2.2.21 and JetBrains annotations 23.0.0. See the
  additional Kotlin standard-library notices above.
- Kotlin coroutines Android/Core 1.9.0. See its required notice above.
- Guava 33.3.1-android, FailureAccess 1.0.2, and ListenableFuture.
- JSpecify annotations 1.0.0.
- TeamNewPipe nanojson commit
  `e9d656ddb49a412a5a0a5d5ef20ca7ef09549996`, derived from nanojson.
  Copyright 2011 The nanojson Authors.
- OkHttp 5.4.0, copyright 2019 Square, Inc., and Okio 3.17.0, copyright
  2013 Square, Inc. See the Public Suffix List notice above.

AndroidX components are copyright The Android Open Source Project and their
respective contributors. Kotlin, Kotlin coroutines, and JetBrains annotations
are copyright JetBrains s.r.o. and their respective contributors. Guava and
FailureAccess are copyright their Google project contributors. JSpecify is
copyright the JSpecify authors.

## SponsorBlock data

SponsorBlock segment data is retrieved at runtime and is not bundled in the
APK. It is provided by the SponsorBlock community under Creative Commons
Attribution-NonCommercial-ShareAlike 4.0 International. See `NOTICE.md` for
attribution and the license URL.

## No endorsement

The inclusion of these notices does not imply endorsement of Plyvanta by any
third-party project or copyright holder.
