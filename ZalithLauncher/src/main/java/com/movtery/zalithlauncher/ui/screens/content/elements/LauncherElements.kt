/*
 * Zalith Launcher 2
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ui.screens.content.elements

import com.movtery.zalithlauncher.game.version.mod.ModCompat
import com.movtery.zalithlauncher.game.version.mod.ModAutoResolver
import com.movtery.zalithlauncher.game.version.mod.LightingFix
import com.movtery.zalithlauncher.game.version.mod.MobileModRules
import com.movtery.zalithlauncher.game.version.mod.BestGameSettings
import com.movtery.zalithlauncher.game.version.mod.LegacyKotlinSupport
import com.movtery.zalithlauncher.game.version.mod.ModPerformanceTuner
import com.movtery.zalithlauncher.game.version.mod.VoxySupport
import com.movtery.zalithlauncher.game.version.mod.VoxyInstaller
import com.movtery.zalithlauncher.game.version.mod.PhysxSupport
import com.movtery.zalithlauncher.utils.device.DeviceProfiler
import com.movtery.zalithlauncher.utils.device.DeviceTier
import android.widget.Toast
import androidx.compose.material3.LinearProgressIndicator
import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.GifDecoder
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.coroutine.TaskSystem
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.account.accountErrorText
import com.movtery.zalithlauncher.game.account.auth_server.AuthServerHelper
import com.movtery.zalithlauncher.game.account.isMicrosoftAccount
import com.movtery.zalithlauncher.game.account.microsoftLogin
import com.movtery.zalithlauncher.game.plugin.ApkPlugin
import com.movtery.zalithlauncher.game.plugin.natives.NativePluginManager
import com.movtery.zalithlauncher.game.plugin.renderer.RendererPluginManager
import com.movtery.zalithlauncher.game.renderer.RendererInterface
import com.movtery.zalithlauncher.game.renderer.Renderers
import com.movtery.zalithlauncher.game.renderer.isSfpewConfiguredFor
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.hasVulkanBackend
import com.movtery.zalithlauncher.game.version.installed.utils.isBiggerVer
import com.movtery.zalithlauncher.game.version.installed.utils.isLowerVer
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.enums.BackgroundBlur
import com.movtery.zalithlauncher.ui.AndroidStringText
import com.movtery.zalithlauncher.ui.androidText
import com.movtery.zalithlauncher.ui.components.ButtonPosition
import com.movtery.zalithlauncher.ui.components.MarqueeText
import com.movtery.zalithlauncher.ui.components.PositionButton
import com.movtery.zalithlauncher.ui.components.PositionFilledTonalButton
import com.movtery.zalithlauncher.ui.components.SimpleAlertDialog
import com.movtery.zalithlauncher.ui.components.VideoPlayer
import com.movtery.zalithlauncher.ui.components.rememberDialogMaxHeight
import com.movtery.zalithlauncher.ui.screens.content.FirstLoginMenu
import com.movtery.zalithlauncher.ui.theme.cardColor
import com.movtery.zalithlauncher.ui.theme.onCardColor
import com.movtery.zalithlauncher.utils.canHandlePermission
import com.movtery.zalithlauncher.utils.checkStoragePermissions
import com.movtery.zalithlauncher.utils.file.InvalidFilenameException
import com.movtery.zalithlauncher.utils.file.checkFilenameValidity
import com.movtery.zalithlauncher.utils.hasStoragePermission
import com.movtery.zalithlauncher.utils.image.isGifFile
import com.movtery.zalithlauncher.viewmodel.BackgroundViewModel
import com.movtery.zalithlauncher.viewmodel.ErrorViewModel
import com.movtery.zalithlauncher.viewmodel.EventViewModel
import com.movtery.zalithlauncher.viewmodel.LaunchGameViewModel
import com.movtery.zalithlauncher.viewmodel.sendToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java.io.File

@Parcelize
sealed interface QuickPlay : Parcelable {
    /** 快速启动游玩存档  仅支持 1.20+ 23w14a+ */
    @Parcelize
    data class Save(val saveName: String): QuickPlay

    /** 快速启动游玩服务器 */
    @Parcelize
    data class Server(val serverAddress: String): QuickPlay
}

sealed interface LaunchGameOperation {
    data object None : LaunchGameOperation
    /** 没有安装版本/没有选中有效版本 */
    data object NoVersion : LaunchGameOperation
    /** 版本名称非法时 */
    data class InvalidVersionName(val th: InvalidFilenameException) : LaunchGameOperation
    /** 没有可用账号 */
    data object NoAccount : LaunchGameOperation

    /** 渲染器可配置，但需要用到文件管理权限 */
    data class RendererNoStoragePermission(
        val renderer: RendererInterface,
        val version: Version,
        val quickPlay: QuickPlay?
    ) : LaunchGameOperation

    /** 当前渲染器不支持选中版本 */
    data class UnsupportedRenderer(
        val renderer: RendererInterface,
        val version: Version,
        val quickPlay: QuickPlay?
    ): LaunchGameOperation

    /** 当前已加载的插件不支持选中的版本 */
    data class UnsupportedPlugins(
        val plugins: List<ApkPlugin>,
        val version: Version,
        val quickPlay: QuickPlay?
    ): LaunchGameOperation

    /** 尝试启动：启动前检查一些东西 */
    data class TryLaunch(
        val version: Version?,
        val quickPlay: QuickPlay? = null
    ) : LaunchGameOperation

    /** 账号凭据已被服务端拒绝，需要重新登录 */
    data class AccountRelogin(
        val account: Account,
        val version: Version,
        val quickPlay: QuickPlay?,
        val logging: Boolean = false,
        val error: Throwable? = null
    ) : LaunchGameOperation

    /** 账号刷新失败，可选择跳过刷新继续启动 */
    data class AccountRefreshFailed(
        val account: Account,
        val error: Throwable,
        val version: Version,
        val quickPlay: QuickPlay?
    ) : LaunchGameOperation

    /** ONA: installing missing requirements and swapping wrong builds before the launch */
    data class ResolvingMods(
        val version: Version,
        val quickPlay: QuickPlay?,
        val skipAccountRefresh: Boolean
    ) : LaunchGameOperation

    /** ONA: Voxy is installed but plugins it needs are missing */
    data class VoxySetup(
        val version: Version,
        val quickPlay: QuickPlay?,
        val skipAccountRefresh: Boolean,
        val missing: List<VoxyInstaller.Part>
    ) : LaunchGameOperation

    /** ONA: mod problems the automatic resolver could not handle */
    data class ModProblems(
        val version: Version,
        val quickPlay: QuickPlay?,
        val skipAccountRefresh: Boolean,
        val problems: List<ModCompat.Problem>
    ) : LaunchGameOperation

    /** 正式启动 */
    data class RealLaunch(
        val version: Version,
        val quickPlay: QuickPlay?,
        val skipAccountRefresh: Boolean = false,
        val skipModCheck: Boolean = false,
        val skipVoxyCheck: Boolean = false
    ) : LaunchGameOperation
}

@Composable
fun LaunchGameOperation(
    activity: Activity,
    eventViewModel: EventViewModel,
    launchGameViewModel: LaunchGameViewModel,
    exitActivity: () -> Unit,
    ensureVulkanSupported: suspend (Version) -> Boolean,
    submitError: (ErrorViewModel.ThrowableMessage) -> Unit,
    toAccountManageScreen: (FirstLoginMenu) -> Unit = {},
    toVersionManageScreen: () -> Unit = {},
    navigateToWeb: (String) -> Unit = {},
    backToMain: () -> Unit = {},
    checkIfInWebScreen: () -> Boolean = { false }
) {
    val launchGameOperation by launchGameViewModel.launchGameOperation.collectAsStateWithLifecycle()

    when (val operation = launchGameOperation) {
        is LaunchGameOperation.None -> {}
        is LaunchGameOperation.NoVersion -> {
            LaunchedEffect(Unit) {
                eventViewModel.sendToast(androidText(R.string.game_launch_no_version))
                toVersionManageScreen()
                launchGameViewModel.updateOperation(LaunchGameOperation.None)
            }
        }
        is LaunchGameOperation.InvalidVersionName -> {
            val th = operation.th
            SimpleAlertDialog(
                title = stringResource(R.string.versions_manage_invalid),
                text = th.getInvalidSummary(),
                confirmText = stringResource(R.string.generic_cancel),
                onDismiss = {
                    launchGameViewModel.updateOperation(LaunchGameOperation.None)
                }
            )
        }
        is LaunchGameOperation.NoAccount -> {
            LaunchedEffect(Unit) {
                eventViewModel.sendToast(androidText(R.string.game_launch_no_account))
                toAccountManageScreen(FirstLoginMenu.NORMAL)
                launchGameViewModel.updateOperation(LaunchGameOperation.None)
            }
        }
        is LaunchGameOperation.RendererNoStoragePermission -> {
            LaunchedEffect(Unit) {
                val renderer = operation.renderer
                val version = operation.version
                val quickPlay = operation.quickPlay
                withContext(Dispatchers.Main) {
                    checkStoragePermissions(
                        activity = activity,
                        message = activity.getString(R.string.renderer_version_storage_permissions, renderer.getRendererName()),
                        messageSdk30 = activity.getString(R.string.renderer_version_storage_permissions_sdk30, renderer.getRendererName()),
                        onDialogCancel = {
                            //用户拒绝授权，但仍然允许启动（不过这会导致配置无法读取）
                            launchGameViewModel.updateOperation(LaunchGameOperation.RealLaunch(version, quickPlay))
                        }
                    )
                }
                launchGameViewModel.updateOperation(LaunchGameOperation.None)
            }
        }
        is LaunchGameOperation.UnsupportedRenderer -> {
            val renderer = operation.renderer
            val version = operation.version
            val quickPlay = operation.quickPlay
            SimpleAlertDialog(
                title = stringResource(R.string.generic_warning),
                text = stringResource(R.string.renderer_version_unsupported_warning, renderer.getRendererName()),
                confirmText = stringResource(R.string.generic_anyway),
                onConfirm = {
                    launchGameViewModel.updateOperation(LaunchGameOperation.RealLaunch(version, quickPlay))
                },
                onDismiss = {
                    launchGameViewModel.updateOperation(LaunchGameOperation.None)
                }
            )
        }
        is LaunchGameOperation.UnsupportedPlugins -> {
            val plugins = operation.plugins
            val version = operation.version
            val quickPlay = operation.quickPlay
            SimpleAlertDialog(
                title = stringResource(R.string.generic_warning),
                text = stringResource(R.string.plugin_unsupported_warning, plugins.joinToString(", ") { it.appName }),
                confirmText = stringResource(R.string.generic_anyway),
                onConfirm = {
                    launchGameViewModel.updateOperation(LaunchGameOperation.RealLaunch(version, quickPlay))
                },
                onDismiss = {
                    launchGameViewModel.updateOperation(LaunchGameOperation.None)
                }
            )
        }
        is LaunchGameOperation.TryLaunch -> {
            LaunchedEffect(Unit) {
                val version = operation.version ?: run {
                    launchGameViewModel.updateOperation(LaunchGameOperation.NoVersion)
                    return@LaunchedEffect
                }

                try {
                    checkFilenameValidity(version.getVersionName())
                } catch (th: InvalidFilenameException) {
                    launchGameViewModel.updateOperation(LaunchGameOperation.InvalidVersionName(th))
                    return@LaunchedEffect
                }

                val quickPlay = operation.quickPlay

                AccountsManager.currentAccountFlow.value ?: run {
                    launchGameViewModel.updateOperation(LaunchGameOperation.NoAccount)
                    return@LaunchedEffect
                }

                Renderers.setCurrentRenderer(version.getRenderer())
                val currentRenderer = Renderers.getCurrentRenderer()

                val mcVer = version.getVersionInfo()!!.minecraftVersion

                // 设备完全支持 Vulkan 时跳过渲染器的版本支持检查
                if (!version.hasVulkanBackend() || !ensureVulkanSupported(version)) {
                    val supportedMin = currentRenderer.getMinMCVersion()?.let { verStr ->
                        !isSfpewConfiguredFor(currentRenderer, version) && mcVer.isLowerVer(verStr)
                    } ?: false
                    val supportedMax = currentRenderer.getMaxMCVersion()?.let { verStr ->
                        mcVer.isBiggerVer(verStr)
                    } ?: false
                    val isRendererUnsupported = supportedMin || supportedMax

                    if (isRendererUnsupported) {
                        launchGameViewModel.updateOperation(LaunchGameOperation.UnsupportedRenderer(currentRenderer, version, quickPlay))
                        return@LaunchedEffect
                    }
                }

                val unsupportedPlugins = NativePluginManager.getCheckedPlugins().filter { plugin ->
                    (plugin.minMCVer?.let { mcVer.isLowerVer(it) } ?: false) ||
                            (plugin.maxMCVer?.let { mcVer.isBiggerVer(it) } ?: false)
                }
                if (unsupportedPlugins.isNotEmpty()) {
                    launchGameViewModel.updateOperation(LaunchGameOperation.UnsupportedPlugins(unsupportedPlugins, version, quickPlay))
                    return@LaunchedEffect
                }

                //为可配置的渲染器检查文件管理权限
                //前提：系统支持这个设置
                if (
                    canHandlePermission &&  !hasStoragePermission &&
                    RendererPluginManager.isConfigurablePlugin(version.getRenderer())
                ) {
                    launchGameViewModel.updateOperation(LaunchGameOperation.RendererNoStoragePermission(currentRenderer, version, quickPlay))
                    return@LaunchedEffect
                }

                //正式启动游戏
                launchGameViewModel.updateOperation(LaunchGameOperation.RealLaunch(version, quickPlay))
            }
        }
        is LaunchGameOperation.AccountRelogin -> {
            if (operation.account.isMicrosoftAccount()) {
                var selectLoginMethod by remember { mutableStateOf(false) }

                val startReloginLogin: (useSystemBrowser: Boolean) -> Unit = { useSystemBrowser ->
                    launchGameViewModel.updateOperation(LaunchGameOperation.None)
                    microsoftLogin(
                        context = activity,
                        useSystemBrowser = useSystemBrowser,
                        toWeb = navigateToWeb,
                        toSystemBrowser = { url ->
                            eventViewModel.sendEvent(EventViewModel.Event.OpenLink(url))
                        },
                        backToMain = backToMain,
                        checkIfInWebScreen = checkIfInWebScreen,
                        updateOperation = {},
                        showToast = { text, duration -> eventViewModel.sendToast(text, duration) },
                        submitError = submitError
                    ) {
                        activity.runOnUiThread {
                            launchGameViewModel.updateOperation(
                                LaunchGameOperation.RealLaunch(
                                    operation.version,
                                    operation.quickPlay
                                )
                            )
                        }
                    }
                }

                if (selectLoginMethod) {
                    MicrosoftLoginMethodDialog(
                        onDismissRequest = {
                            selectLoginMethod = false
                        },
                        onSystemBrowser = {
                            startReloginLogin(true)
                        },
                        onInternalBrowser = {
                            startReloginLogin(false)
                        }
                    )
                } else {
                    MicrosoftReloginDialog(
                        onDismissRequest = {
                            launchGameViewModel.updateOperation(LaunchGameOperation.None)
                        },
                        onConfirm = {
                            selectLoginMethod = true
                        }
                    )
                }
            } else {
                OtherAccountReloginDialog(
                    account = operation.account,
                    logging = operation.logging,
                    error = operation.error,
                    onDismissRequest = {
                        launchGameViewModel.updateOperation(LaunchGameOperation.None)
                    },
                    onConfirm = { password ->
                        launchGameViewModel.updateOperation(operation.copy(logging = true, error = null))
                        AuthServerHelper(
                            baseUrl = operation.account.otherBaseUrl!!,
                            serverName = operation.account.accountType!!,
                            email = operation.account.otherAccount!!,
                            password = password,
                            onSuccess = { acc, _ ->
                                AccountsManager.markSessionValidated(acc)
                                AccountsManager.suspendSaveAccount(acc)
                                activity.runOnUiThread {
                                    launchGameViewModel.updateOperation(
                                        LaunchGameOperation.RealLaunch(
                                            operation.version,
                                            operation.quickPlay)
                                    )
                                }
                            },
                            onFailed = { th ->
                                activity.runOnUiThread {
                                    launchGameViewModel.updateOperation(
                                        operation.copy(
                                            logging = false,
                                            error = th
                                        ))
                                }
                            }
                        ).let { helper ->
                            TaskSystem.submitTask(helper.justLogin(activity, operation.account))
                        }
                    }
                )
            }
        }
        is LaunchGameOperation.AccountRefreshFailed -> {
            val state = operation
            AccountRefreshFailedDialog(
                error = state.error,
                onSkip = {
                    launchGameViewModel.updateOperation(
                        LaunchGameOperation.RealLaunch(
                            state.version,
                            state.quickPlay,
                            skipAccountRefresh = true
                        )
                    )
                },
                onRetry = {
                    launchGameViewModel.updateOperation(LaunchGameOperation.RealLaunch(state.version, state.quickPlay))
                },
                onCancel = {
                    launchGameViewModel.updateOperation(LaunchGameOperation.None)
                }
            )
        }
        is LaunchGameOperation.ResolvingMods -> {
            var current by remember { mutableStateOf("") }
            LaunchedEffect(operation) {
                val result = withContext(Dispatchers.IO) {
                    ModAutoResolver.resolve(operation.version) { name -> current = name }
                }
                if (result.fixed > 0) {
                    eventViewModel.sendToast(androidText(R.string.mod_auto_deps_done, result.fixed))
                }
                launchGameViewModel.updateOperation(
                    if (result.remaining.isEmpty()) {
                        LaunchGameOperation.RealLaunch(
                            version = operation.version,
                            quickPlay = operation.quickPlay,
                            skipAccountRefresh = operation.skipAccountRefresh,
                            skipModCheck = true
                        )
                    } else {
                        LaunchGameOperation.ModProblems(operation.version, operation.quickPlay, operation.skipAccountRefresh, result.remaining)
                    }
                )
            }
            Dialog(onDismissRequest = {}) {
                Surface(shape = MaterialTheme.shapes.extraLarge) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.mod_auto_deps_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (current.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.mod_auto_deps_installing, current),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        // ONA: never hold the player back, launch right away if they want
                        androidx.compose.material3.TextButton(
                            modifier = Modifier.align(Alignment.End),
                            onClick = {
                                launchGameViewModel.updateOperation(
                                    LaunchGameOperation.RealLaunch(
                                        version = operation.version,
                                        quickPlay = operation.quickPlay,
                                        skipAccountRefresh = operation.skipAccountRefresh,
                                        skipModCheck = true
                                    )
                                )
                            }
                        ) {
                            Text(text = stringResource(R.string.ona_skip_and_play))
                        }
                    }
                }
            }
        }
        is LaunchGameOperation.VoxySetup -> {
            VoxySetupDialog(
                missing = operation.missing,
                onPlay = {
                    launchGameViewModel.updateOperation(
                        LaunchGameOperation.RealLaunch(
                            version = operation.version,
                            quickPlay = operation.quickPlay,
                            skipAccountRefresh = operation.skipAccountRefresh,
                            skipModCheck = true,
                            skipVoxyCheck = true
                        )
                    )
                },
                onDismiss = {
                    launchGameViewModel.updateOperation(LaunchGameOperation.None)
                }
            )
        }
        is LaunchGameOperation.ModProblems -> {
            ModProblemsDialog(
                version = operation.version,
                initialProblems = operation.problems,
                onPlay = {
                    launchGameViewModel.updateOperation(
                        LaunchGameOperation.RealLaunch(
                            version = operation.version,
                            quickPlay = operation.quickPlay,
                            skipAccountRefresh = operation.skipAccountRefresh,
                            skipModCheck = true
                        )
                    )
                },
                onDismiss = {
                    launchGameViewModel.updateOperation(LaunchGameOperation.None)
                }
            )
        }
        is LaunchGameOperation.RealLaunch -> {
            LaunchedEffect(Unit) {
                val version = operation.version
                val quickPlay = operation.quickPlay
                // ONA: "Light updates" off in Sodium/Rubidium/Embeddium Extra leaves broken blocks black
                val lightingFixed = withContext(Dispatchers.IO) {
                    runCatching { LightingFix.apply(version) }.getOrDefault(emptyList())
                }
                if (lightingFixed.isNotEmpty()) {
                    eventViewModel.sendToast(androidText(R.string.ona_lighting_fixed, lightingFixed.joinToString()), Toast.LENGTH_LONG)
                }
                // ONA: render mods that crash GL4ES based renderers are switched off for them
                if (Renderers.isCurrentRendererValid()) {
                    val renderer = Renderers.getCurrentRenderer()
                    val result = withContext(Dispatchers.IO) {
                        runCatching { MobileModRules.apply(version, renderer) }.getOrNull()
                    }
                    if (result != null && result.madeCompatible.isNotEmpty()) {
                        eventViewModel.sendToast(
                            androidText(R.string.ona_mobile_mods_compatible, result.madeCompatible.joinToString(), renderer.getRendererName()),
                            Toast.LENGTH_LONG
                        )
                    }
                    if (result != null && result.switchedOff.isNotEmpty()) {
                        eventViewModel.sendToast(
                            androidText(R.string.ona_mobile_mods_off, result.switchedOff.joinToString(), renderer.getRendererName()),
                            Toast.LENGTH_LONG
                        )
                    }
                }
                // ONA: PhysX mods (Physics Mod) get the Android ARM64 PhysX of their version
                val physx = withContext(Dispatchers.IO) {
                    runCatching { PhysxSupport.prepare(version) }.getOrNull()
                }
                when (physx?.status) {
                    PhysxSupport.Status.READY, PhysxSupport.Status.PORTED ->
                        eventViewModel.sendToast(androidText(R.string.ona_physx_ready, physx?.physxVersion ?: ""))
                    PhysxSupport.Status.UNSUPPORTED_OLD -> eventViewModel.sendToast(
                        androidText(R.string.ona_physx_old, physx?.modFile ?: "", physx?.physxVersion ?: ""), Toast.LENGTH_LONG
                    )
                    PhysxSupport.Status.DOWNLOAD_FAILED -> eventViewModel.sendToast(androidText(R.string.ona_physx_failed), Toast.LENGTH_LONG)
                    else -> {}
                }
                // ONA: Voxy's Android compatibility mod goes in by itself
                val voxyCompatInstalled = withContext(Dispatchers.IO) {
                    runCatching { VoxyInstaller.ensureCompatMod(version) }.getOrDefault(false)
                }
                if (voxyCompatInstalled) {
                    eventViewModel.sendToast(androidText(R.string.ona_voxy_compat_installed))
                }
                // ONA: Forge 1.12 Kotlin mods (Better Foliage...) get the Kotlin runtime (Forgelin)
                val forgelin = withContext(Dispatchers.IO) {
                    runCatching { LegacyKotlinSupport.ensure(version) }.getOrNull()
                }
                if (forgelin != null) {
                    eventViewModel.sendToast(androidText(R.string.ona_forgelin_installed, forgelin), Toast.LENGTH_LONG)
                }
                // ONA: lighten heavy mods through their configs, or put the configs back
                val tunedMods = withContext(Dispatchers.IO) {
                    runCatching {
                        if (AllSettings.tuneHeavyMods.getValue()) {
                            ModPerformanceTuner.apply(version, DeviceProfiler.current()?.tier ?: DeviceTier.HIGH)
                        } else {
                            ModPerformanceTuner.restore(version)
                            emptyList()
                        }
                    }.getOrDefault(emptyList())
                }
                if (tunedMods.isNotEmpty()) {
                    eventViewModel.sendToast(androidText(R.string.ona_tuned_mods, tunedMods.joinToString()), Toast.LENGTH_LONG)
                }
                // ONA: the game's own video settings set once for high FPS (vanilla, Sodium, OptiFine...)
                val bestSettings = withContext(Dispatchers.IO) {
                    runCatching {
                        if (AllSettings.bestGameSettings.getValue()) {
                            BestGameSettings.apply(version, DeviceProfiler.current()?.tier ?: DeviceTier.HIGH)
                        } else {
                            BestGameSettings.reset(version)
                            emptyList()
                        }
                    }.getOrDefault(emptyList())
                }
                if (bestSettings.isNotEmpty()) {
                    eventViewModel.sendToast(androidText(R.string.ona_best_settings_done, bestSettings.joinToString()), Toast.LENGTH_LONG)
                }
                if (!operation.skipModCheck) {
                    // Broken mod setups get their requirements installed automatically first
                    val problems = version.findModProblems()
                    if (problems.isNotEmpty()) {
                        launchGameViewModel.updateOperation(
                            LaunchGameOperation.ResolvingMods(version, quickPlay, operation.skipAccountRefresh)
                        )
                        return@LaunchedEffect
                    }
                }
                // ONA: Voxy needs the Turnip, Kopper Zink and RocksDB plugins
                if (!operation.skipVoxyCheck) {
                    val missing = withContext(Dispatchers.IO) {
                        runCatching { if (VoxySupport.hasVoxy(version)) VoxyInstaller.missingParts() else emptyList() }
                            .getOrDefault(emptyList())
                    }
                    if (missing.isNotEmpty()) {
                        launchGameViewModel.updateOperation(
                            LaunchGameOperation.VoxySetup(version, quickPlay, operation.skipAccountRefresh, missing)
                        )
                        return@LaunchedEffect
                    }
                }
                version.apply {
                    offlineAccountLogin = false
                    quickPlaySingle = quickPlay
                }
                launchGameViewModel.start(
                    activity = activity,
                    version = version,
                    exitActivity = exitActivity,
                    submitError = submitError,
                    quickPlay = quickPlay,
                    skipAccountRefresh = operation.skipAccountRefresh
                )
                launchGameViewModel.updateOperation(LaunchGameOperation.None)
            }
        }
    }
}

@Composable
private fun AccountRefreshFailedDialog(
    error: Throwable,
    onSkip: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .heightIn(max = rememberDialogMaxHeight())
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .padding(all = 6.dp)
                    .heightIn(max = (maxHeight - 12.dp).coerceAtMost(rememberDialogMaxHeight()))
                    .wrapContentHeight(),
                shape = MaterialTheme.shapes.extraLarge,
                color = cardColor(false),
                contentColor = onCardColor(),
                shadowElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.account_refresh_failed_title),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.size(12.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth()
                    ) {
                        AndroidStringText(
                            text = accountErrorText(error),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = stringResource(R.string.account_refresh_failed_skip_message),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.size(16.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        PositionButton(
                            modifier = Modifier.fillMaxWidth(),
                            position = ButtonPosition.Top,
                            onClick = onRetry
                        ) {
                            MarqueeText(text = stringResource(R.string.account_refresh_failed_retry))
                        }
                        PositionFilledTonalButton(
                            modifier = Modifier.fillMaxWidth(),
                            position = ButtonPosition.Middle,
                            onClick = onSkip
                        ) {
                            MarqueeText(text = stringResource(R.string.account_refresh_failed_skip))
                        }
                        PositionFilledTonalButton(
                            modifier = Modifier.fillMaxWidth(),
                            position = ButtonPosition.Bottom,
                            onClick = onCancel
                        ) {
                            MarqueeText(text = stringResource(R.string.generic_cancel))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 启动器背景图片/视频层
 */
@Composable
fun Background(
    viewModel: BackgroundViewModel,
    modifier: Modifier = Modifier,
    allowVideo: Boolean = true
) {
    val blur = AllSettings.backgroundBlur.state
    val opacity = AllSettings.launcherBackgroundOpacity.state
    val backgroundMode = AllSettings.backgroundBlurType.state == BackgroundBlur.Background
    val backgroundBlurEnabled = backgroundMode && blur > 0 && opacity < 100
    val layerCapture = !backgroundMode && blur > 0 && opacity < 100 && viewModel.isValid
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .then(
                if (backgroundBlurEnabled && Build.VERSION.SDK_INT >= 31) {
                    Modifier.blur(blur.dp)
                } else {
                    Modifier
                }
            )
            .backgroundCapture(
                store = viewModel,
                recordContent = layerCapture,
                blurRadiusPx = blur * density.density,
                whiteOverlayAlpha = if (backgroundBlurEnabled) whiteOverlayAlpha(blur) else 0f
            )
    ) {
        if (viewModel.isValid) {
            when {
                viewModel.isVideo && allowVideo -> {
                    VideoPlayer(
                        videoUri = Uri.fromFile(viewModel.backgroundFile),
                        modifier = Modifier.fillMaxSize(),
                        refreshTrigger = viewModel.refreshTrigger,
                        volume = AllSettings.videoBackgroundVolume.state / 100f
                    )
                }
                viewModel.isImage -> {
                    val blurred = viewModel.blurredBackground
                    if (backgroundBlurEnabled && Build.VERSION.SDK_INT < 31 && blurred != null) {
                        Image(
                            bitmap = blurred,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        BackgroundImage(
                            modifier = Modifier.fillMaxSize(),
                            imageFile = viewModel.backgroundFile,
                            refreshTrigger = viewModel.refreshTrigger
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundImage(
    refreshTrigger: Any,
    imageFile: File,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val imageLoader = remember(refreshTrigger) {
        ImageLoader.Builder(context)
            .components { add(GifDecoder.Factory()) }
            .build()
    }
    val request = remember(refreshTrigger) {
        ImageRequest.Builder(context)
            .data(imageFile)
            .allowHardware(false)
            .crossfade(false)
            .build()
    }

    //GIF 的动画帧由绘制 → invalidateSelf → 再绘制的自续循环推进，
    //任何一帧失效丢失都会让动画永久冻结，由帧时钟显式逐帧驱动重绘，保证循环自愈
    val isAnimatedState = remember(refreshTrigger) { mutableStateOf(false) }
    LaunchedEffect(refreshTrigger) {
        isAnimatedState.value = withContext(Dispatchers.IO) {
            imageFile.isGifFile()
        }
    }
    val isAnimated = isAnimatedState.value

    val frameTick = remember { mutableIntStateOf(0) }
    if (isAnimated) {
        LaunchedEffect(Unit) {
            while (isActive) {
                withFrameNanos { }
                frameTick.intValue++
            }
        }
    }

    AsyncImage(
        modifier = modifier.then(
            if (isAnimated) {
                Modifier.drawBehind {
                    frameTick.intValue
                }
            } else {
                Modifier
            }
        ),
        model = request,
        imageLoader = imageLoader,
        contentDescription = null,
        contentScale = ContentScale.Crop
    )
}