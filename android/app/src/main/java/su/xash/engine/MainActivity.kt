<<<<<<< HEAD
package su.xash.engine

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import su.xash.engine.databinding.ActivityMainBinding
import su.xash.engine.model.AppUpdater
import su.xash.engine.util.CrashReports
import su.xash.engine.util.monospaceTextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        val defaultTheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "dynamic_sys"
        } else {
            "fixed_sys"
        }

        val themeMode = prefs.getString("app_theme", defaultTheme) ?: defaultTheme

        when {
            themeMode.contains("light") -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            themeMode.contains("dark") -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        if (themeMode == "legacy") {
            setTheme(R.style.Theme_App_Legacy)
        } else {
            setTheme(R.style.Theme_App_Fixed)
        }

        if (themeMode.startsWith("dynamic") && DynamicColors.isDynamicColorAvailable()) {
            DynamicColors.applyToActivityIfAvailable(this)
        }

        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment
        navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.libraryFragment,
                R.id.downloadPanelFragment,
                R.id.appSettingsFragment
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)

        binding.bottomNav.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.libraryFragment,
                R.id.downloadPanelFragment,
                R.id.appSettingsFragment -> {
                    binding.bottomNav.visibility = View.VISIBLE
                }
                else -> {
                    binding.bottomNav.visibility = View.GONE // bye bye
                }
            }
        }

        CrashReports.prune(this)
        showPendingCrashReport()

        checkForEngineUpdate()
    }

    private fun checkForEngineUpdate() {
        val prefs = getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
        val updater = AppUpdater(this)
        
        lifecycleScope.launch {
            val info = updater.checkForUpdate() ?: return@launch
            if (prefs.getInt(KEY_DISMISSED_BUILDNUM, -1) >= info.buildNum)
                return@launch

            showEngineUpdateDialog(updater, info.buildNum, info.commitHash, info.changelog, prefs)
        }
    }

    private fun showEngineUpdateDialog(
        updater: AppUpdater,
        remoteBuildNum: Int,
        remoteCommitHash: String,
        changelog: String?,
        prefs: android.content.SharedPreferences,
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_engine_update, null)

        val tvCurrentVersion = dialogView.findViewById<TextView>(R.id.tvCurrentVersion)
        val tvNewVersion = dialogView.findViewById<TextView>(R.id.tvNewVersion)
        val tvChangelogHeader = dialogView.findViewById<TextView>(R.id.tvChangelogHeader)
        val tvChangelog = dialogView.findViewById<TextView>(R.id.tvChangelog)
        val tvError = dialogView.findViewById<TextView>(R.id.tvError)
        val btnLater = dialogView.findViewById<MaterialButton>(R.id.btnLater)
        val btnDownload = dialogView.findViewById<MaterialButton>(R.id.btnDownload)

        val layoutProgress = dialogView.findViewById<View>(R.id.layoutProgress)
        val progressBar = dialogView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressBar)
        val tvProgressPercent = dialogView.findViewById<TextView>(R.id.tvProgressPercent)

        val currentHash = BuildConfig.GIT_HASH.ifEmpty { BuildConfig.VERSION_NAME }
        tvCurrentVersion.text = "b$currentHash"
        val targetHash = remoteCommitHash.ifEmpty { remoteBuildNum.toString() }
        tvNewVersion.text = "b$targetHash"

        if (!changelog.isNullOrEmpty()) {
            val formattedChangelog = changelog.replace("\n", "<br>")
            tvChangelog.visibility = View.VISIBLE
            tvChangelog.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(formattedChangelog, Html.FROM_HTML_MODE_COMPACT)
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(formattedChangelog)
            }
        } else {
            tvChangelog.visibility = View.GONE
        }

        var isDownloading = false
        var isInstalling = false

        fun resetUIState() {
            isDownloading = false
            isInstalling = false
            val hasPermission = updater.canInstall()
            val hasApk = updater.hasDownloadedApk()

            layoutProgress.visibility = View.GONE
            tvError.visibility = View.GONE

            if (!changelog.isNullOrEmpty()) {
                tvChangelogHeader.visibility = View.VISIBLE
                tvChangelog.visibility = View.VISIBLE
            }
            btnLater.visibility = View.VISIBLE
            btnDownload.visibility = View.VISIBLE
            btnDownload.isEnabled = true

            when {
                !hasPermission -> {
                    btnDownload.setText(R.string.engine_update_grant_permission)
                    btnDownload.setIconResource(R.drawable.ic_baseline_lock_24px)
                }
                hasApk -> {
                    btnDownload.setText(R.string.engine_update_install_now)
                    btnDownload.setIconResource(R.drawable.ic_baseline_mobile_arrow_down_24px)
                }
                else -> {
                    btnDownload.setText(R.string.engine_update_download)
                    btnDownload.setIconResource(R.drawable.ic_baseline_download_24px)
                }
            }
        }

        fun showInstallingState() {
            isInstalling = true
            tvError.visibility = View.GONE
            tvChangelogHeader.visibility = View.GONE
            tvChangelog.visibility = View.GONE
            btnLater.visibility = View.GONE
            btnDownload.visibility = View.GONE

            layoutProgress.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
            tvProgressPercent.text = ""
        }

        fun showError(errorMessage: String) {
            isDownloading = false
            isInstalling = false
            layoutProgress.visibility = View.GONE
            tvChangelogHeader.visibility = View.GONE
            tvChangelog.visibility = View.GONE

            tvError.text = errorMessage
            tvError.visibility = View.VISIBLE

            btnLater.visibility = View.VISIBLE
            btnDownload.visibility = View.VISIBLE
            btnDownload.isEnabled = true
            btnDownload.setText(R.string.engine_update_retry)
            btnDownload.setIconResource(R.drawable.ic_baseline_replay_24px)
        }

        resetUIState()

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val installReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AppUpdater.INSTALL_ACTION) {
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

                    when (status) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        }
                        PackageInstaller.STATUS_SUCCESS -> {
                        }
                        PackageInstaller.STATUS_FAILURE_ABORTED -> {
                            resetUIState()
                        }
                        else -> {
                            val errorMsg = message ?: getString(R.string.engine_update_install_failed)
                            showError(errorMsg)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(AppUpdater.INSTALL_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(installReceiver, filter)
        }

        val lifecycleObserver = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                if (!isDownloading && !isInstalling) {
                    resetUIState()
                }
            }
        }
        lifecycle.addObserver(lifecycleObserver)

        dialog.setOnDismissListener {
            lifecycle.removeObserver(lifecycleObserver)
            try {
                unregisterReceiver(installReceiver)
            } catch (_: Exception) {}
        }

        btnLater.setOnClickListener {
            if (!isDownloading && !isInstalling) {
                prefs.edit().putInt(KEY_DISMISSED_BUILDNUM, remoteBuildNum).apply()
                dialog.dismiss()
            }
        }

        btnDownload.setOnClickListener {
            if (!updater.canInstall()) {
                val packageIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:$packageName".toUri()
                )
                try {
                    startActivity(packageIntent)
                } catch (_: ActivityNotFoundException) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                    } catch (_: ActivityNotFoundException) {
                    }
                }
            } else if (updater.hasDownloadedApk()) {
                showInstallingState()
                try {
                    updater.installDownloadedApk()
                } catch (e: Exception) {
                    val installErrorMsg = "${getString(R.string.engine_update_install_failed)}: ${e.localizedMessage ?: e.message}"
                    showError(installErrorMsg)
                }
            } else {
                isDownloading = true
                tvError.visibility = View.GONE
                btnLater.visibility = View.GONE
                btnDownload.visibility = View.GONE

                tvChangelogHeader.visibility = View.GONE
                tvChangelog.visibility = View.GONE
                layoutProgress.visibility = View.VISIBLE

                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        updater.downloadAndInstall { current, total ->
                            runOnUiThread {
                                if (total > 0) {
                                    val percent = ((current * 100) / total).toInt()
                                    progressBar.isIndeterminate = false
                                    progressBar.progress = percent
                                    tvProgressPercent.text = "%$percent"
                                } else {
                                    progressBar.isIndeterminate = true
                                    tvProgressPercent.text = ""
                                }
                            }
                        }
                    }

                    result.fold(
                        onSuccess = {
                            showInstallingState()
                        },
                        onFailure = { error ->
                            val downloadErrorMsg = "${getString(R.string.engine_update_download_failed)}: ${error.localizedMessage ?: error.message}"
                            showError(downloadErrorMsg)
                        }
                    )
                }
            }
        }

        dialog.show()
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    private fun showPendingCrashReport() {
        val pending = CrashReports.pendingStacktrace(this)
        if (!pending.exists() || pending.length() == 0L)
            return

        val historyDir = CrashReports.historyDir(this).apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val entryDir = File(historyDir, "crash-$ts").apply { mkdirs() }

        moveOrCopy(pending, File(entryDir, CrashReports.STACKTRACE_NAME))
        moveOrCopy(CrashReports.pendingSysinfo(this), File(entryDir, CrashReports.SYSINFO_NAME))
        moveOrCopy(CrashReports.pendingIntent(this), File(entryDir, CrashReports.INTENT_NAME))
        moveOrCopy(CrashReports.pendingEngineLog(this), File(entryDir, CrashReports.ENGINELOG_NAME))

        val entry = CrashReports.Entry(entryDir)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_dialog_title)
            .setView(monospaceTextView(this, entry.summary()))
            .setPositiveButton(R.string.crash_share) { _, _ ->
                CrashReports.share(this, entry)
            }
            .setNegativeButton(R.string.crash_dismiss, null)
            .show()
    }

    private fun moveOrCopy(src: File, dst: File) {
        if (!src.exists())
            return

        if (src.renameTo(dst))
            return

        src.copyTo(dst, overwrite = true)
        src.delete()
    }

    companion object {
        private const val UPDATE_PREFS = "app_updater"
        private const val KEY_DISMISSED_BUILDNUM = "dismissed_buildnum"
    }
}
=======
package su.xash.engine

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import su.xash.engine.databinding.ActivityMainBinding
import su.xash.engine.model.AppUpdater
import su.xash.engine.util.CrashReports
import su.xash.engine.util.dialogContentView
import su.xash.engine.util.monospaceTextView
import su.xash.engine.util.showDownloadProgressDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
	private lateinit var binding: ActivityMainBinding
	private lateinit var appBarConfiguration: AppBarConfiguration
	private lateinit var navController: NavController

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

		setSupportActionBar(binding.toolbar)

		val navHostFragment =
			supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment
		navController = navHostFragment.navController
		appBarConfiguration = AppBarConfiguration(navController.graph)
		setupActionBarWithNavController(navController, appBarConfiguration)

		CrashReports.prune(this)
		showPendingCrashReport()

		checkForEngineUpdate()
	}

	private fun checkForEngineUpdate() {
		val prefs = getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
		val now = System.currentTimeMillis()
		val installedDays = BuildConfig.VERSION_CODE / 10000
		// Once the pending update is 2 weeks newer than the installed build, nag on
		// every launch: reuse the last known remote build to skip the interval gate.
		val maxNag = prefs.getInt(KEY_REMOTE_BUILDNUM, -1) - installedDays >= MAX_NAG_STALENESS_DAYS
		if (!maxNag && now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS)
			return

		val updater = AppUpdater(this)
		lifecycleScope.launch {
			val result = updater.checkForUpdate()
			prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
			if (result !is AppUpdater.UpdateCheck.Available)
				return@launch
			val info = result.info
			prefs.edit().putInt(KEY_REMOTE_BUILDNUM, info.buildNum).apply()

			val staleness = info.buildNum - installedDays
			val snoozed = prefs.getInt(KEY_DISMISSED_BUILDNUM, -1) >= info.buildNum &&
				staleness < MAX_NAG_STALENESS_DAYS &&
				now < prefs.getLong(KEY_DISMISS_UNTIL, 0L)
			if (snoozed)
				return@launch

			val changelog = updater.fetchChangelog(BuildConfig.GIT_HASH, info.tagName)
			showEngineUpdateDialog(updater, info.buildNum, changelog, prefs)
		}
	}

	// Snooze shrinks the longer the user keeps skipping: 12h at first, linearly
	// down to 0 after 2 days of skipping (then it nags at every check).
	private fun snoozeFor(elapsedMs: Long): Long {
		val factor = (1.0 - elapsedMs.toDouble() / DISMISS_ESCALATION_MS).coerceIn(0.0, 1.0)
		return (DISMISS_SNOOZE_START_MS * factor).toLong()
	}

	private fun snoozeUpdate(prefs: android.content.SharedPreferences, remoteBuildNum: Int) {
		val now = System.currentTimeMillis()
		val prevBuild = prefs.getInt(KEY_DISMISSED_BUILDNUM, -1)
		val prevFirst = prefs.getLong(KEY_FIRST_DISMISS_TIME, 0L)
		// A newer build restarts the escalation clock; the same build keeps counting.
		val firstDismiss = if (remoteBuildNum > prevBuild || prevFirst == 0L) now else prevFirst
		val snooze = snoozeFor(now - firstDismiss)
		prefs.edit()
			.putInt(KEY_DISMISSED_BUILDNUM, remoteBuildNum)
			.putLong(KEY_FIRST_DISMISS_TIME, firstDismiss)
			.putLong(KEY_DISMISS_UNTIL, now + snooze)
			.apply()
	}

	// Manual check from Settings: bypasses the interval and "later" gates and
	// always reports back, so the user gets feedback ("up to date" / failure).
	fun checkForUpdatesManually() {
		val view = LayoutInflater.from(this).inflate(R.layout.dialog_download_progress, null)
		view.findViewById<TextView>(R.id.downloadStatus).text = getString(R.string.engine_update_checking)
		val dialog = MaterialAlertDialogBuilder(this)
			.setTitle(R.string.engine_update_checking)
			.setView(view)
			.setCancelable(true)
			.setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
			.create()
		dialog.show()

		val updater = AppUpdater(this)
		val job = lifecycleScope.launch {
			val result = updater.checkForUpdate()
			if (!dialog.isShowing)
				return@launch
			dialog.dismiss()
			when (result) {
				is AppUpdater.UpdateCheck.Available -> {
					val prefs = getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
					prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
					val changelog = updater.fetchChangelog(BuildConfig.GIT_HASH, result.info.tagName)
					showEngineUpdateDialog(updater, result.info.buildNum, changelog, prefs)
				}
				is AppUpdater.UpdateCheck.UpToDate,
				is AppUpdater.UpdateCheck.Disabled ->
					MaterialAlertDialogBuilder(this@MainActivity)
						.setTitle(R.string.check_updates)
						.setMessage(R.string.engine_update_up_to_date)
						.setPositiveButton(android.R.string.ok, null)
						.show()
				is AppUpdater.UpdateCheck.Failed ->
					MaterialAlertDialogBuilder(this@MainActivity)
						.setTitle(R.string.check_updates)
						.setMessage(R.string.engine_update_check_failed)
						.setPositiveButton(android.R.string.ok, null)
						.show()
			}
		}
		dialog.setOnDismissListener { job.cancel() }
	}

	private fun showEngineUpdateDialog(
		updater: AppUpdater,
		remoteBuildNum: Int,
		changelog: List<AppUpdater.CommitInfo>?,
		prefs: android.content.SharedPreferences,
	) {
		val changelogText = changelog?.takeIf { it.isNotEmpty() }?.let { commits ->
			buildString {
				append(getString(R.string.engine_update_changelog_header))
				val shown = commits.take(CHANGELOG_MAX_LINES)
				for (c in shown)
					append("\n* ").append(c.subject)
				val extra = commits.size - shown.size
				if (extra > 0)
					append("\n").append(getString(R.string.engine_update_changelog_more, extra))
			}
		}

		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.engine_update_available)
			// Message and changelog share one custom view: with both setMessage()
			// and setView() the dialog layout stops prioritizing the buttons, and
			// a long changelog pushes them off screen. As a single view the
			// buttons are measured first and the changelog shrinks and scrolls.
			.setView(dialogContentView(
				this,
				getString(R.string.engine_update_message, remoteBuildNum),
				changelogText,
			))
			.setPositiveButton(R.string.engine_update_download) { _, _ ->
				showEngineDownloadDialog(updater)
			}
			.setNegativeButton(R.string.engine_update_later) { _, _ ->
				snoozeUpdate(prefs, remoteBuildNum)
			}
			.show()
	}

	private fun showEngineDownloadDialog(updater: AppUpdater) {
		if (!updater.canInstall()) {
			promptForInstallPermission()
			return
		}
		showDownloadProgressDialog(
			ctx = this,
			titleRes = R.string.engine_update_downloading,
			cancelable = true,
			scope = lifecycleScope,
			download = { onProgress -> updater.downloadAndInstall(onProgress) },
		)
	}

	private fun promptForInstallPermission() {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.engine_update_permission_needed)
			.setMessage(R.string.engine_update_permission_message)
			.setPositiveButton(R.string.engine_update_open_settings) { _, _ ->
				val packageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
					"package:$packageName".toUri())
				try {
					startActivity(packageIntent)
				} catch (_: ActivityNotFoundException) {
					try {
						startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
					} catch (_: ActivityNotFoundException) {
						// no settings screen — nothing more we can do
					}
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	override fun onSupportNavigateUp(): Boolean {
		return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
	}

	private fun showPendingCrashReport() {
		val pending = CrashReports.pendingStacktrace(this)
		if (!pending.exists() || pending.length() == 0L)
			return

		val historyDir = CrashReports.historyDir(this).apply { mkdirs() }
		val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
		val entryDir = File(historyDir, "crash-$ts").apply { mkdirs() }

		moveOrCopy(pending, File(entryDir, CrashReports.STACKTRACE_NAME))
		moveOrCopy(CrashReports.pendingSysinfo(this), File(entryDir, CrashReports.SYSINFO_NAME))
		moveOrCopy(CrashReports.pendingIntent(this), File(entryDir, CrashReports.INTENT_NAME))
		moveOrCopy(CrashReports.pendingEngineLog(this), File(entryDir, CrashReports.ENGINELOG_NAME))

		val entry = CrashReports.Entry(entryDir)
		AlertDialog.Builder(this)
			.setTitle(R.string.crash_dialog_title)
			.setView(monospaceTextView(this, entry.summary()))
			.setPositiveButton(R.string.crash_send_to_developers) { _, _ -> CrashReports.sendByEmail(this, entry) }
			.setNeutralButton(R.string.crash_share) { _, _ -> CrashReports.share(this, entry) }
			.setNegativeButton(R.string.crash_dismiss, null)
			.show()
	}

	private fun moveOrCopy(src: File, dst: File) {
		if (!src.exists())
			return

		if (src.renameTo(dst))
			return

		src.copyTo(dst, overwrite = true)
		src.delete()
	}

	companion object {
		private const val CHANGELOG_MAX_LINES = 15
		private const val UPDATE_PREFS = "app_updater"
		private const val KEY_LAST_CHECK = "last_check_ms"
		private const val KEY_DISMISSED_BUILDNUM = "dismissed_buildnum"
		private const val KEY_DISMISS_UNTIL = "dismiss_until_ms"
		private const val KEY_FIRST_DISMISS_TIME = "first_dismiss_ms"
		private const val KEY_REMOTE_BUILDNUM = "remote_buildnum"
		private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
		private const val DISMISS_SNOOZE_START_MS = 12 * 60 * 60 * 1000L
		private const val DISMISS_ESCALATION_MS = 2 * 24 * 60 * 60 * 1000L
		private const val MAX_NAG_STALENESS_DAYS = 14
	}
}
>>>>>>> upstream/master
