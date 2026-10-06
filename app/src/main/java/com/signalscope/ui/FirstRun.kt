package com.signalscope.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What to expect, and when -- shown once, after consent and the permission prompts.
 *
 * ## Why this screen exists
 *
 * Almost everything in this app is deliberately silent until it has enough evidence to speak.
 * The mast card says nothing until a mast has been measured enough times at that hour. The map
 * draws nothing until a position fix arrives. The shared layer shows nothing until three separate
 * phones have covered an area. Each silence is correct, and each is indistinguishable from the
 * app being broken if nobody said it was coming.
 *
 * That is not a guess about new users. It is what happened to the people who already had it: a
 * map that stayed empty for twenty-eight minutes, a mast card that never appeared for four days,
 * a shared layer that drew nothing while seven contributions sat on the server. In every case the
 * app was behaving correctly and looked broken.
 *
 * ## Why it is one screen and not a carousel
 *
 * Nobody reads the second page. The four things below are the four silences somebody will
 * actually hit in their first week, in the order they will hit them, and the last one is the
 * limit that makes the rest honest.
 */
object FirstRun {
    private const val PREFS = "first_run"
    private const val KEY_SEEN = "expectations_seen_v1"

    fun seen(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    fun markSeen(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
    }

    /** Part of "delete everything collected": a fresh install should read as a fresh install. */
    fun forget(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

@Composable
fun FirstRunScreen(onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Text(
            "Set up",
            color = T.Text, fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Three permissions, each asked for here once and only when you tap it. " +
                "You can skip any of them and the rest still works.",
            color = T.Dim, fontSize = 13.sp, lineHeight = 19.sp
        )
        Spacer(Modifier.height(14.dp))
        SetupRows()
        Spacer(Modifier.height(26.dp))
        Text(
            "What to expect",
            color = T.Text, fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Most of this app stays quiet until it has enough evidence to say something. " +
                "That is deliberate, and it means the first few days are quieter than you " +
                "might expect.",
            color = T.Dim, fontSize = 13.sp, lineHeight = 19.sp
        )
        Spacer(Modifier.height(20.dp))

        Item(
            "Straight away",
            "The live readings. Signal strength, quality, which mast and which band — and the " +
                "three different bar counts your phone computes for the same instant."
        )
        Item(
            "Within a few minutes",
            "A verdict on where you are: good, patchy, or a bad area. It needs about ten " +
                "minutes of readings before it will commit to one."
        )
        Item(
            "Once you move around outdoors",
            "The coverage map. It needs a position fix, and indoors on mobile data with Wi-Fi " +
                "off that can take a long time or never arrive. Nothing is guessed at in the " +
                "meantime — an empty map means no measurements, not no coverage."
        )
        Item(
            "After some days",
            "What each mast has been like at each time of day. It stays silent about a mast it " +
                "has not measured enough, which is not the same as saying that mast is fine."
        )
        Item(
            "Only with other people",
            "The shared map. An area appears on it once three separate phones have measured " +
                "it, so it will show nothing at all until that happens near you."
        )

        Spacer(Modifier.height(6.dp))
        // The limit, stated before anything else can imply otherwise. Everything above is
        // description; this is the part that decides whether the rest is worth trusting.
        Text(
            "What it cannot do",
            color = T.Bad, fontSize = 15.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "It cannot fix your connection. No app can choose which mast you are on or which " +
                "band you get — that needs a signed carrier app. What it can do is tell you " +
                "what is happening and why, so the answer to \"is it me?\" is measured rather " +
                "than guessed.",
            color = T.Dim, fontSize = 13.sp, lineHeight = 19.sp
        )

        Spacer(Modifier.height(24.dp))
        Btn("Start collecting") { onDone() }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Item(whenIt: String, what: String) {
    Text(whenIt, color = T.Brand, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    Spacer(Modifier.height(3.dp))
    Text(what, color = T.Dim, fontSize = 12.5.sp, lineHeight = 17.sp)
    Spacer(Modifier.height(15.dp))
}


@Composable
private fun SetupRows() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val refused = remember { mutableStateMapOf<String, Boolean>() }
    var asking by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        asking?.let { if (!ok) refused[it] = true }
        tick++
    }

    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun appSettings() {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
            )
        }
    }

    @Composable
    fun Row(title: String, why: String, granted: Boolean, perm: String?, optional: Boolean = false,
            onOther: (() -> Unit)? = null) {
        Text(
            title + if (optional) "  ·  optional" else "",
            color = T.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(3.dp))
        Text(why, color = T.Dim, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(6.dp))
        if (granted) {
            Text("Allowed", color = T.Good, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        } else if (perm != null) {
            val blocked = refused[perm] == true
            Btn(if (blocked) "Open app settings" else "Allow", ghost = true) {
                if (blocked) appSettings() else { asking = perm; launcher.launch(perm) }
            }
        } else if (onOther != null) {
            Btn("Open battery settings", ghost = true) { onOther() }
        }
        Spacer(Modifier.height(16.dp))
    }

    // Read so the rows re-evaluate after a result or after coming back from Settings.
    tick.let {
        Row(
            "Phone",
            "Reads which network, mast and band is serving you. Without it the app only sees " +
                "signal strength. It never reads calls, messages or contacts.",
            has(Manifest.permission.READ_PHONE_STATE), Manifest.permission.READ_PHONE_STATE
        )
        Row(
            "Location",
            "Puts each reading on the map. Used while the app is collecting; a position is " +
                "kept in memory only, never stored as a trail.",
            has(Manifest.permission.ACCESS_FINE_LOCATION), Manifest.permission.ACCESS_FINE_LOCATION
        )
        Row(
            "Notifications",
            "Tells you when you enter or leave a poor data area.",
            Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS),
            Manifest.permission.POST_NOTIFICATIONS
        )
        val pm = ctx.getSystemService(PowerManager::class.java)
        Row(
            "Battery",
            "Some phones, Samsung especially, pause apps in the background. Setting SignalScope " +
                "to unrestricted keeps measuring with the screen off.",
            pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true, null, optional = true,
            onOther = {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        )
    }
}
