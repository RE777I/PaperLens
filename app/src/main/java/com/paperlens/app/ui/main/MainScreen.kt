package com.paperlens.app.ui.main

import com.paperlens.app.S
import com.paperlens.app.R

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.paperlens.app.ExternalRequest
import com.paperlens.app.app
import com.paperlens.app.export.PdfImporter
import com.paperlens.app.ui.files.FilesTab
import com.paperlens.app.ui.home.HomeTab
import com.paperlens.app.ui.me.MeTab
import com.paperlens.app.ui.tools.Tool
import com.paperlens.app.ui.tools.isDocx
import com.paperlens.app.ui.tools.ToolNavigator
import com.paperlens.app.ui.tools.ToolRunnerHost
import com.paperlens.app.ui.tools.ToolsTab
import com.paperlens.app.ui.tools.rememberToolRunner

enum class MainTab(private val labelRes: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME(R.string.home, Icons.Outlined.Home, Icons.Filled.Home),
    FILES(R.string.files, Icons.Outlined.Description, Icons.Filled.Description),
    TOOLS(R.string.tools, Icons.Outlined.Widgets, Icons.Filled.Widgets),
    ME(R.string.me, Icons.Outlined.Person, Icons.Filled.Person),
;
    val label: String get() = S.str(labelRes)
}

/** Bottom-navigation shell: Home, Files, Tools, Me, with the camera button on Home and Files. */
@Composable
fun MainScreen(
    nav: ToolNavigator,
    openScanSettings: () -> Unit,
    openMoreSettings: () -> Unit,
    openAppearance: () -> Unit,
    openFolder: (Long) -> Unit,
    externalRequest: ExternalRequest?,
    onExternalRequestHandled: () -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    val navigator = ToolNavigator(
        openDocument = nav.openDocument,
        openPage = nav.openPage,
        openIdCard = nav.openIdCard,
        openCodeHistory = nav.openCodeHistory,
        openPagesTool = nav.openPagesTool,
        openToolsTab = { tab = MainTab.TOOLS },
        openSign = nav.openSign,
        openErase = nav.openErase,
    )
    val runner = rememberToolRunner(navigator)

    // Settings → Scan → Start with Camera: open the scanner once per app launch.
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoStarted && externalRequest == null && context.app.settings.current().startWithCamera) runner.run(Tool.SMART_SCAN)
        autoStarted = true
    }

    // Launcher shortcuts and files shared from other apps.
    LaunchedEffect(externalRequest) {
        when (val request = externalRequest) {
            null -> return@LaunchedEffect
            ExternalRequest.Scan -> runner.run(Tool.SMART_SCAN)
            ExternalRequest.ScanQr -> runner.run(Tool.SCAN_CODE)
            is ExternalRequest.Import -> {
                val repo = context.app.repository
                if (request.uris.any { isDocx(context, it) }) {
                    runner.onFilesPicked(request.uris)
                } else if (request.isPdf) {
                    runner.busy.run(S.str(R.string.importing_pdf)) { nav.openDocument(PdfImporter.import(context, request.uris.first(), repo)) }
                } else {
                    runner.busy.run(S.str(R.string.importing_images)) {
                        nav.openDocument(repo.createFromUris(request.uris, repo.defaultTitle(S.str(R.string.import_text)), rawPhotos = true))
                    }
                }
            }
        }
        onExternalRequestHandled()
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    MainTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(if (tab == t) t.selectedIcon else t.icon, null) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = Color.Transparent,
                            ),
                        )
                    }
                }
            },
            floatingActionButton = {
                if (tab == MainTab.HOME || tab == MainTab.FILES) {
                    FloatingActionButton(
                        onClick = { runner.run(Tool.SMART_SCAN) },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        modifier = Modifier
                            .size(68.dp)
                            .semantics { contentDescription = S.str(R.string.scan_a_document) },
                    ) { Icon(Icons.Filled.CameraAlt, null, Modifier.size(30.dp)) }
                }
            },
        ) { padding ->
            Box(Modifier.padding(bottom = padding.calculateBottomPadding())) {
                when (tab) {
                    MainTab.HOME -> HomeTab(runner, openMoreSettings, openFiles = { tab = MainTab.FILES })
                    MainTab.FILES -> FilesTab(runner, folderId = null, openFolder = openFolder)
                    MainTab.TOOLS -> ToolsTab(runner)
                    MainTab.ME -> MeTab(
                        openScanSettings,
                        openMoreSettings,
                        openFiles = { tab = MainTab.FILES },
                        openCodeHistory = nav.openCodeHistory,
                        openAppearance = openAppearance,
                    )
                }
            }
        }
        ToolRunnerHost(runner)
    }
}
