# SignalScope release shrinking and obfuscation.
#
# Why this file exists at all: Play reports DEX obfuscation at 1% and asks for it above 25% by
# February 2027. The size win here is small -- two thirds of the download is MapLibre's native
# library and the PMTiles basemap, neither of which R8 touches -- so this is about satisfying a
# published requirement, not about making the app smaller.
#
# What is NOT enabled, deliberately: isShrinkResources. Play's requirement is about DEX, resource
# shrinking buys almost nothing here, and it is a second independent way for something to vanish
# silently. Risk without a reason.
#
# The usual thing that breaks under obfuscation is reflective serialisation -- a field renamed and
# a JSON key following it. This project has none: Contribution, BinCodec, SharedMap and Export all
# build and parse by hand with literal string keys, and every raw SQL read is by column index.


# ---------------------------------------------------------------------------------------------
# Crash reports have to stay readable.
#
# Without these a stack trace from Play is a list of a, b and c. mapping.txt must be uploaded with
# every release from now on, or the traces cannot be turned back into anything.
# ---------------------------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SF


# ---------------------------------------------------------------------------------------------
# MapLibre. The one real risk in this app.
#
# Native code calls back into Java by name across JNI, so a renamed class or method is a crash or
# a blank map rather than a compile error -- and only on a device, only at runtime. The library
# ships its own consumer rules; this is belt and braces over the packages the JNI layer reaches,
# because the failure mode is somebody else's phone showing an empty screen.
# ---------------------------------------------------------------------------------------------
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**


# ---------------------------------------------------------------------------------------------
# Shizuku. AIDL plus reflection, for the privileged tier.
#
# Obfuscating the binder interfaces breaks the tier detection silently: the app would simply
# report "tier 0 only" forever and nobody would know it was a build setting.
# ---------------------------------------------------------------------------------------------
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**


# ---------------------------------------------------------------------------------------------
# Room.
#
# The generated implementations are referenced by name from Room's runtime, and entities are read
# back into constructors. Room ships consumer rules that cover this; these are explicit because a
# database that opens but returns nothing is the hardest failure here to notice.
# ---------------------------------------------------------------------------------------------
-keep class com.signalscope.store.Db_Impl { *; }
-keep @androidx.room.Entity class com.signalscope.** { *; }
-keep class com.signalscope.store.BinHourStat { *; }
-keep class com.signalscope.store.SiteStat { *; }


# ---------------------------------------------------------------------------------------------
# This app's own reflection, such as it is.
#
# TelephonyCollector asks NetworkRegistrationInfo for getNrState() reflectively, because the
# method is hidden and blocked on recent Android. That targets a FRAMEWORK class, which R8 never
# renames, so it needs no rule -- recorded here so the next person does not go looking for one.
#
# SignalStrength.toString() is parsed for the vendor bar level. Also the platform's own output,
# also unaffected.
# ---------------------------------------------------------------------------------------------


# Kotlin coroutines and Compose carry their own rules. Nothing to add.
