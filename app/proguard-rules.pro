# The `demo` build type supplies its catalog by name, because the fixture sources must not be
# visible to (or shipped in) release builds. Keep it and the pack it owns intact.
-keep class io.github.manuelrtsch.sidetrack.demo.** { *; }

# kotlin-logging (pulled in by the Jellyfin SDK) ships its SLF4J backend next to the Android
# native one. R8 sees the references even though Android selects the native factory, so the
# release/dev minify step fails on classes that are not on the runtime classpath.
-dontwarn org.slf4j.**
