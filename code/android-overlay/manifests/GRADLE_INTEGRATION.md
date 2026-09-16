# Android integration dependencies

The repository now provides two Android library modules with fixed build
dependencies. Run `make android` from the root; see
[local development](../../../docs/DEVELOPMENT.md) for SDK setup and outputs.
The snippets below describe future host application integration, not an additional
set of dependencies needed to compile this repository. The current Room file
contains entities only, so a database compiler is not required until a host adds
its Database/DAO implementation.

The overlay is intentionally SDK-neutral. Select exactly one Rokid SDK family,
then add the project dependencies required by that family. The study modules also
need:

```kotlin
dependencies {
    implementation(project(":opstate-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:<frozen>")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:<frozen>")
    implementation("com.squareup.okhttp3:okhttp:<frozen>")

    implementation("androidx.room:room-runtime:<frozen>")
    implementation("androidx.room:room-ktx:<frozen>")
    ksp("androidx.room:room-compiler:<frozen>")

    implementation(platform("androidx.compose:compose-bom:<frozen>"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
```

Do not copy version numbers blindly from another private project. Freeze versions
only after the actual phone, glasses firmware, Android Gradle Plugin and Rokid SDK
family have been confirmed.

For local harness development:

```bash
adb reverse tcp:8765 tcp:8765
```

Then point the phone debug build to `http://127.0.0.1:8765`.
