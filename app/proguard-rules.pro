# The `demo` build type supplies its catalog by name, because the fixture sources must not be
# visible to (or shipped in) release builds. Keep it and the pack it owns intact.
-keep class io.github.manuelrtsch.sidetrack.demo.** { *; }
