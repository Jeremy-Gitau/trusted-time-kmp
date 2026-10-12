# trusted-time-kmp

A Kotlin Multiplatform library that provides a clock users can't fake by changing the device time.

Apps that rely on the device clock for trial periods, licence expiry, time-limited offers or
audit timestamps can be fooled by moving the clock forward or back. `TrustedTimeClock` syncs once
with a trusted network source, then keeps time with the device's monotonic counter, which isn't
affected by clock changes. After the sync, `now()` needs no network, costs nothing to call and
keeps working offline.

## Platforms

| Platform | Trusted source                         | Minimum       |
|----------|----------------------------------------|---------------|
| Android  | Google Play services TrustedTime API   | API 21        |
| iOS      | HTTPS `Date` header from `www.apple.com` | iOS 15      |
| JVM      | NTP (`pool.ntp.org`)                   | Java 8        |

## Installation

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jeremy-gitau:trusted-time-kmp:1.0.0")
        }
    }
}
```

The library is not on Maven Central yet. Until it is, include the `trusted-time` module from this
repository:

```kotlin
// settings.gradle.kts
include(":trusted-time")

// build.gradle.kts
implementation(project(":trusted-time"))
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

`sync()` doesn't throw on network errors. If it fails, the clock keeps its previous sync, or uses
the device clock if it has never synced. Cancelling the coroutine cancels the sync.

`now()` always returns a value. Before a successful sync it returns the device time, and
`timeSource()` reports `SYSTEM_FALLBACK`. Check `timeSource()` before using the time for anything
security-sensitive.

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
    suspend fun sync()
    fun isTimeSynced(): Boolean
    fun timeSource(): TimeSource
}

enum class TimeSource { TRUSTED, SYSTEM_FALLBACK }
```

| Member           | Description                                                        |
|------------------|--------------------------------------------------------------------|
| `now()`          | The trusted time, or the device time when not synced               |
| `sync()`         | Fetches the trusted time; keeps the previous sync if it fails      |
| `isTimeSynced()` | Whether a sync has ever succeeded                                  |
| `timeSource()`   | The source `now()` would use right now                             |

`Clock` and `Instant` come from `kotlin.time` in the standard library, so the library adds no
date-time dependency.

## Accuracy

- **Android:** provided by Google Play services. Right after a reboot, Play services may have no
  trusted signal yet. `timeSource()` then reports `SYSTEM_FALLBACK` until it does.
- **JVM:** NTP with network delay compensation, typically within a few milliseconds of the server.
- **iOS:** the HTTP `Date` header has 1-second precision, so the time can be up to about a second
  off. That is enough to detect a changed clock, but not for precise timing.

## Samples

| Sample        | Run with                               |
|---------------|----------------------------------------|
| `sample-app`  | `./gradlew :sample-app:installDebug`   |
| `sample-jvm`  | `./gradlew :sample-jvm:run`            |
| `sample-ios`  | `open sample-ios/SampleIos.xcodeproj`  |

The JVM sample prints the trusted time next to the system time once a second for a minute. Changing
the computer's clock while it runs moves the system time but not the trusted time.

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
