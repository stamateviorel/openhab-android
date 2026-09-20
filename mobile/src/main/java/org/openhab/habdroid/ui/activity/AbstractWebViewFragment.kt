/*
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.ui.activity

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.openhab.habdroid.R
import org.openhab.habdroid.core.connection.Connection
import org.openhab.habdroid.core.connection.DemoConnection
import org.openhab.habdroid.databinding.BottomSheetShortcutLabelBinding
import org.openhab.habdroid.databinding.FragmentWebviewBinding
import org.openhab.habdroid.model.ServerConfiguration
import org.openhab.habdroid.ui.AbstractBaseActivity
import org.openhab.habdroid.ui.ConnectionWebViewClient
import org.openhab.habdroid.ui.MainActivity
import org.openhab.habdroid.ui.WebViewManager
import org.openhab.habdroid.ui.setUpForConnection
import org.openhab.habdroid.util.getActiveServerId
import org.openhab.habdroid.util.getConfiguredServerIds
import org.openhab.habdroid.util.getConnectionFactory
import org.openhab.habdroid.util.getPrefs
import org.openhab.habdroid.util.getSecretPrefs
import org.openhab.habdroid.util.hasPermissions
import org.openhab.habdroid.util.isDarkModeActive
import org.openhab.habdroid.util.orDefaultIfEmpty
import org.openhab.habdroid.util.toRelativeUrl

abstract class AbstractWebViewFragment :
    Fragment(),
    CoroutineScope,
    MenuProvider {
    private val job = Job()
    override val coroutineContext: CoroutineContext get() = Dispatchers.Main + job
    private var binding: FragmentWebviewBinding? = null
    private val webView get() = binding?.webview
    private var callback: ParentCallback? = null
    private val mainActivity get() = context as MainActivity?
    var isStackRoot = false
        private set
    var title: String? = null
        private set
    var wantsActionBar = true
        private set
    private var initialLoadDone = false
    private var logoAnimator: ObjectAnimator? = null

    private val permissionRequester = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val request = pendingPermissionRequests.remove(results.keys) ?: return@registerForActivityResult
        val grantedResources = permsToWebResources(results.filter { (_, v) -> v }.keys.toTypedArray())
        if (grantedResources.isEmpty()) {
            request.deny()
        } else {
            request.grant(grantedResources)
        }
    }

    private val pendingPermissionRequests = mutableMapOf<Set<String>, PermissionRequest>()

    abstract val titleRes: Int
    abstract val errorMessageRes: Int
    abstract val urlToLoad: String
    abstract val pathForError: String
    abstract val lockDrawer: Boolean
    abstract val shortcutIcon: Int
    abstract val shortcutAction: String
    private val shortcutInfo: ShortcutInfoCompat
        get() {
            val context = requireContext()
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SERVER_ID, context.getPrefs().getActiveServerId())
                .setAction(shortcutAction)

            webView?.url?.toHttpUrlOrNull()?.let {
                intent.putExtra(MainActivity.EXTRA_SUBPAGE, it.toRelativeUrl())
            }

            return ShortcutInfoCompat.Builder(context, "$shortcutAction-${System.currentTimeMillis()}")
                .setShortLabel(title!!)
                .setIcon(IconCompat.createWithResource(context, shortcutIcon))
                .setIntent(intent)
                .build()
        }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val prefs = context.getPrefs()
        val activeServerId = prefs.getActiveServerId()
        title = context.getString(titleRes)
        if (
            prefs.getConfiguredServerIds().size > 1 &&
            context.getConnectionFactory().currentActive?.conn?.connection !is DemoConnection
        ) {
            val activeServerName = ServerConfiguration.load(prefs, context.getSecretPrefs(), activeServerId)?.name
            title = getString(R.string.ui_on_server, title, activeServerName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireContext().getConnectionFactory().activeFlow.collectLatest { info ->
                    val usedConnection = webView?.tag as? Connection
                    val newConnection = info.conn?.connection
                    if (newConnection != null && newConnection != usedConnection) {
                        loadWebsite()
                    }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        val binding = FragmentWebviewBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        webView?.let { WebViewManager.getInstance(requireContext()).setUpForActiveServer(it) }
        webView?.apply {
            // Make sure not to pass window insets into the WebView, we already handle them in the activity
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                WindowInsetsCompat.CONSUMED
            }

            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    Log.d(TAG, "progressCallback: progress = $newProgress")
                    if (newProgress == 100) {
                        initialLoadDone = true
                        updateViewVisibility(null, null)
                    } else {
                        updateViewVisibility(null, newProgress)
                    }
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    val requestedPerms = request.resources
                        .map { res -> PERMISSION_REQUEST_MAPPING.get(res) }
                        .filterNotNull()
                        .flatten()
                        .toTypedArray()

                    if (requestedPerms.isEmpty()) {
                        Log.w(TAG, "Requested unknown permissions ${request.resources}")
                        request.deny()
                    } else if (requireContext().hasPermissions(requestedPerms)) {
                        request.grant(permsToWebResources(requestedPerms))
                    } else {
                        (activity as AbstractBaseActivity).showSnackbar(
                            SNACKBAR_TAG_WEBVIEW_PERMISSIONS,
                            R.string.webview_snackbar_permissions_missing,
                            Snackbar.LENGTH_INDEFINITE,
                            R.string.settings_background_tasks_permission_allow,
                            { request.deny() }
                        ) {
                            pendingPermissionRequests[requestedPerms.toSet()] = request
                            permissionRequester.launch(requestedPerms)
                        }
                    }
                }

                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d(TAG, "${message.message()} -- From line ${message.lineNumber()} of ${message.sourceId()}")
                    return true
                }
            }
        }

        isStackRoot = requireArguments().getBoolean(KEY_IS_STACK_ROOT)

        binding?.retryButton?.setOnClickListener {
            Log.d(TAG, "Retry button clicked, reload website")
            loadWebsite()
        }
        binding?.emptyMessage?.text = getString(errorMessageRes)

        val subpage = requireArguments().getString(KEY_SUBPAGE)
        when {
            savedInstanceState != null -> {
                val savedUrl = savedInstanceState.getString(KEY_CURRENT_URL, urlToLoad)
                Log.d(TAG, "Load website from savedInstanceState: $savedUrl")
                webView?.restoreState(savedInstanceState)
                loadWebsite(savedUrl)
            }

            subpage != null && subpage.startsWith("/") -> {
                Log.d(TAG, "Load subpage: $subpage")
                loadWebsite(subpage)
            }

            else -> {
                Log.d(TAG, "Load default website")
                loadWebsite()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        logoAnimator?.cancel()
        webView?.destroy()
        binding = null
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
        pushAppMenu()
        if (lockDrawer) {
            mainActivity?.setDrawerLocked(true)
        }
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
        webView?.pauseTimers()
        if (lockDrawer) {
            mainActivity?.setDrawerLocked(false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.url?.let { outState.putString(KEY_CURRENT_URL, it) }
        webView?.saveState(outState)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(requireContext())) {
            inflater.inflate(R.menu.webview_menu, menu)
        }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.webview_add_shortcut -> {
            pinShortcut()
            true
        }

        else -> false
    }

    fun setCallback(callback: ParentCallback) {
        this.callback = callback
    }

    private fun pinShortcut() {
        if (!isAdded) {
            return
        }
        val f = ShortcutTitleBottomSheet()
        f.show(childFragmentManager, "shortcut_title")
    }

    // called from ShortcutTitleBottomSheet
    private fun createShortcut(info: ShortcutInfoCompat) {
        val context = context ?: return
        val success = ShortcutManagerCompat.requestPinShortcut(context, info, null)
        val textResId = if (success) R.string.home_shortcut_success_pinning else R.string.home_shortcut_error_pinning
        val duration = if (success) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG
        mainActivity?.showSnackbar(MainActivity.SNACKBAR_TAG_SHORTCUT_INFO, textResId, duration)
    }

    fun goBack(): Boolean {
        if (webView?.canGoBack() == true) {
            val oldUrl = webView?.url
            do {
                webView?.goBack()
                // Skip redundant history entries while going back
            } while (webView?.url == oldUrl && webView?.canGoBack() == true)
            return true
        }
        return false
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    private fun loadWebsite(urlToLoad: String = this.urlToLoad) {
        val conn = requireContext().getConnectionFactory().currentActive?.usableConnection
        if (conn == null) {
            updateViewVisibility(true, null)
            return
        }
        initialLoadDone = false
        binding?.loadingLogoFill?.drawable?.level = 0
        updateViewVisibility(false, 0)

        val webView = webView ?: return
        val url = buildUrl(conn, urlToLoad)

        Log.d(TAG, "Loading web page $url")
        webView.setUpForConnection(conn)
        webView.setBackgroundColor(Color.TRANSPARENT)

        val jsInterface = if (ShortcutManagerCompat.isRequestPinShortcutSupported(requireContext())) {
            OHAppInterfaceWithPin(requireContext(), this)
        } else {
            OHAppInterface(requireContext(), this)
        }
        webView.addJavascriptInterface(jsInterface, "OHApp")

        webView.webViewClient = object : ConnectionWebViewClient(conn) {
            private fun handleError(url: Uri) {
                if (url.path == pathForError) {
                    updateViewVisibility(true, null)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                Log.e(TAG, "onReceivedError() on URL: ${request.url}")
                handleError(request.url)
            }

            @Deprecated(message = "Function is called on older Android versions")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                Log.e(TAG, "onReceivedError() (deprecated) on URL: $failingUrl")
                // This deprecated version is only called for the main resource, so no need to check for 'pathForError' here
                updateViewVisibility(true, null)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                Log.e(TAG, "onReceivedHttpError() on URL: ${request.url}")
                handleError(request.url)
            }
        }
        webView.tag = conn
        webView.loadUrl(url.toString())
    }

    open fun buildUrl(connection: Connection, url: String): HttpUrl = connection.httpClient.buildUrl(url)

    /**
     * Change the visibility of the progress and error indicators and the WebView.
     * @param error null if the error state didn't change, true if an error occurred, false if an error was cleared.
     * @param loadingProgress null if no loading happens, current progress otherwise.
     */
    private fun updateViewVisibility(error: Boolean?, loadingProgress: Int?) {
        error?.let {
            webView?.isVisible = !error
            binding?.empty?.isVisible = error
        }
        // The logo is for the initial load of a page, later loads inside that page only get the progress bar
        val showLogo = loadingProgress != null && !initialLoadDone
        binding?.loadingLogo?.isVisible = showLogo
        val logoFill = binding?.loadingLogoFill?.drawable
        if (showLogo && logoFill != null) {
            logoAnimator?.cancel()
            logoAnimator = ObjectAnimator.ofInt(logoFill, "level", logoFill.level, (loadingProgress ?: 0) * 100)
                .setDuration(LOGO_ANIMATION_DURATION_MS)
                .also { it.start() }
        }
        binding?.progress?.apply {
            isVisible = loadingProgress != null && !showLogo
            progress = loadingProgress ?: 0
        }
    }

    /**
     * Hand the app's menu entries to Main UI, which shows them in its own sidebar.
     * No-op for web UIs (or Main UI versions) which don't support that.
     */
    private fun pushAppMenu() {
        val menu = mainActivity?.buildAppMenu() ?: return
        webView?.evaluateJavascript(
            "if (window.MainUI && typeof window.MainUI.setAppMenu === 'function') window.MainUI.setAppMenu($menu)",
            null
        )
    }

    private fun handleAppMenuItem(id: String) {
        mainActivity?.onAppMenuItemSelected(id)
    }

    private fun hideActionBar() {
        wantsActionBar = false
        callback?.updateActionBarState()
    }

    private fun closeFragment() {
        callback?.closeFragment()
    }

    open class OHAppInterface(private val context: Context, private val fragment: AbstractWebViewFragment) {
        @JavascriptInterface
        fun preferTheme(): String {
            return "md" // Material design
        }

        @JavascriptInterface
        fun preferDarkMode(): String {
            val nightMode = if (context.isDarkModeActive()) "dark" else "light"
            Log.d(TAG, "preferDarkMode(): $nightMode")
            return nightMode
        }

        @JavascriptInterface
        fun exitToApp() {
            Log.d(TAG, "exitToApp()")
            fragment.launch {
                fragment.closeFragment()
            }
        }

        @JavascriptInterface
        fun menuReady() {
            Log.d(TAG, "menuReady()")
            fragment.launch {
                fragment.pushAppMenu()
            }
        }

        @JavascriptInterface
        fun menuItemSelected(id: String) {
            Log.d(TAG, "menuItemSelected($id)")
            fragment.launch {
                fragment.handleAppMenuItem(id)
            }
        }

        @JavascriptInterface
        fun goFullscreen() {
            Log.d(TAG, "goFullscreen()")
            fragment.launch {
                fragment.hideActionBar()
            }
        }

        companion object {
            @JvmStatic
            protected val TAG: String = OHAppInterface::class.java.simpleName
        }
    }

    class OHAppInterfaceWithPin(context: Context, private val fragment: AbstractWebViewFragment) :
        OHAppInterface(context, fragment) {
        @JavascriptInterface
        fun pinToHome() {
            Log.d(TAG, "pinToHome()")
            fragment.launch {
                fragment.pinShortcut()
            }
        }
    }

    companion object {
        private val TAG = AbstractWebViewFragment::class.java.simpleName

        private const val SNACKBAR_TAG_WEBVIEW_PERMISSIONS = "webviewPermissions"

        private val PERMISSION_REQUEST_MAPPING = mapOf(
            PermissionRequest.RESOURCE_AUDIO_CAPTURE to listOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.MODIFY_AUDIO_SETTINGS
            ),
            PermissionRequest.RESOURCE_VIDEO_CAPTURE to listOf(
                android.Manifest.permission.CAMERA
            )
        )

        private fun permsToWebResources(androidPermissions: Array<String>) = PERMISSION_REQUEST_MAPPING
            .filter { (_, perms) -> perms.all { perm -> androidPermissions.contains(perm) } }
            .keys
            .toTypedArray()

        private const val LOGO_ANIMATION_DURATION_MS = 200L

        private const val KEY_CURRENT_URL = "url"
        const val KEY_IS_STACK_ROOT = "is_stack_root"
        const val KEY_SUBPAGE = "subpage"
    }

    interface ParentCallback {
        fun closeFragment()

        fun updateActionBarState()
    }

    class ShortcutTitleBottomSheet : BottomSheetDialogFragment() {
        private val parent get() = parentFragment as AbstractWebViewFragment
        private lateinit var origInfo: ShortcutInfoCompat
        private lateinit var binding: BottomSheetShortcutLabelBinding

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            origInfo = parent.shortcutInfo
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            binding = BottomSheetShortcutLabelBinding.inflate(inflater, container, false)
            return binding.root
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            binding.editor.apply {
                setText(origInfo.shortLabel, TextView.BufferType.EDITABLE)
                requestFocus()
            }

            binding.cancelButton.setOnClickListener {
                dismissAllowingStateLoss()
            }
            binding.save.setOnClickListener {
                save()
                dismissAllowingStateLoss()
            }
        }

        private fun save() {
            val label = binding.editor.text.toString().orDefaultIfEmpty(" ")
            val newInfo = ShortcutInfoCompat.Builder(origInfo)
                .setShortLabel(label)
                .build()
            parent.createShortcut(newInfo)
        }
    }
}
