# trusted-time-kmp

A **Kotlin Multiplatform** library that gives your app a clock users can't fake by changing the
device time. It syncs once with a trusted network source, then keeps time locally, so `now()` is
cheap and works offline.

> ⚠️ **Work in progress — not production-ready yet.** Android uses Google's TrustedTime API and is
> the most complete target. The iOS and JS targets still have known gaps (see
> [Known limitations](#known-limitations)). Not yet published to Maven Central.

---

## Platform support

| Platform | Trusted time source                           | Status                  |
|----------|-----------------------------------------------|-------------------------|
| Android  | Google Play Services TrustedTime API          | ✅ Tested on a device   |
| JVM      | NTP via Apache Commons Net (`pool.ntp.org`)   | ✅ Builds, unit tested  |
| iOS      | HTTP `Date` header + device uptime            | 🚧 Builds, sync untested |
| JS       | HTTP `Date` header via `fetch`                | 🚧 Builds, sync untested |

---

## Installation

Not on Maven Central yet. For now, clone this repo and depend on the module directly:

```kotlin
// settings.gradle.kts
include(":trusted-time")

// your module's build.gradle.kts
implementation(project(":trusted-time"))
```

Once published, it will be:

```kotlin
implementation("io.github.jeremy-gitau:trusted-time-kmp:<version>")
```

---

## Usage

### 1. Initialize (Android only)

The Android implementation needs an application `Context`. Call this once in
`Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TrustedTimeInitializer.init(this)
    }
}
```

### 2. Create one clock and sync it

Each `TrustedTimeClock` syncs independently, so create **one shared instance** (e.g. a singleton
or via dependency injection) rather than one per screen.

```kotlin
val clock = TrustedTimeClock()

// Suspends until the network sync finishes. Doesn't throw on network errors:
// the clock keeps its previous sync, or the system time if it never synced.
// Cancelling the coroutine cancels the sync, as usual for coroutines.
scope.launch {
    clock.sync()
}
```

### 3. Read the time

```kotlin
val now: Instant = clock.now()              // always returns a value
val source: TimeSource = clock.timeSource() // what now() would use right now
val synced: Boolean = clock.isTimeSynced()  // has a sync ever succeeded?

if (source == TimeSource.TRUSTED) {
    // safe to use for licence checks, trial expiry, timestamps, etc.
}
```

Before a successful sync, or when no trusted time is available, `now()` falls back to the device
clock and `timeSource()` returns `SYSTEM_FALLBACK`. Always check the source before relying on the
time for anything security-sensitive.

---

## API reference

```kotlin
class TrustedTimeClock() {
    fun now(): Instant            // Current time (trusted, or system fallback)
    suspend fun sync()            // Network sync; call once at startup
    fun isTimeSynced(): Boolean   // Whether a sync has succeeded
    fun timeSource(): TimeSource  // Source now() would use right now
}

enum class TimeSource {
    TRUSTED,         // From the platform's trusted source
    SYSTEM_FALLBACK  // Device clock (not synced, sync failed, or no trusted signal yet)
}
```

`Instant` is `kotlinx.datetime.Instant`.

---

## Known limitations

These are tracked and will be fixed before a 1.0 release:

- **iOS:** elapsed time uses device uptime, which may pause while the device sleeps, so the clock
  can fall behind.
- **JS:** browsers hide the `Date` header on cross-origin requests, and `window` doesn't exist in
  Node.js, so sync is likely to fail on both.
- **iOS / JS:** the HTTP `Date` header only has 1-second precision and network delay isn't
  compensated, so expect up to ~1 s of error. Fine for detecting a changed clock, not for precise
  timing.
- `TrustedTimeClock` does not implement `kotlinx.datetime.Clock` yet.

---

## Building

The project uses the Gradle wrapper (Gradle 8.14.5) and JDK 17. You don't need Gradle installed.

```bash
./gradlew :trusted-time:compileKotlinJvm               # JVM
./gradlew :trusted-time:compileDebugKotlinAndroid      # Android
./gradlew :trusted-time:jvmTest                        # run tests on the JVM
```

iOS targets require macOS with Xcode installed.

### Running the samples

```bash
./gradlew :sample-app:installDebug   # Android: install on a connected device
./gradlew :sample-jvm:run            # JVM: sync with NTP, then print trusted vs system time
```

The JVM sample runs for a minute. Meanwhile, change your computer's clock: the system time jumps, the trusted
time does not.

---

## Project structure

```
trusted-time-kmp/
├── trusted-time/              ← the library
│   └── src/
│       ├── commonMain/        ← shared API (expect class, TimeSource)
│       ├── commonTest/        ← shared tests
│       ├── androidMain/       ← Google TrustedTime implementation
│       ├── jvmMain/           ← NTP implementation
│       ├── iosMain/           ← HTTP Date header implementation
│       └── jsMain/            ← fetch implementation
├── sample-app/                ← Android demo app
├── sample-jvm/                ← command-line JVM demo
├── gradle/libs.versions.toml  ← dependency versions
└── gradlew                    ← Gradle wrapper
```

---

## Publishing (maintainers)

Publishing uses the [vanniktech maven-publish](https://github.com/vanniktech/gradle-maven-publish-plugin)
plugin. Coordinates are set in `trusted-time/build.gradle.kts`.

1. Verify the `io.github.jeremy-gitau` namespace at https://central.sonatype.com
2. Create a GPG key (`gpg --gen-key`) and publish it to a key server
3. Add credentials to your **local** `~/.gradle/gradle.properties` — never commit them:

   ```properties
   mavenCentralUsername=...
   mavenCentralPassword=...
   signing.keyId=...
   signing.password=...
   signing.secretKeyRingFile=/path/to/secring.gpg
   ```

4. Run `./gradlew :trusted-time:publishAllPublicationsToMavenCentralRepository`

---

## License

```
Copyright 2026 Jeremy Gitau

Licensed under the Apache License, Version 2.0
```
