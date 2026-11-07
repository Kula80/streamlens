package com.streamlens.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Size
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.streamlens.app.camera.CameraCapabilities
import com.streamlens.app.camera.CameraController
import com.streamlens.app.camera.CameraControls
import com.streamlens.app.camera.WhiteBalance
import com.streamlens.app.net.NetworkCheck
import com.streamlens.app.net.ObsFinder
import com.streamlens.app.net.ObsNetworkPin
import com.streamlens.app.net.SrtSender
import com.streamlens.app.service.StreamService
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Dial { EV, ISO, SHUTTER, FOCUS, ZOOM }

    private lateinit var engine: StreamEngine
    private lateinit var settings: StreamSettings
    private var caps: CameraCapabilities? = null
    private var controls = CameraControls()
    private var dial: Dial? = null
    private var lastStatus = EngineStatus()
    private val ui = Handler(Looper.getMainLooper())
    private val networkPin by lazy { ObsNetworkPin(this) }

    private val v by lazy { Views(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        settings = StreamSettings.load(this)
        engine = StreamEngine(this, ::render)
        caps = engine.cameraId?.let { CameraController.capabilities(this, it) }

        v.live.setOnClickListener { toggleLive() }
        v.settings.setOnClickListener { showSettings() }
        v.check.setOnClickListener { showNetworkCheck() }
        v.dimToggle.setOnClickListener {
            v.dim.visibility = if (v.dim.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        v.dim.setOnClickListener { v.dim.visibility = View.GONE }
        wireControls()
        refreshControlLabels()
        render(lastStatus)

        if (hasCameraPermission()) attachPreview() else requestPermissions()
        ui.post(phoneStatsTick)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        networkPin.stop()
        engine.release()
        StreamService.stop(this)
        super.onDestroy()
    }

    // region Permissions and preview

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val wanted = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(wanted.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasCameraPermission()) attachPreview()
        else Toast.makeText(this, "StreamLens needs the camera to work", Toast.LENGTH_LONG).show()
    }

    private fun attachPreview() {
        val id = engine.cameraId ?: return toast("No back camera found")
        val size = CameraController.previewSize(this, id, Size(16, 9))
        v.preview.holder.setFixedSize(size.width, size.height)
        v.preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width == size.width && height == size.height && !engine.isLive) {
                    engine.startPreview(holder.surface)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (!engine.isLive) engine.stopLive(reopenPreview = false)
            }
        })
        fitPreview(size)
    }

    /** Letterboxes the preview so it shows the true 16:9 frame that OBS receives. */
    private fun fitPreview(size: Size) {
        v.preview.post {
            val parent = v.preview.parent as View
            val ratio = size.width.toFloat() / size.height
            val lp = v.preview.layoutParams
            if (parent.width / parent.height.toFloat() > ratio) {
                lp.height = parent.height
                lp.width = (parent.height * ratio).roundToInt()
            } else {
                lp.width = parent.width
                lp.height = (parent.width / ratio).roundToInt()
            }
            v.preview.layoutParams = lp
        }
    }

    // endregion

    // region Going live

    private fun toggleLive() {
        if (engine.isLive) {
            AlertDialog.Builder(this)
                .setTitle("Stop streaming?")
                .setMessage("OBS will show your right-back screen until you go live again.")
                .setPositiveButton("Stop") { _, _ ->
                    engine.stopLive()
                    StreamService.stop(this)
                    networkPin.stop()
                }
                .setNegativeButton("Keep streaming", null)
                .show()
            return
        }
        val report = NetworkCheck.run(this)
        if (report.suggestedMbps == 0) {
            AlertDialog.Builder(this).setTitle("Not connected").setMessage(report.warning).setPositiveButton("OK", null).show()
            return
        }
        locateObs { askSilenceThenGoLive() }
    }

    /**
     * Makes sure we know where OBS is: checks the address used last time, and if OBS doesn't answer
     * there (first use, or the computer got a new IP), searches the network for it.
     */
    private fun locateObs(then: () -> Unit) {
        val searching = AlertDialog.Builder(this).setMessage("Looking for OBS on your network…").setCancelable(false).show()
        val saved = settings.host
        val port = settings.port
        thread(name = "locate-obs") {
            val savedAnswers = saved.isNotBlank() && ObsFinder.probe(this, saved, port)
            val found = if (savedAnswers) emptyList() else ObsFinder.find(this, port).map { it.host }
            ui.post {
                searching.dismiss()
                if (isFinishing || isDestroyed) return@post
                when {
                    savedAnswers -> then()
                    found.size == 1 -> {
                        useHost(found[0])
                        then()
                    }
                    found.size > 1 -> chooseObs(found) { useHost(it); then() }
                    // OBS may still be starting; SRT keeps retrying the last address.
                    saved.isNotBlank() -> {
                        toast("OBS didn't answer yet. Will keep trying $saved")
                        then()
                    }
                    else -> showObsNotFound { locateObs(then) }
                }
            }
        }
    }

    private fun useHost(host: String) {
        if (host == settings.host) return
        settings = settings.copy(host = host).also { it.save(this) }
        toast("Found OBS at $host")
    }

    private fun chooseObs(hosts: List<String>, onChosen: (String) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("More than one OBS found")
            .setItems(hosts.toTypedArray()) { _, i -> onChosen(hosts[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showObsNotFound(retry: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Couldn't find OBS")
            .setMessage(
                "Check that:\n\n" +
                    "• The phone and computer are on the same Wi-Fi or router (not a guest network)\n" +
                    "• OBS is open and you clicked Set up phone camera (Tools → Scripts)\n" +
                    "• Windows allowed OBS on Private networks when it asked (firewall)\n" +
                    "• The port in Settings matches the one in OBS (${settings.port})\n\n" +
                    "You can also type the computer's IP address in Settings.",
            )
            .setPositiveButton("Try again") { _, _ -> retry() }
            .setNeutralButton("Settings") { _, _ -> showSettings() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askSilenceThenGoLive() {
        if (settings.silenceWhileLive && !getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) {
            AlertDialog.Builder(this)
                .setTitle("Silence calls while live?")
                .setMessage("Allow StreamLens to turn on Do Not Disturb while streaming, so calls can't interrupt the camera.")
                .setPositiveButton("Allow") { _, _ -> startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                .setNegativeButton("Go live anyway") { _, _ -> startLive() }
                .show()
            return
        }
        startLive()
    }

    private fun startLive() {
        StreamService.start(this, settings.silenceWhileLive)
        bindToObsNetwork()
        engine.goLive(settings)
    }

    /** Keeps the stream on the network that reaches OBS, even if Android prefers mobile data. */
    private fun bindToObsNetwork() = networkPin.start(settings.host)

    private fun render(status: EngineStatus) {
        lastStatus = status
        val live = engine.isLive
        v.live.isSelected = live
        v.live.text = if (live) "STOP" else "GO LIVE"

        val (text, color) = when (status.link) {
            SrtSender.State.LIVE -> "● LIVE to OBS" to R.color.live
            SrtSender.State.CONNECTING -> "● Connecting to OBS…" to R.color.warn
            SrtSender.State.RECONNECTING -> "● Reconnecting…" to R.color.warn
            SrtSender.State.STOPPED -> "● Not live" to android.R.color.white
        }
        v.link.text = text
        v.link.setTextColor(getColor(color))
        // Before going live, show what will be sent so saved settings are visible straight away.
        v.video.text = if (live) status.videoLabel else "Ready: ${settingsSummary()}"
        v.bitrate.text = if (live) String.format(Locale.US, "%.1f Mbps", status.bitrateBps / 1e6) else ""
        v.net.text = status.stats?.let {
            String.format(Locale.US, "RTT %.0f ms · loss %.1f%% · buffer %d ms", it.rttMs, it.lossPercent, it.sendBufferMs)
        } ?: ""

        val warning = status.error ?: status.heatWarning
        v.warning.text = warning
        v.warning.visibility = if (warning == null) View.GONE else View.VISIBLE
    }

    private val phoneStatsTick = object : Runnable {
        override fun run() {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (battery != null) {
                val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 /
                    battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
                val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                v.phone.text = String.format(Locale.US, "%.0f°C · %d%%%s", temp, level, if (plugged) " ⚡" else "")
            }
            ui.postDelayed(this, 5000)
        }
    }

    // endregion

    // region Camera controls

    private fun wireControls() {
        // Manual → auto, auto → locked, locked → auto.
        v.aeLock.setOnClickListener {
            if (controls.manualIso != null) exposureToAuto()
            else set(controls.copy(exposureLocked = !controls.exposureLocked))
        }
        v.wbLock.setOnClickListener { set(controls.copy(whiteBalanceLocked = !controls.whiteBalanceLocked)) }
        v.natural.setOnClickListener { set(controls.copy(natural = !controls.natural)) }
        v.wb.setOnClickListener {
            val all = WhiteBalance.entries
            set(controls.copy(whiteBalance = all[(controls.whiteBalance.ordinal + 1) % all.size]))
        }
        v.ev.setOnClickListener {
            if (controls.manualIso != null) toast("EV only works in auto. Tap Exposure to go back to auto.") else openDial(Dial.EV)
        }
        v.iso.setOnClickListener { if (caps?.manualSensor == true) openDial(Dial.ISO) else notOnThisPhone() }
        v.shutter.setOnClickListener { if (caps?.manualSensor == true) openDial(Dial.SHUTTER) else notOnThisPhone() }
        v.focus.setOnClickListener { if ((caps?.minFocusDiopters ?: 0f) > 0f) openDial(Dial.FOCUS) else notOnThisPhone() }
        v.zoom.setOnClickListener { if (caps?.zoomRange != null) openDial(Dial.ZOOM) else notOnThisPhone() }

        v.slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onDial(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        v.sliderAuto.setOnClickListener {
            when (dial) {
                Dial.EV -> set(controls.copy(evIndex = 0))
                Dial.ISO, Dial.SHUTTER -> exposureToAuto()
                Dial.FOCUS -> set(controls.copy(manualFocus = null))
                Dial.ZOOM -> set(controls.copy(zoom = 1f))
                null -> Unit
            }
            v.sliderPanel.visibility = View.GONE
            dial = null
        }
    }

    private fun exposureToAuto() {
        // An open ISO or shutter slider would switch the camera straight back to manual.
        if (dial == Dial.ISO || dial == Dial.SHUTTER) {
            v.sliderPanel.visibility = View.GONE
            dial = null
        }
        set(controls.copy(manualIso = null, manualExposureNs = null, exposureLocked = false))
    }

    private fun shutterChoices(): List<Long> {
        val c = caps ?: return emptyList()
        val range = c.exposureRangeNs ?: return emptyList()
        val maxForFps = 1_000_000_000L / settings.fps
        return listOf(24, 25, 30, 48, 50, 60, 100, 120, 250, 500, 1000, 2000)
            .map { 1_000_000_000L / it }
            .filter { it in range && it <= maxForFps }
    }

    private fun openDial(d: Dial) {
        if (dial == d && v.sliderPanel.visibility == View.VISIBLE) {
            v.sliderPanel.visibility = View.GONE
            dial = null
            return
        }
        val c = caps ?: return
        dial = d
        when (d) {
            Dial.EV -> {
                v.slider.max = c.evRange.upper - c.evRange.lower
                v.slider.progress = controls.evIndex - c.evRange.lower
            }
            Dial.ISO, Dial.SHUTTER -> {
                // Manual exposure needs both values; start from a sensible 180° shutter.
                if (controls.manualIso == null) {
                    val shutter = shutterChoices().firstOrNull { it <= 1_000_000_000L / (settings.fps * 2) }
                        ?: shutterChoices().lastOrNull()
                    set(controls.copy(manualIso = 400.coerceIn(c.isoRange!!.lower, c.isoRange.upper), manualExposureNs = shutter))
                }
                if (d == Dial.ISO) {
                    v.slider.max = 100
                    v.slider.progress = isoToProgress(controls.manualIso!!)
                } else {
                    val choices = shutterChoices()
                    v.slider.max = (choices.size - 1).coerceAtLeast(0)
                    v.slider.progress = choices.indexOf(controls.manualExposureNs).coerceAtLeast(0)
                }
            }
            Dial.FOCUS -> {
                v.slider.max = 100
                v.slider.progress = (((controls.manualFocus ?: 0f) / c.minFocusDiopters) * 100).roundToInt()
                if (controls.manualFocus == null) set(controls.copy(manualFocus = 0f))
            }
            Dial.ZOOM -> {
                val z = c.zoomRange!!
                v.slider.max = ((z.upper - z.lower) * 10).roundToInt()
                v.slider.progress = ((controls.zoom - z.lower) * 10).roundToInt()
            }
        }
        v.sliderPanel.visibility = View.VISIBLE
        refreshControlLabels()
    }

    private fun isoToProgress(iso: Int): Int {
        val r = caps?.isoRange ?: return 0
        val t = Math.log(iso.toDouble() / r.lower) / Math.log(r.upper.toDouble() / r.lower)
        return (t * 100).roundToInt()
    }

    private fun progressToIso(p: Int): Int {
        val r = caps?.isoRange ?: return 400
        return (r.lower * Math.pow(r.upper.toDouble() / r.lower, p / 100.0)).roundToInt()
    }

    private fun onDial(progress: Int) {
        val c = caps ?: return
        when (dial) {
            Dial.EV -> set(controls.copy(evIndex = progress + c.evRange.lower))
            Dial.ISO -> set(controls.copy(manualIso = progressToIso(progress)))
            Dial.SHUTTER -> shutterChoices().getOrNull(progress)?.let { set(controls.copy(manualExposureNs = it)) }
            Dial.FOCUS -> set(controls.copy(manualFocus = c.minFocusDiopters * progress / 100f))
            Dial.ZOOM -> set(controls.copy(zoom = c.zoomRange!!.lower + progress / 10f))
            null -> Unit
        }
    }

    private fun set(newControls: CameraControls) {
        controls = newControls
        engine.setControls(newControls)
        refreshControlLabels()
    }

    private fun refreshControlLabels() {
        val c = caps
        v.aeLock.text = if (controls.manualIso != null) "Exposure: manual"
        else if (controls.exposureLocked) "Exposure: locked" else "Exposure: auto"
        val ev = (c?.evStep ?: 1f) * controls.evIndex
        v.ev.text = String.format(Locale.US, "EV %+.1f", ev)
        v.iso.text = controls.manualIso?.let { "ISO $it" } ?: "ISO auto"
        v.shutter.text = controls.manualExposureNs?.let { "Shutter 1/${(1_000_000_000.0 / it).roundToInt()}" } ?: "Shutter auto"
        v.wb.text = "WB ${controls.whiteBalance.label}"
        v.wbLock.text = if (controls.whiteBalanceLocked) "WB: locked" else "WB: free"
        v.focus.text = controls.manualFocus?.let {
            if (it == 0f) "Focus ∞" else String.format(Locale.US, "Focus %.1f m", 1 / it)
        } ?: "Focus auto"
        v.zoom.text = String.format(Locale.US, "Zoom %.1f×", controls.zoom)
        v.natural.text = if (controls.natural) "Look: natural" else "Look: phone"
        v.sliderLabel.text = when (dial) {
            Dial.EV -> v.ev.text
            Dial.ISO -> v.iso.text
            Dial.SHUTTER -> v.shutter.text
            Dial.FOCUS -> v.focus.text
            Dial.ZOOM -> v.zoom.text
            null -> ""
        }
    }

    private fun notOnThisPhone() = toast("This phone's camera doesn't allow that control")

    // endregion

    // region Settings and network check

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val host = view.findViewById<EditText>(R.id.host)
        val port = view.findViewById<EditText>(R.id.port)
        val resolution = view.findViewById<Spinner>(R.id.resolution)
        val fps = view.findViewById<Spinner>(R.id.fps)
        val codec = view.findViewById<Spinner>(R.id.codec)
        val bitrate = view.findViewById<SeekBar>(R.id.bitrate)
        val bitrateLabel = view.findViewById<TextView>(R.id.bitrateLabel)
        val latency = view.findViewById<Spinner>(R.id.latency)
        val adaptive = view.findViewById<CheckBox>(R.id.adaptive)
        val silence = view.findViewById<CheckBox>(R.id.silence)
        val obsUrl = view.findViewById<TextView>(R.id.obsUrl)

        fun <T> Spinner.fill(items: List<T>, label: (T) -> String, selected: T) {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items.map(label))
            setSelection(items.indexOf(selected).coerceAtLeast(0))
        }

        host.setText(settings.host)
        port.setText(settings.port.toString())
        resolution.fill(Resolution.entries, { it.label }, settings.resolution)
        fps.fill(StreamSettings.FPS_CHOICES, { "$it fps" }, settings.fps)
        codec.fill(Codec.entries, { it.label }, settings.codec)
        latency.fill(LatencyPreset.entries, { "${it.label} (${it.ms} ms)" }, settings.latency)
        adaptive.isChecked = settings.adaptiveBitrate
        silence.isChecked = settings.silenceWhileLive

        val find = view.findViewById<Button>(R.id.findObs)
        find.setOnClickListener {
            val p = port.text.toString().toIntOrNull()?.takeIf { it in StreamSettings.PORT_RANGE } ?: settings.port
            find.isEnabled = false
            find.text = "Searching…"
            host.error = null
            thread(name = "find-obs") {
                val found = ObsFinder.find(this, p).map { it.host }
                ui.post {
                    find.isEnabled = true
                    find.text = getString(R.string.find_obs)
                    when (found.size) {
                        0 -> host.error = "OBS not found on port $p. Is OBS open with the phone camera set up?"
                        1 -> host.setText(found[0])
                        else -> chooseObs(found) { host.setText(it) }
                    }
                }
            }
        }

        fun updateLabels() {
            bitrateLabel.text = "Quality: ${bitrate.progress + 2} Mbps"
            val p = port.text.toString().toIntOrNull() ?: settings.port
            val ms = LatencyPreset.entries[latency.selectedItemPosition].ms
            obsUrl.text = "srt://0.0.0.0:$p?mode=listener&latency=${ms * 1000}&timeout=2000000"
        }
        bitrate.progress = settings.bitrateMbps - 2
        bitrate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = updateLabels()
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        latency.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateLabels()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        port.doAfterTextChanged { updateLabels() }
        updateLabels()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Stream settings")
            .setView(view)
            // Set below, so the dialog stays open when the address or port is wrong.
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typed = host.text.toString()
            // Empty means "find OBS automatically"; while live, keep streaming to the current address.
            val address = if (typed.isBlank()) (if (engine.isLive) settings.host else "") to null
            else StreamSettings.parseAddress(typed)
            if (address == null) {
                host.error = "Type the OBS computer's IP address, like 192.168.1.20"
                return@setOnClickListener
            }
            // A port typed into the address box (192.168.1.20:9710) wins over the port box.
            val portNumber = address.second ?: port.text.toString().trim().ifEmpty { "9710" }.toIntOrNull()
            if (portNumber == null || portNumber !in StreamSettings.PORT_RANGE) {
                port.error = "Use a port between ${StreamSettings.PORT_RANGE.first} and ${StreamSettings.PORT_RANGE.last}"
                return@setOnClickListener
            }
            dialog.dismiss()
            run {
                val newSettings = StreamSettings(
                    host = address.first,
                    port = portNumber,
                    resolution = Resolution.entries[resolution.selectedItemPosition],
                    fps = StreamSettings.FPS_CHOICES[fps.selectedItemPosition],
                    codec = Codec.entries[codec.selectedItemPosition],
                    bitrateMbps = bitrate.progress + 2,
                    latency = LatencyPreset.entries[latency.selectedItemPosition],
                    adaptiveBitrate = adaptive.isChecked,
                    silenceWhileLive = silence.isChecked,
                )
                val old = settings
                settings = newSettings
                settings.save(this)
                clampShutterToFps()
                if (engine.isLive && old.silenceWhileLive != settings.silenceWhileLive) {
                    StreamService.start(this, settings.silenceWhileLive)
                }
                if (engine.isLive && streamChanged(old, settings)) {
                    // Restart the stream with the new settings; OBS covers the short gap.
                    bindToObsNetwork()
                    engine.goLive(settings)
                    toast("Restarting stream with new settings")
                } else {
                    toast("Saved: ${settingsSummary()}")
                }
                render(lastStatus)
            }
        }
    }

    /** A shutter slower than one frame would drag the frame rate down, so use the slowest that fits. */
    private fun clampShutterToFps() {
        val ns = controls.manualExposureNs ?: return
        if (ns <= 1_000_000_000L / settings.fps) return
        shutterChoices().firstOrNull()?.let { set(controls.copy(manualExposureNs = it)) }
    }

    private fun settingsSummary() = with(settings) {
        "${resolution.label} ${fps}fps ${if (codec == Codec.HEVC) "HEVC" else "H.264"} · $bitrateMbps Mbps"
    }

    /** Silence is handled by the service, so only these need the stream restarted. */
    private fun streamChanged(a: StreamSettings, b: StreamSettings) =
        a.copy(silenceWhileLive = b.silenceWhileLive) != b

    private fun showNetworkCheck() {
        val r = NetworkCheck.run(this)
        val message = buildString {
            append(r.summary)
            if (r.suggestedMbps > 0) append("\n\nSuggested quality: up to ${r.suggestedMbps} Mbps (you chose ${settings.bitrateMbps}).")
            r.warning?.let { append("\n\n").append(it) }
            if (r.warning == null) append("\n\nLooks good for streaming.")
        }
        AlertDialog.Builder(this)
            .setTitle("Network check: ${r.kind}")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .apply {
                if (r.suggestedMbps in 1 until settings.bitrateMbps) {
                    setNeutralButton("Use ${r.suggestedMbps} Mbps") { _, _ ->
                        settings = settings.copy(bitrateMbps = r.suggestedMbps).also { it.save(this@MainActivity) }
                        render(lastStatus)
                    }
                }
            }
            .show()
    }

    // endregion

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private class Views(a: AppCompatActivity) {
        val preview = a.findViewById<android.view.SurfaceView>(R.id.preview)
        val dim = a.findViewById<View>(R.id.dim)
        val link = a.findViewById<TextView>(R.id.link)
        val video = a.findViewById<TextView>(R.id.video)
        val bitrate = a.findViewById<TextView>(R.id.bitrate)
        val net = a.findViewById<TextView>(R.id.net)
        val phone = a.findViewById<TextView>(R.id.phone)
        val warning = a.findViewById<TextView>(R.id.warning)
        val live = a.findViewById<Button>(R.id.live)
        val settings = a.findViewById<Button>(R.id.settings)
        val check = a.findViewById<Button>(R.id.check)
        val dimToggle = a.findViewById<Button>(R.id.dimToggle)
        val sliderPanel = a.findViewById<View>(R.id.sliderPanel)
        val sliderLabel = a.findViewById<TextView>(R.id.sliderLabel)
        val slider = a.findViewById<SeekBar>(R.id.slider)
        val sliderAuto = a.findViewById<Button>(R.id.sliderAuto)
        val aeLock = a.findViewById<Button>(R.id.aeLock)
        val ev = a.findViewById<Button>(R.id.ev)
        val iso = a.findViewById<Button>(R.id.iso)
        val shutter = a.findViewById<Button>(R.id.shutter)
        val wb = a.findViewById<Button>(R.id.wb)
        val wbLock = a.findViewById<Button>(R.id.wbLock)
        val focus = a.findViewById<Button>(R.id.focus)
        val zoom = a.findViewById<Button>(R.id.zoom)
        val natural = a.findViewById<Button>(R.id.natural)
    }
}
