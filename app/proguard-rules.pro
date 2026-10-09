# Project-specific R8 rules. The default rules come from
# getDefaultProguardFile("proguard-android-optimize.txt"), wired up in build.gradle.kts.
#
# Nothing here yet, deliberately. This app has no reflection of its own -- no Class.forName,
# no .java handles, no @Serializable, no Room or DataStore -- and every dependency it does use
# (Compose, ExifInterface, Coil) ships its own consumer rules. So the empty file is the
# correct state, not an oversight.
#
# Add rules here rather than widening the defaults. If a shrink ever breaks at runtime, the
# `ClassNotFoundException` in `adb logcat` names the missing type; keep exactly that class
# instead of reaching for `-dontwarn` or `-keep class ** { *; }`, which would give back most of
# the size this file's existence is paying for.