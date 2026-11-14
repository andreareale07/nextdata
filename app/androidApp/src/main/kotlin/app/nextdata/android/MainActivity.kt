package app.nextdata.android

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import app.nextdata.bridge.JniEngine
import app.nextdata.bridge.NativeLoader
import app.nextdata.engine.Engine
import app.nextdata.engine.FakeEngine
import app.nextdata.engine.UnavailableEngine
import app.nextdata.platform.AppEnvironment
import app.nextdata.platform.DownloadTarget
import app.nextdata.platform.PlatformFiles
import app.nextdata.platform.PlatformKind
import app.nextdata.platform.SecretProtection
import app.nextdata.platform.UploadSelection
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ErrorCodes
import app.nextdata.state.AppController
import app.nextdata.ui.NextDataApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/** Forwards to the file adapter of the Activity currently on screen (the controller outlives Activities). */
class FilesHolder : PlatformFiles {
    @Volatile
    var delegate: AndroidFiles? = null

    private fun d() = delegate ?: error("no activity")
    override suspend fun pickUploadFiles(onPicked: () -> Unit): UploadSelection? = d().pickUploadFiles(onPicked)
    override suspend fun pickUploadFolder(onPicked: () -> Unit): UploadSelection? = d().pickUploadFolder(onPicked)
    override suspend fun chooseDownloadTarget(askEachTime: Boolean): DownloadTarget? = d().chooseDownloadTarget(askEachTime)
    override suspend fun openLocation(location: String): Boolean = d().openLocation(location)
    override suspend fun saveBytes(suggestedName: String, bytes: ByteArray): Boolean = d().saveBytes(suggestedName, bytes)
    override suspend fun pickBytes(extensions: List<String>): ByteArray? = d().pickBytes(extensions)
}

class NextDataApplication : Application() {
    val files = FilesHolder()
    private var controllerInstance: AppController? = null

    fun existingController(): AppController? = controllerInstance

    /** `useFake` (debug: `adb shell am start --ez fake true`) selects the in-memory engine. */
    @Synchronized
    fun controller(useFake: Boolean): AppController = controllerInstance ?: run {
        val env = AppEnvironment(
            dataDir = File(filesDir, "nextdata").absolutePath,
            secretStore = KeystoreSecretStore(File(noBackupFilesDir, "pmk.bin")),
            files = files,
            kind = PlatformKind.Android,
            secretProtection = SecretProtection.AndroidKeystore,
            appVersion = "1.0.0" + if (useFake) " (fake engine)" else "",
            reduceMotion = {
                // "Remove animations" / animator scale 0 in the system settings
                android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            },
        )
        AppController(env, createEngine(useFake), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)).also { controllerInstance = it }
    }

    private fun createEngine(useFake: Boolean): Engine {
        if (useFake) return FakeEngine(latencyMs = 150, transferStepDelayMs = 400, seedProfiles = true)
        return try {
            NativeLoader.ensureLoaded()
            JniEngine()
        } catch (e: Throwable) {
            UnavailableEngine(ErrInfo.of(ErrorCodes.NOT_INITIALIZED))
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var androidFiles: AndroidFiles

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as NextDataApplication
        androidFiles = AndroidFiles(this)
        app.files.delegate = androidFiles
        val controller = app.controller(intent?.getBooleanExtra("fake", false) == true)
        setContent {
            NextDataApp(controller)
        }
        // keep the process alive (foreground service) while transfers run, also when the user is in the file picker
        controller.scope.launch {
            controller.transfers.items.map { list -> list.count { it.status.isActive } }.distinctUntilChanged().collect { n ->
                if (n > 0) {
                    askNotificationPermissionOnce()
                    runCatching { TransferService.start(this@MainActivity) }
                }
            }
        }
    }

    private val notifPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    private fun askNotificationPermissionOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("nextdata_files", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notif", false)) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean("asked_notif", true).apply()
        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() {
        super.onStart()
        // the system may have cut the connection while the app was in the background: probe and reconnect
        (application as NextDataApplication).existingController()?.onAppResumed()
    }

    override fun onDestroy() {
        val app = application as NextDataApplication
        if (app.files.delegate === androidFiles) app.files.delegate = null
        super.onDestroy()
    }
}
