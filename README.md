# trusted-time-kmp

A **Kotlin Multiplatform SDK** that provides tamper-proof time across Android, iOS, JVM, and JS.
Uses platform-native trusted time sources so users cannot spoof your app's clock by changing device settings.

---

## Platform Support

| Platform | Trusted Time Source                          |
|----------|----------------------------------------------|
| Android  | Google Play Services TrustedTime API         |
| iOS      | Cloudflare HTTP time header + monotonic clock |
| JVM      | NTP via Apache Commons Net (`pool.ntp.org`)   |
| JS       | Cloudflare HTTP time header                   |

---

## Installation

Add to your shared module's `build.gradle.kts`:

```kotlin
implementation("io.github.jeremy-gitau:trusted-time-kmp:1.0.0")
```

> **Not yet published to Maven Central.** Until then, clone this repo and depend on the `:trusted-time` module directly.

---

## Usage

### 1. Initialize (Android only)

In your `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TrustedTimeInitializer.init(this)
    }
}
```

### 2. Sync once at startup

```kotlin
val clock = TrustedTimeClock()

// Call once — ideally in a ViewModel or at app start
viewModelScope.launch {
    clock.sync()
}
```

### 3. Use everywhere

```kotlin
val now: Instant = clock.now()
val isTrusted: Boolean = clock.isTimeSynced()
val source: TimeSource = clock.timeSource() // TRUSTED or SYSTEM_FALLBACK
```

### 4. Use as a `kotlinx.datetime` Clock

```kotlin
// TrustedTimeClock is a drop-in wherever Clock is expected
fun doSomethingWithClock(clock: Clock) {
    val now = clock.now()
}

doSomethingWithClock(clock)
```

---

## API Reference

```kotlin
class TrustedTimeClock() : Clock {
    fun now(): Instant           // Current time (trusted or fallback)
    suspend fun sync()           // Perform network sync (call once at startup)
    fun isTimeSynced(): Boolean  // Whether a successful sync has occurred
    fun timeSource(): TimeSource // TRUSTED or SYSTEM_FALLBACK
}

enum class TimeSource {
    TRUSTED,        // Time from NTP / trusted service
    SYSTEM_FALLBACK // Device system clock (sync not yet done or failed)
}
```

---

## Publishing to Maven Central

1. Create a Sonatype account at https://central.sonatype.com
2. Register your `io.github.<username>` namespace
3. Generate a GPG key: `gpg --gen-key`
4. Export your key: `gpg --export-secret-keys -o secring.gpg`
5. Add credentials to `~/.gradle/gradle.properties`:

```properties
mavenCentralUsername=your_username
mavenCentralPassword=your_password
signing.keyId=LAST_8_CHARS
signing.password=your_passphrase
signing.secretKeyRingFile=/path/to/secring.gpg
```

6. Update `trusted-time/build.gradle.kts` with your coordinates
7. Run: `./gradlew :trusted-time:publishAllPublicationsToMavenCentralRepository`

---

## Project Structure

```
trusted-time-kmp/
├── trusted-time/                        ← SDK library module
│   └── src/
│       ├── commonMain/                  ← expect class + shared API
│       ├── androidMain/                 ← Google TrustedTime actual
│       ├── iosMain/                     ← Cloudflare HTTP + monotonic actual
│       ├── jvmMain/                     ← Apache Commons NTP actual
│       └── jsMain/                      ← Cloudflare fetch actual
├── sample-app/                          ← Android demo app
├── gradle/libs.versions.toml            ← Version catalog
└── build.gradle.kts
```

---

## License

```
Copyright 2026 Jeremy Gitau

Licensed under the Apache License, Version 2.0
```
