package cz.jonas.bakaplus

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var splashOverlay: View
    private var isPageLoaded = false
    private var isOfflineErrorShown = false

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    private val appUrl = "https://baka.hyperlandia.cz"

    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (content != null) {
                        val encodedContent = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        webView.post {
                            webView.evaluateJavascript("if(window.onBackupLoaded) window.onBackupLoaded('$encodedContent');", null)
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Chyba při čtení souboru", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        splashOverlay = findViewById(R.id.splashOverlay)
        val rootLayout: View = findViewById(R.id.main)

        createNotificationChannel()

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            insets
        }

        val settings = webView.settings
        settings.javaScriptEnabled = true
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidApp")
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = true

        val defaultUserAgent = settings.userAgentString
        val appVersion = packageManager.getPackageInfo(packageName, 0).versionName
        settings.userAgentString = "$defaultUserAgent BakaPlus/$appVersion"

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (isNetworkAvailable()) {
            settings.cacheMode = WebSettings.LOAD_DEFAULT
        } else {
            settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        }

        setupNetworkCallback()

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            try {
                if (url.startsWith("blob:")) {
                    val jsCode = """
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function(e) {
                            if (this.status == 200) {
                                var blob = this.response;
                                var reader = new FileReader();
                                reader.readAsDataUrl(blob);
                                reader.onloadend = function() {
                                    var base64data = reader.result;
                                    Android.processBase64Data(base64data, '$contentDisposition', '$mimetype');
                                }
                            }
                        };
                        xhr.send();
                    """.trimIndent()
                    webView.evaluateJavascript(jsCode, null)
                } else if (url.startsWith("data:")) {

                } else {
                    val request = DownloadManager.Request(Uri.parse(url))
                    request.setMimeType(mimetype)
                    val cookies = android.webkit.CookieManager.getInstance().getCookie(url)
                    request.addRequestHeader("cookie", cookies)
                    request.addRequestHeader("User-Agent", userAgent)
                    request.setDescription("Stahování rozvrhu")
                    request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                    request.allowScanningByMediaScanner()
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))

                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(applicationContext, "Stahování začalo", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(applicationContext, "Chyba při stahování", Toast.LENGTH_LONG).show()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != "about:blank" && !isOfflineErrorShown) {
                    isPageLoaded = true

                    splashOverlay.animate()
                        .alpha(0f)
                        .setDuration(400)
                        .withEndAction { splashOverlay.visibility = View.GONE }

                    checkNotificationPermission()
                    injectFirebaseToken()
                    handleIntent(intent)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)

                if (request?.isForMainFrame == true) {
                    isOfflineErrorShown = true
                    val offlineHtml = """
                        <html>
                            <body style="background-color: #050505; color: white; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; text-align: center; padding: 20px;">
                                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2.5" style="background: rgba(239, 68, 68, 0.1); padding: 15px; border-radius: 50px;"><path d="M10.6 10.6a5.5 5.5 0 0 1 7.7 0M14.1 14.1a.5.5 0 0 1 .7 0M7 7a10.5 10.5 0 0 1 14.8 0M3 3l18 18"/></svg>
                                <h2 style="margin-top: 25px; font-weight: 900; text-transform: uppercase; letter-spacing: 1px;">Spojení ztraceno</h2>
                                <p style="color: rgba(255,255,255,0.4); font-size: 11px; font-weight: bold; text-transform: uppercase; letter-spacing: 1.5px; margin-top: 5px;">Aplikace nemá uložená data.<br>Čekám na připojení k internetu...</p>
                            </body>
                        </html>
                    """.trimIndent()
                    webView.loadDataWithBaseURL(appUrl, offlineHtml, "text/html", "UTF-8", null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.contains("github.com") && url.contains("/releases")) {
                    val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    view?.context?.startActivity(openIntent)
                    return true
                }
                return false
            }
        }

        webView.loadUrl(appUrl)

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack() && !isOfflineErrorShown) {
                    webView.goBack()
                } else {
                    webView.evaluateJavascript("if (window.onAndroidBackPressed) window.onAndroidBackPressed();", null)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (isPageLoaded) {
            handleIntent(intent)
        }
    }

    private fun setupNetworkCallback() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                    if (isOfflineErrorShown) {
                        isOfflineErrorShown = false
                        webView.loadUrl(appUrl)
                    } else if (isPageLoaded) {
                        webView.evaluateJavascript("if(window.silentRefresh) window.silentRefresh();", null)
                    }
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                }
            }
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val actNw = connectivityManager.getNetworkCapabilities(network) ?: return false
        return actNw.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    private fun handleIntent(intent: Intent?) {
        val targetTab = intent?.getStringExtra("targetTab")
        val targetSubject = intent?.getStringExtra("targetSubject") ?: ""
        val targetAccountId = intent?.getStringExtra("accountId") ?: ""

        if (targetTab != null) {
            val jsCode = """
                (function() {
                    var attempts = 0;
                    function checkAndOpen() {
                        if (window.openTabFromNotification) {
                            window.openTabFromNotification('$targetTab', '$targetSubject', '$targetAccountId');
                        } else if (attempts < 50) {
                            attempts++;
                            setTimeout(checkAndOpen, 100);
                        }
                    }
                    checkAndOpen();
                })();
            """.trimIndent()

            webView.post {
                webView.evaluateJavascript(jsCode, null)
            }

            intent.removeExtra("targetTab")
            intent.removeExtra("targetSubject")
            intent.removeExtra("accountId")
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    inner class WebAppInterface(private val context: Context) {

        @android.webkit.JavascriptInterface
        fun closeApp() {
            runOnUiThread {
                finish()
            }
        }

        @android.webkit.JavascriptInterface
        fun importBackup() {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            importBackupLauncher.launch(intent)
        }

        @android.webkit.JavascriptInterface
        fun openUrl(url: String) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @android.webkit.JavascriptInterface
        fun downloadFile(filename: String, mimeType: String, base64Data: String) {
            try {
                val cleanBase64 = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
                val decodedBytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)

                val isImage = mimeType.startsWith("image/")
                val directoryName = if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "$directoryName/BakaPlus")
                    }
                    val uriCollection = if (isImage) {
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    } else {
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    }

                    val uri = resolver.insert(uriCollection, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(decodedBytes)
                        }
                    }
                } else {
                    val dir = java.io.File(Environment.getExternalStoragePublicDirectory(directoryName), "BakaPlus")
                    if (!dir.exists()) dir.mkdirs()
                    java.io.File(dir, filename).writeBytes(decodedBytes)
                }

                runOnUiThread {
                    val folderName = if (isImage) "Galerie" else "Stažených"
                    Toast.makeText(context, "Uloženo do $folderName (BakaPlus)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(context, "Chyba při ukládání souboru", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun getDeviceId(): String {
            return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                ?: "web_fallback"
        }

        @android.webkit.JavascriptInterface
        fun syncSettings(jsonStr: String) {
            try {
                val json = JSONObject(jsonStr)
                val prefs = getSharedPreferences("BakaPlusPrefs", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putBoolean("notifMarks", json.optBoolean("notifMarks", true))
                    putBoolean("notifTasks", json.optBoolean("notifTasks", true))
                    putBoolean("notifMessages", json.optBoolean("notifMessages", true))
                    putFloat(
                        "notifWeightThreshold",
                        json.optDouble("notifWeightThreshold", 1.0).toFloat()
                    )
                    putBoolean("notifQuietHours", json.optBoolean("notifQuietHours", false))
                    apply()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @android.webkit.JavascriptInterface
        fun isBiometricAvailable(): Boolean {
            val biometricManager = BiometricManager.from(context)
            return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
        }

        @android.webkit.JavascriptInterface
        fun startAppUpdate(apkUrl: String) {
            runOnUiThread {
                try {
                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val fileName = "bakaplus_update.apk"

                    val existingFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                    if (existingFile.exists()) {
                        existingFile.delete()
                    }

                    val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                        setMimeType("application/vnd.android.package-archive")
                        setTitle("Aktualizace BakaPlus")
                        setDescription("Stahuji novou verzi...")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    }

                    val downloadId = dm.enqueue(request)

                    val handler = Handler(Looper.getMainLooper())
                    val runnable = object : Runnable {
                        override fun run() {
                            val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                            if (cursor.moveToFirst()) {
                                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                                val status = cursor.getInt(statusIndex)

                                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                    webView.evaluateJavascript("if(window.onUpdateProgress) window.onUpdateProgress(100, 'Příprava k instalaci...');", null)
                                    installApk(fileName)
                                    cursor.close()
                                    return
                                } else if (status == DownloadManager.STATUS_FAILED) {
                                    webView.evaluateJavascript("if(window.onUpdateError) window.onUpdateError('Stahování selhalo');", null)
                                    cursor.close()
                                    return
                                }

                                val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                                val downloaded = cursor.getInt(bytesDownloadedIndex)
                                val total = cursor.getInt(bytesTotalIndex)

                                if (total > 0) {
                                    val progress = (downloaded * 100L) / total
                                    webView.evaluateJavascript("if(window.onUpdateProgress) window.onUpdateProgress($progress, 'Stahování...');", null)
                                }
                            }
                            cursor.close()
                            handler.postDelayed(this, 500)
                        }
                    }
                    handler.post(runnable)

                } catch (e: Exception) {
                    webView.evaluateJavascript("if(window.onUpdateError) window.onUpdateError('Nelze spustit stahování');", null)
                }
            }
        }

        private fun installApk(fileName: String) {
            try {
                val apkFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    fileName
                )
                if (!apkFile.exists()) return

                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    apkFile
                )

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                webView.evaluateJavascript("if(window.onUpdateError) window.onUpdateError('Nelze spustit instalační balíček. Máte povoleny neznámé zdroje?');", null)
            }
        }

        @android.webkit.JavascriptInterface
        fun authenticate() {
            runOnUiThread {
                val executor: Executor = ContextCompat.getMainExecutor(this@MainActivity)
                val biometricPrompt = BiometricPrompt(
                    this@MainActivity, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            webView.evaluateJavascript(
                                "if(window.unlockAppSuccess) window.unlockAppSuccess();",
                                null
                            )
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence
                        ) {
                            super.onAuthenticationError(errorCode, errString)
                            webView.evaluateJavascript(
                                "if(window.unlockAppError) window.unlockAppError('${errString}');",
                                null
                            )
                        }
                    })

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Odemknout BakaPlus")
                    .setSubtitle("Pro přístup k aplikaci ověřte svou identitu")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()

                biometricPrompt.authenticate(promptInfo)
            }
        }
    }

    private fun injectFirebaseToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result

                val jsCode = """
                    (function() {
                        var attempts = 0;
                        function checkAndRegister() {
                            if (window.registerFcmToken) {
                                window.registerFcmToken('$token');
                            } else if (attempts < 50) {
                                attempts++;
                                setTimeout(checkAndRegister, 100);
                            }
                        }
                        checkAndRegister();
                    })();
                """.trimIndent()

                webView.post {
                    webView.evaluateJavascript(jsCode, null)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            notificationManager.deleteNotificationChannel("baka_notifications")

            val channelGrades = NotificationChannel(
                "baka_grades",
                "Známky",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikace na nové známky"
            }

            val channelHomeworks = NotificationChannel(
                "baka_homeworks",
                "Domácí úkoly",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Upozornění na nové domácí úkoly"
            }

            val channelTimetable = NotificationChannel(
                "baka_timetable",
                "Suplování a rozvrh",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Změny v rozvrhu a suplování"
            }
            val channelKomens = NotificationChannel(
                "baka_messages",
                "Komens",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Zprávy v Komens"
            }

            notificationManager.createNotificationChannels(
                listOf(channelGrades, channelHomeworks, channelTimetable, channelKomens)
            )
        }
    }
}