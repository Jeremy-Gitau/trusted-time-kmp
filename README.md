# trusted-time-kmp

A Kotlin Multiplatform library that provides a clock users can't fake by changing the device time.

Apps that rely on the device clock for trial periods, licence expiry, time-limited offers or
audit timestamps can be fooled by moving the clock forward or back. `TrustedTimeClock` syncs once
with a trusted network source, then keeps time with the device's monotonic counter, which isn't
affected by clock changes. After the sync, `now()` needs no network, costs nothing to call and
keeps working offline.

## Platforms

| Platform | Trusted source                                                   | Minimum |
|----------|------------------------------------------------------------------|---------|
| Android  | Google Play services TrustedTime API, or HTTPS `Date` without it | API 21  |
| iOS      | HTTPS `Date` header from `www.apple.com`                         | iOS 15  |
| JVM      | NTP (`pool.ntp.org`), or HTTPS `Date` where NTP is blocked       | Java 8  |

Where a platform's main source isn't available, the clock falls back to the `Date` header of an
HTTPS response from `www.apple.com`.

## Installation

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jeremy-gitau:trusted-time-kmp:0.2.0")
        }
    }
}
```

The library is on [Maven Central](https://central.sonatype.com/artifact/io.github.jeremy-gitau/trusted-time-kmp).
Android-only and JVM-only projects can add the same dependency to their `dependencies` block:

```kotlin
dependencies {
    implementation("io.github.jeremy-gitau:trusted-time-kmp:0.2.0")
}
```

## Usage

### Android setup

The Android implementation needs the application context. Initialize it once in your
`Application`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TrustedTimeInitializer.init(this)
    }
}
```

iOS and the JVM need no setup.

### Sync and read the time

```kotlin
val clock = TrustedTimeClock()

scope.launch {
    clock.sync()
}

val now: Instant = clock.now()

if (clock.timeSource() == TimeSource.TRUSTED) {
    // The time can't have been changed from the device settings
}
```

Each `TrustedTimeClock` syncs on its own, so share a single instance across the app.

`sync()` returns whether it succeeded, and doesn't throw on network errors. If it fails, the clock
keeps its previous sync, or uses the device clock if it has never synced. Cancelling the coroutine cancels the sync.

`now()` always returns a value. Before a successful sync it returns the device time, and
`timeSource()` reports `SYSTEM_FALLBACK`. Check `timeSource()` before using the time for anything
security-sensitive, and call `sync()` again when it reports `SYSTEM_FALLBACK`, for example when the
app returns to the foreground.

On iOS the sync is saved, so it survives an app restart until the device reboots. On Android, Play
services keeps its own trusted time across restarts. On the JVM, call `sync()` each time the app
starts.

### Use as a `kotlin.time.Clock`

`TrustedTimeClock` implements `kotlin.time.Clock`, so it works anywhere a `Clock` is expected,
including [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime) 0.7 or later:

```kotlin
val today: LocalDate = clock.todayIn(TimeZone.currentSystemDefault())
```

### Swift

On iOS the library is the `TrustedTime` framework, and `sync()` is an `async` function. Call it on
the main actor:

```swift
import TrustedTime

let clock = TrustedTimeClock()

Task { @MainActor in
    try? await clock.sync()
}

let nowMs = clock.now().toEpochMilliseconds()
let trusted = clock.timeSource() == .trusted
```

## API

```kotlin
class TrustedTimeClock() : Clock {
    override fun now(): Instant
    suspend fun sync(): Boolean
    fun isTimeSynced(): Boolean
    fun timeSource(): TimeSource
}

enum class TimeSource { TRUSTED, SYSTEM_FALLBACK }
```

| Member           | Description                                                                       |
|------------------|-----------------------------------------------------------------------------------|
| `now()`          | The trusted time, or the device time when not synced                              |
| `sync()`         | Fetches the trusted time; returns `false` and keeps the previous sync if it fails |
| `isTimeSynced()` | Whether a sync has ever succeeded                                                 |
| `timeSource()`   | The source `now()` would use right now                                            |

`Clock` and `Instant` come from `kotlin.time` in the standard library, so the library adds no
date-time dependency.

## Accuracy

- **Android:** provided by Google Play services. Right after a reboot, Play services may have no
  trusted signal yet, so `sync()` uses the HTTPS fallback until it does.
- **JVM:** NTP with network delay compensation, typically within a few milliseconds of the server.
  The JVM's monotonic counter pauses while the computer sleeps on macOS and Linux. A sleep looks
  the same as the clock being moved forward, so either one makes `timeSource()` report
  `SYSTEM_FALLBACK` until the next `sync()`. Moving the clock back has no effect.
- **iOS:** the HTTPS `Date` header only has whole seconds, so `sync()` makes about six requests
  over one to two seconds to find the moment it changes second. The time is typically within
  50 ms.
- **HTTPS fallback on Android and the JVM:** a single request, within about half a second. That is
  enough to detect a changed clock, but not for precise timing.

## Samples

| Sample        | Run with                               |
|---------------|----------------------------------------|
| `sample-app`  | `./gradlew :sample-app:installDebug`   |
| `sample-jvm`  | `./gradlew :sample-jvm:run`            |
| `sample-ios`  | `open sample-ios/SampleIos.xcodeproj`  |

The JVM sample is a Compose for Desktop app that shows the trusted time next to the system time,
updated live. Moving the computer's clock back while it runs moves the system time but not the
trusted time. Moving it forward switches the source to `SYSTEM_FALLBACK` until the next sync.

## Building

Requires JDK 17. iOS targets also require macOS with Xcode.

```bash
./gradlew :trusted-time:jvmTest                  # JVM tests
./gradlew :trusted-time:testAndroidHostTest      # Android tests
./gradlew :trusted-time:iosSimulatorArm64Test    # iOS simulator tests
./ktlint.sh                                      # format and check code style
```

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

```
Copyright 2026 Jeremy Gitau
```
