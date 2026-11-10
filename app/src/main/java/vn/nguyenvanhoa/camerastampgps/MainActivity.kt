package vn.nguyenvanhoa.camerastampgps

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Address
import android.media.AudioManager
import android.media.ToneGenerator
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.annotation.RequiresApi
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: StampOverlayView
    private lateinit var btnFlash: TextView
    private lateinit var tvGps: TextView
    private lateinit var thumb: ImageView
    private lateinit var shutter: View

    private lateinit var btnTimer: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var focusRing: View
    private lateinit var sbExposure: SeekBar
    private lateinit var tvExp: TextView
    private lateinit var sbZoom: SeekBar
    private lateinit var tvZoom: TextView

    private var camera: Camera? = null
    private var userZooming = false
    private var timerSec = 0
    private var countdown: CountDownTimer? = null
    private var tone: ToneGenerator? = null

    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private lateinit var cameraExecutor: ExecutorService
    private val geoExecutor = Executors.newSingleThreadExecutor()
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var locationManager: LocationManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var lastLocation: Location? = null
    @Volatile private var lastAddress: String = ""
    @Volatile private var mslAltitude: Double? = null
    private val altBusy = AtomicBoolean(false)
    private var geocodedAt: Location? = null
    private var lastGeoAt = 0L
    private val geoBusy = AtomicBoolean(false)
    private val netExecutor = Executors.newSingleThreadExecutor()

    // Giờ GPS: độ lệch (ms) giữa giờ vệ tinh và giờ máy, cùng thời điểm đo
    @Volatile private var gpsOffsetMs: Long? = null
    @Volatile private var gpsOffsetAt: Long = 0L

    private var options = StampOptions()
    private var logo: Bitmap? = null
    private var lastSavedUri: Uri? = null
    private var busy = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.CAMERA] == true) startCamera()
            else toast("Cần quyền Camera để chụp ảnh")
            if (hasLocationPerm()) startLocation()
            else toast("Chưa cấp quyền vị trí: ảnh sẽ không có tọa độ")
        }

    private val pickLogo =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) saveLogo(uri)
        }

    private val ticker = object : Runnable {
        override fun run() {
            refreshOverlay()
            handler.postDelayed(this, 1000L)
        }
    }

    private val locCallback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let { onNewLocation(it) }
        }
    }

    /** Nhận vị trí trực tiếp từ chip GPS để lấy giờ vệ tinh (UTC). */
    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L
            gpsOffsetMs = l.time + ageMs - System.currentTimeMillis()
            gpsOffsetAt = SystemClock.elapsedRealtime()
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val rot = when (o) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rot
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        btnFlash = findViewById(R.id.btnFlash)
        tvGps = findViewById(R.id.tvGps)
        thumb = findViewById(R.id.thumb)
        shutter = findViewById(R.id.shutter)
        btnTimer = findViewById(R.id.btnTimer)
        tvCountdown = findViewById(R.id.tvCountdown)
        focusRing = findViewById(R.id.focusRing)
        sbExposure = findViewById(R.id.sbExposure)
        tvExp = findViewById(R.id.tvExp)
        sbZoom = findViewById(R.id.sbZoom)
        tvZoom = findViewById(R.id.tvZoom)

        prefs = getSharedPreferences("stamp", MODE_PRIVATE)
        options = loadOptions()
        cameraExecutor = Executors.newSingleThreadExecutor()
        fused = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        loadLogo()

        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        btnFlash.setOnClickListener { toggleFlash() }
        findViewById<View>(R.id.btnSwitch).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }
        shutter.setOnClickListener { onShutter() }
        timerSec = prefs.getInt("timer", 0)
        updateTimerLabel()
        btnTimer.setOnClickListener { cycleTimer() }
        setupCameraControls()
        thumb.setOnClickListener { openLast() }

        val needed = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (needed.all { granted(it) }) startCamera() else permLauncher.launch(needed)
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
        handler.post(ticker)
        if (hasLocationPerm()) startLocation()
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
        handler.removeCallbacks(ticker)
        fused.removeLocationUpdates(locCallback)
        try { locationManager.removeUpdates(gpsListener) } catch (_: Exception) {}
        cancelCountdown()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        geoExecutor.shutdown()
        netExecutor.shutdown()
        tone?.release()
    }

    /** Nút âm lượng = nút chụp. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event?.repeatCount == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- Camera ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setFlashMode(flashMode)
                .build()
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                camera?.cameraInfo?.zoomState?.removeObservers(this)
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, imageCapture)
                onCameraBound()
            } catch (e: Exception) {
                toast("Không mở được camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------- Độ sáng / Zoom / Lấy nét ----------------

    private fun onCameraBound() {
        val cam = camera ?: return
        val es = cam.cameraInfo.exposureState
        if (es.isExposureCompensationSupported) {
            val r = es.exposureCompensationRange
            sbExposure.isEnabled = true
            sbExposure.min = r.lower
            sbExposure.max = r.upper
            sbExposure.progress = 0
            cam.cameraControl.setExposureCompensationIndex(0)
        } else {
            sbExposure.isEnabled = false
        }
        updateExpLabel(0)
        cam.cameraInfo.zoomState.observe(this) { z ->
            tvZoom.text = String.format(Locale.US, "🔍 %.1fx", z.zoomRatio)
            if (!userZooming) sbZoom.progress = (z.linearZoom * 100).roundToInt()
        }
    }

    private fun updateExpLabel(index: Int) {
        val step = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0f
        val ev = index * step
        tvExp.text = if (index == 0) "☀ 0 EV" else String.format(Locale.US, "☀ %+.1f EV", ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCameraControls() {
        sbExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                camera?.cameraControl?.setExposureCompensationIndex(p)
                updateExpLabel(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        findViewById<View>(R.id.btnExpReset).setOnClickListener {
            sbExposure.progress = 0
            camera?.cameraControl?.setExposureCompensationIndex(0)
            updateExpLabel(0)
        }

        sbZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) camera?.cameraControl?.setLinearZoom(p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar) { userZooming = true }
            override fun onStopTrackingTouch(s: SeekBar) { userZooming = false }
        })
        findViewById<View>(R.id.btnZoomReset).setOnClickListener { camera?.cameraControl?.setZoomRatio(1f) }

        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val z = cam.cameraInfo.zoomState.value ?: return false
                val target = (z.zoomRatio * detector.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio)
                cam.cameraControl.setZoomRatio(target)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
        })
        previewView.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (!scale.isInProgress && e.pointerCount == 1) tap.onTouchEvent(e)
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)

        focusRing.animate().cancel()
        focusRing.x = x - focusRing.width / 2f
        focusRing.y = y - focusRing.height / 2f
        focusRing.alpha = 1f
        focusRing.scaleX = 1.4f
        focusRing.scaleY = 1.4f
        focusRing.visibility = View.VISIBLE
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(1200).setDuration(400).start()
        }.start()
    }

    // ---------------- Hẹn giờ chụp ----------------

    private fun cycleTimer() {
        timerSec = when (timerSec) { 0 -> 2; 2 -> 5; 5 -> 10; else -> 0 }
        prefs.edit().putInt("timer", timerSec).apply()
        updateTimerLabel()
    }

    private fun updateTimerLabel() {
        btnTimer.text = if (timerSec == 0) "⏱ Tắt" else "⏱ ${timerSec}s"
        btnTimer.setTextColor(if (timerSec == 0) 0xFFFFFFFF.toInt() else 0xFFFFC400.toInt())
    }

    private fun onShutter() {
        if (countdown != null) {
            cancelCountdown()
            toast("Đã hủy hẹn giờ")
            return
        }
        if (busy) return
        if (timerSec == 0) { takePhoto(); return }
        tvCountdown.visibility = View.VISIBLE
        countdown = object : CountDownTimer(timerSec * 1000L, 1000L) {
            override fun onTick(ms: Long) {
                tvCountdown.text = ((ms + 999) / 1000).toString()
                beep(false)
            }
            override fun onFinish() {
                countdown = null
                tvCountdown.visibility = View.GONE
                beep(true)
                takePhoto()
            }
        }.start()
    }

    private fun cancelCountdown() {
        countdown?.cancel()
        countdown = null
        tvCountdown.visibility = View.GONE
    }

    private fun beep(last: Boolean) {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tone?.startTone(if (last) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (_: Exception) {
        }
    }

    private fun toggleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        btnFlash.text = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> "⚡ Bật"
            ImageCapture.FLASH_MODE_AUTO -> "⚡ Tự động"
            else -> "⚡ Tắt"
        }
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        if (busy) return
        busy = true
        shutter.alpha = 0.4f
        val data = currentStamp()
        val logoSnap = logo

        ic.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rot = image.imageInfo.rotationDegrees
                    val raw = image.use { it.toBitmap() }
                    val stamped = StampRenderer.drawOnBitmap(rotate(raw, rot), data, logoSnap)
                    val uri = saveToGallery(stamped, data)
                    val tw = 240
                    val th = max(1, (tw.toFloat() * stamped.height / stamped.width).toInt())
                    val small = Bitmap.createScaledBitmap(stamped, tw, th, true)
                    runOnUiThread {
                        lastSavedUri = uri
                        thumb.setImageBitmap(small)
                        toast("Đã lưu: Pictures/CameraStampGPS")
                    }
                } catch (e: Exception) {
                    runOnUiThread { toast("Lỗi lưu ảnh: ${e.message}") }
                } finally {
                    runOnUiThread { busy = false; shutter.alpha = 1f }
                }
            }

            override fun onError(e: ImageCaptureException) {
                runOnUiThread {
                    busy = false
                    shutter.alpha = 1f
                    toast("Lỗi chụp: ${e.message}")
                }
            }
        })
    }

    private fun rotate(src: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return src
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out != src) src.recycle()
        return out
    }

    private fun saveToGallery(bmp: Bitmap, d: StampData): Uri {
        val name = "GPS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(d.time) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, d.time.time)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CameraStampGPS")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "CameraStampGPS"
                ).apply { mkdirs() }
                put(MediaStore.Images.Media.DATA, File(dir, name).absolutePath)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Không tạo được file ảnh")
        contentResolver.openOutputStream(uri)?.use {
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
        } ?: throw IllegalStateException("Không ghi được file ảnh")

        // EXIF: thời gian chụp + tọa độ GPS
        try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val t = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(d.time)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, t)
                exif.setAttribute(ExifInterface.TAG_DATETIME, t)
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "CameraStampGPS")
                d.location?.let { exif.setGpsInfo(it) }
                exif.saveAttributes()
            }
        } catch (_: Exception) {
            // EXIF không bắt buộc; dấu đã in trên ảnh
        }

        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
        return uri
    }

    private fun openLast() {
        val uri = lastSavedUri ?: return toast("Chưa có ảnh vừa chụp")
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Exception) {
            toast("Không mở được ứng dụng xem ảnh")
        }
    }

    // ---------------- Vị trí ----------------

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocationPerm()) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        fused.requestLocationUpdates(req, locCallback, Looper.getMainLooper())
        fused.lastLocation.addOnSuccessListener { l ->
            if (l != null && lastLocation == null) onNewLocation(l)
        }
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper()
                )
            } catch (_: Exception) {
                // Máy không có GPS provider: bỏ qua giờ vệ tinh
            }
        }
    }

    private fun onNewLocation(l: Location) {
        lastLocation = l
        updateMslAltitude(l)
        val g = geocodedAt
        val now = SystemClock.elapsedRealtime()
        val moved = g?.distanceTo(l) ?: Float.MAX_VALUE
        if (moved > 300f || (moved > 25f && now - lastGeoAt > 5000L)) {
            if (geoBusy.compareAndSet(false, true)) {
                geocodedAt = l
                lastGeoAt = now
                geocode(l)
            }
        }
        refreshOverlay()
    }

    /** Độ cao so với mực nước biển. Android 14+ mới có; máy cũ trả về null (ẩn dòng độ cao). */
    private fun updateMslAltitude(l: Location) {
        if (Build.VERSION.SDK_INT < 34) { mslAltitude = null; return }
        updateMslAltitude34(l)
    }

    @RequiresApi(34)
    private fun updateMslAltitude34(l: Location) {
        if (l.hasMslAltitude()) { mslAltitude = l.mslAltitudeMeters; return }
        if (!l.hasAltitude() || !altBusy.compareAndSet(false, true)) return
        val copy = Location(l)
        geoExecutor.execute {
            try {
                AltitudeConverter().addMslAltitudeToLocation(this, copy)
                if (copy.hasMslAltitude()) mslAltitude = copy.mslAltitudeMeters
            } catch (_: Exception) {
            } finally {
                altBusy.set(false)
            }
        }
    }

    /**
     * Tra địa chỉ: Google Geocoder (xã/huyện/tỉnh) + tên đường.
     * Khi Google không có tên đường hoặc xe đang chạy nhanh (> ~54 km/h, thường là cao tốc/quốc lộ),
     * lấy tên đường từ OpenStreetMap (có tên cao tốc, quốc lộ, số hiệu CT/QL).
     */
    private fun geocode(l: Location) {
        netExecutor.execute {
            try {
                var list: List<Address> = emptyList()
                if (Geocoder.isPresent()) {
                    try {
                        @Suppress("DEPRECATION")
                        list = Geocoder(this, Locale("vi", "VN"))
                            .getFromLocation(l.latitude, l.longitude, 5) ?: emptyList()
                    } catch (_: Exception) {
                    }
                }
                var road = findRoad(list)
                val fast = l.hasSpeed() && l.speed > 15f
                if (road == null || fast) {
                    osmRoad(l)?.let { road = it }
                }
                val addr = buildAddress(road, list.firstOrNull())
                if (addr.isNotBlank()) runOnUiThread { lastAddress = addr; refreshOverlay() }
            } catch (_: Exception) {
                // Không có mạng: chỉ hiện tọa độ
            } finally {
                geoBusy.set(false)
            }
        }
    }

    private val plusRe = Regex("^[23456789CFGHJMPQRVWX]{2,8}\\+[23456789CFGHJMPQRVWX]{0,3}\\s*", RegexOption.IGNORE_CASE)
    private val roadRe = Regex(
        "(cao tốc|quốc lộ|tỉnh lộ|đường|phố|đại lộ|xa lộ|cầu|hầm|QL\\s*\\d|ĐT\\s*\\d|CT\\.?\\s*\\d|AH\\s*\\d)",
        RegexOption.IGNORE_CASE
    )

    private fun clean(s: String?) = s?.replace(plusRe, "")?.trim().orEmpty()

    private fun findRoad(list: List<Address>): String? {
        for (a in list) {
            val t = clean(a.thoroughfare)
            if (t.isNotEmpty()) return listOf(clean(a.subThoroughfare), t).filter { it.isNotEmpty() }.joinToString(" ")
        }
        for (a in list) {
            val f = clean(a.featureName)
            if (f.isNotEmpty() && roadRe.containsMatchIn(f)) return f
        }
        return null
    }

    /** Ghép: tên đường, phường/xã, quận/huyện, tỉnh (bỏ Plus Code và "Việt Nam"). */
    private fun buildAddress(road: String?, a: Address?): String {
        val country = setOf("việt nam", "vietnam", "viet nam")
        val admin = if (a == null) emptyList() else {
            val comp = listOf(clean(a.subLocality), clean(a.locality), clean(a.subAdminArea), clean(a.adminArea))
                .filter { it.isNotEmpty() }
            comp.ifEmpty {
                (0..a.maxAddressLineIndex)
                    .flatMap { (a.getAddressLine(it) ?: "").split(",") }
                    .map { clean(it) }
                    .filter { it.isNotEmpty() }
            }
        }
        val parts = mutableListOf<String>()
        if (!road.isNullOrBlank()) parts += road
        parts += admin
        return parts.filter { it.lowercase() !in country }.distinct().joinToString(", ")
    }

    /** Tên đường từ OpenStreetMap (Nominatim). Gọi tối đa ~1 lần/5 giây. */
    private fun osmRoad(l: Location): String? = try {
        val url = URL(
            String.format(
                Locale.US,
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=17&namedetails=1&accept-language=vi",
                l.latitude, l.longitude
            )
        )
        val c = url.openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "CameraStampGPS/1.3 (personal use)")
        c.connectTimeout = 6000
        c.readTimeout = 6000
        val body = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        val j = JSONObject(body)
        val isRoad = j.optString("category") == "highway"
        var name = j.optJSONObject("address")?.optString("road").orEmpty()
        if (name.isEmpty() && isRoad) name = j.optString("name")
        val ref = if (isRoad) j.optJSONObject("namedetails")?.optString("ref").orEmpty() else ""
        when {
            name.isEmpty() && ref.isEmpty() -> null
            name.isEmpty() -> ref
            ref.isEmpty() || name.contains(ref, ignoreCase = true) -> name
            else -> "$name ($ref)"
        }
    } catch (_: Exception) {
        null
    }

    private fun hasLocationPerm() =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ---------------- Dấu ----------------

    private fun currentGpsTime(now: Long): Date? {
        val off = gpsOffsetMs ?: return null
        if (SystemClock.elapsedRealtime() - gpsOffsetAt > 60_000L) return null   // mất tín hiệu GPS > 60 giây
        return Date(now + off)
    }

    private fun currentStamp(): StampData {
        val now = System.currentTimeMillis()
        return StampData(
            title = prefs.getString("title", "") ?: "",
            note = prefs.getString("note", "") ?: "",
            time = Date(now),
            location = lastLocation,
            address = lastAddress,
            mslAltitude = mslAltitude,
            gpsTime = currentGpsTime(now),
            options = options
        )
    }

    private fun refreshOverlay() {
        overlay.update(currentStamp(), logo)
        val l = lastLocation
        tvGps.text = if (l == null) "GPS: đang dò..." else "GPS ±${l.accuracy.roundToInt()} m"
    }

    private fun loadOptions() = StampOptions(
        showAddress = prefs.getBoolean("showAddress", true),
        showCoords = prefs.getBoolean("showCoords", true),
        showAltitude = prefs.getBoolean("showAltitude", true),
        showGpsTime = prefs.getBoolean("showGpsTime", true),
        showNote = prefs.getBoolean("showNote", true),
        showLogo = prefs.getBoolean("showLogo", true),
        sizePct = prefs.getInt("sizePct", 100),
        bgOpacityPct = prefs.getInt("bgPct", 65)
    )

    private fun showSettings() {
        val dp = resources.displayMetrics.density
        val pad = (18 * dp).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val scroll = ScrollView(this).apply { addView(box) }

        fun header(t: String) = TextView(this).apply {
            text = t
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFC400.toInt())
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        }

        val etTitle = EditText(this).apply {
            hint = "VD: KFC Việt Nam – Kho lạnh"
            setText(prefs.getString("title", ""))
        }
        val etNote = EditText(this).apply {
            hint = "VD: Kiểm tra nhận hàng"
            setText(prefs.getString("note", ""))
        }

        val toggles = listOf(
            "showAddress" to "Địa chỉ",
            "showCoords" to "Tọa độ & sai số",
            "showAltitude" to "Độ cao (mực nước biển, Android 14+)",
            "showGpsTime" to "Giờ vệ tinh GPS",
            "showNote" to "Ghi chú",
            "showLogo" to "Logo"
        ).map { (key, label) ->
            key to CheckBox(this).apply { text = label; isChecked = prefs.getBoolean(key, true) }
        }

        fun slider(label: String, min: Int, max: Int, value: Int): Pair<TextView, SeekBar> {
            val tv = TextView(this).apply { text = "$label: $value%" }
            val sb = SeekBar(this).apply {
                this.min = min
                this.max = max
                progress = value
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { tv.text = "$label: $p%" }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            return tv to sb
        }
        val (tvSize, sbSize) = slider("Cỡ dấu", 50, 150, prefs.getInt("sizePct", 100))
        val (tvBg, sbBg) = slider("Độ đậm nền", 20, 95, prefs.getInt("bgPct", 65))

        val btnLogo = Button(this).apply {
            text = if (logo == null) "Chọn logo" else "Đổi logo"
            setOnClickListener {
                pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        val btnRemove = Button(this).apply {
            text = "Xóa logo"
            setOnClickListener {
                File(filesDir, "logo.png").delete()
                logo = null
                refreshOverlay()
                toast("Đã xóa logo")
            }
        }

        box.addView(header("Nội dung"))
        box.addView(TextView(this).apply { text = "Tên dấu / đơn vị" })
        box.addView(etTitle)
        box.addView(TextView(this).apply { text = "Ghi chú" })
        box.addView(etNote)
        box.addView(header("Hiển thị trên dấu"))
        toggles.forEach { box.addView(it.second) }
        box.addView(header("Kích thước & nền"))
        box.addView(tvSize); box.addView(sbSize)
        box.addView(tvBg); box.addView(sbBg)
        box.addView(header("Logo"))
        box.addView(btnLogo)
        box.addView(btnRemove)

        AlertDialog.Builder(this)
            .setTitle("Cài đặt dấu")
            .setView(scroll)
            .setPositiveButton("Lưu") { _, _ ->
                val e = prefs.edit()
                    .putString("title", etTitle.text.toString().trim())
                    .putString("note", etNote.text.toString().trim())
                    .putInt("sizePct", sbSize.progress)
                    .putInt("bgPct", sbBg.progress)
                toggles.forEach { (k, cb) -> e.putBoolean(k, cb.isChecked) }
                e.apply()
                options = loadOptions()
                refreshOverlay()
            }
            .setNeutralButton("Mặc định") { _, _ ->
                val e = prefs.edit().putInt("sizePct", 100).putInt("bgPct", 65)
                toggles.forEach { (k, _) -> e.putBoolean(k, true) }
                e.apply()
                options = loadOptions()
                refreshOverlay()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun saveLogo(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return toast("Không đọc được ảnh logo")
            FileOutputStream(File(filesDir, "logo.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            logo = bmp
            refreshOverlay()
            toast("Đã cập nhật logo")
        } catch (e: Exception) {
            toast("Lỗi logo: ${e.message}")
        }
    }

    private fun loadLogo() {
        val f = File(filesDir, "logo.png")
        logo = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
