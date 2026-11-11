# OnAir release keep rules (used by optimization { keepRules } in build.gradle.kts).
#
# R8 moved kotlin.text.StringsKt__StringBuilderKt into the root package while its package-private parent
# (StringsKt__IndentKt, part of the same Kotlin multi-file facade) stayed in kotlin.text. On the device that is an
# IllegalAccessError while androidx.startup / WorkManager initialise, and the release build crashed at launch.
# Keeping the package names stops R8 from moving classes out of their packages (names are still shortened, code is
# still optimised). Same for the other optimised scopes, whose classes are built the same way.
-keeppackagenames kotlin.**,kotlinx.**,androidx.**

# Room 2.6.1 (pulled in by WorkManager) ships "-keep class * extends androidx.room.RoomDatabase" without members. R8 full
# mode then removes the generated database's no-argument constructor, which Room calls by reflection
# (NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init>, crash at launch). Keep it explicitly.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
