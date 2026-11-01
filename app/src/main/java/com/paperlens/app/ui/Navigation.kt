package com.paperlens.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.paperlens.app.app
import com.paperlens.app.ui.word.WordScreen
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.paperlens.app.ExternalRequest
import com.paperlens.app.ui.document.DocumentScreen
import com.paperlens.app.ui.draw.AnnotateScreen
import com.paperlens.app.ui.draw.SignatureScreen
import com.paperlens.app.ui.edit.EditPagesScreen
import com.paperlens.app.ui.edit.CropPageScreen
import com.paperlens.app.ui.edit.RegionEditScreen
import com.paperlens.app.ui.edit.RegionTool
import com.paperlens.app.ui.files.FilesTab
import com.paperlens.app.ui.idcard.IdCardScreen
import com.paperlens.app.ui.main.MainScreen
import com.paperlens.app.ui.page.PageScreen
import com.paperlens.app.ui.qr.QrHistoryScreen
import com.paperlens.app.ui.translate.TranslatePagesScreen
import com.paperlens.app.ui.settings.MoreSettingsScreen
import com.paperlens.app.ui.settings.ScanSettingsScreen
import com.paperlens.app.ui.settings.SettingsSection
import com.paperlens.app.ui.settings.SettingsSectionScreen
import com.paperlens.app.ui.textedit.TextEditScreen
import com.paperlens.app.ui.tools.PagesMode
import com.paperlens.app.ui.tools.PagesToolScreen
import com.paperlens.app.ui.tools.ToolNavigator
import com.paperlens.app.ui.tools.ToolRunnerHost
import com.paperlens.app.ui.tools.rememberToolRunner

private object Routes {
    const val MAIN = "main"
    const val DOCUMENT = "document/{documentId}"
    const val PAGE = "page/{documentId}/{index}"
    const val EDIT_TEXT = "edittext/{documentId}/{index}"
    const val EDIT_PAGES = "editpages/{documentId}/{index}?pages={pages}"
    const val REGION = "region/{pageId}/{tool}"
    const val SIGN = "sign/{pageId}"
    const val ANNOTATE = "annotate/{pageId}"
    const val SCAN_SETTINGS = "settings/scan"
    const val MORE_SETTINGS = "settings/more"
    const val SETTINGS_SECTION = "settings/section/{section}"
    const val QR = "qr"
    const val ID_CARD = "idcard"
    const val FOLDER = "folder/{folderId}"
    const val PAGES_TOOL = "pages/{documentId}/{mode}"
    const val TRANSLATE = "translate/{documentId}/{index}"
    fun translate(documentId: Long, index: Int) = "translate/$documentId/$index"

    fun document(id: Long) = "document/$id"
    fun page(documentId: Long, index: Int) = "page/$documentId/$index"
    fun editText(documentId: Long, index: Int) = "edittext/$documentId/$index"
    /** [selected]: pages picked in the document (long-press), which the editor's filter applies to. */
    fun editPages(documentId: Long, index: Int, selected: List<Long> = emptyList()) =
        "editpages/$documentId/$index" + if (selected.isEmpty()) "" else "?pages=${selected.joinToString(",")}"
    fun region(pageId: Long, tool: RegionTool) = "region/$pageId/${tool.name}"
    fun sign(pageId: Long) = "sign/$pageId"
    fun annotate(pageId: Long) = "annotate/$pageId"
    fun folder(id: Long) = "folder/$id"
    fun pagesTool(documentId: Long, mode: PagesMode) = "pages/$documentId/${mode.name}"
    fun settingsSection(section: SettingsSection) = "settings/section/${section.name}"
}

private fun toolNavigator(nav: NavHostController, openToolsTab: () -> Unit = {}) = ToolNavigator(
    openDocument = { nav.navigate(Routes.document(it)) },
    openPage = { doc, index -> nav.navigate(Routes.page(doc, index)) },
    openIdCard = { nav.navigate(Routes.ID_CARD) },
    openCodeHistory = { nav.navigate(Routes.QR) },
    openPagesTool = { doc, mode -> nav.navigate(Routes.pagesTool(doc, mode)) },
    openToolsTab = openToolsTab,
    openSign = { pageId -> nav.navigate(Routes.sign(pageId)) },
    openErase = { pageId -> nav.navigate(Routes.region(pageId, RegionTool.ERASE)) },
    openTranslate = { doc, index -> nav.navigate(Routes.translate(doc, index)) },
)

@Composable
fun PaperLensNavHost(externalRequest: ExternalRequest?, onExternalRequestHandled: () -> Unit) {
    val nav = rememberNavController()

    // Shortcuts and shared files are handled by the main screen; bring it to the front first.
    LaunchedEffect(externalRequest) {
        if (externalRequest != null) nav.popBackStack(Routes.MAIN, inclusive = false)
    }

    NavHost(navController = nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(
                nav = toolNavigator(nav),
                openScanSettings = { nav.navigate(Routes.SCAN_SETTINGS) },
                openMoreSettings = { nav.navigate(Routes.MORE_SETTINGS) },
                openAppearance = { nav.navigate(Routes.settingsSection(SettingsSection.APPEARANCE)) },
                openFolder = { nav.navigate(Routes.folder(it)) },
                externalRequest = externalRequest,
                onExternalRequestHandled = onExternalRequestHandled,
            )
        }
        composable(Routes.FOLDER, arguments = listOf(navArgument("folderId") { type = NavType.LongType })) { entry ->
            val folderId = entry.arguments!!.getLong("folderId")
            val runner = rememberToolRunner(toolNavigator(nav))
            runner.targetFolderId = folderId
            Box(Modifier.fillMaxSize()) {
                FilesTab(runner, folderId = folderId, openFolder = { nav.navigate(Routes.folder(it)) }, onBack = { nav.popBackStack() })
                ToolRunnerHost(runner)
            }
        }
        composable(Routes.DOCUMENT, arguments = listOf(navArgument("documentId") { type = NavType.LongType })) { entry ->
            val documentId = entry.arguments!!.getLong("documentId")
            // Word documents (converted with To Word, or imported .docx files) open in the Word editor.
            val context = LocalContext.current
            var isWord by remember(documentId) { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(documentId) { isWord = context.app.repository.getDocument(documentId)?.isWord == true }
            when (isWord) {
                null -> Box(Modifier.fillMaxSize())
                true -> WordScreen(documentId = documentId, onBack = { nav.popBackStack() })
                false -> DocumentScreen(
                documentId = documentId,
                nav = toolNavigator(nav),
                onBack = { nav.popBackStack() },
                openPage = { index -> nav.navigate(Routes.page(documentId, index)) },
                openEditText = { index -> nav.navigate(Routes.editText(documentId, index)) },
                openEditPages = { index, selected -> nav.navigate(Routes.editPages(documentId, index, selected)) },
                openSign = { pageId -> nav.navigate(Routes.sign(pageId)) },
                openCrop = { pageId -> nav.navigate(Routes.region(pageId, RegionTool.CROP)) },
            )
            }
        }
        composable(
            Routes.EDIT_PAGES,
            arguments = listOf(
                navArgument("documentId") { type = NavType.LongType },
                navArgument("index") { type = NavType.IntType },
                navArgument("pages") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val documentId = entry.arguments!!.getLong("documentId")
            EditPagesScreen(
                documentId = documentId,
                startIndex = entry.arguments!!.getInt("index"),
                selectedPages = entry.arguments!!.getString("pages")?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty(),
                onClose = { nav.popBackStack() },
                openEditText = { index -> nav.navigate(Routes.editText(documentId, index)) },
                openRegion = { pageId, tool -> nav.navigate(Routes.region(pageId, tool)) },
                openSign = { nav.navigate(Routes.sign(it)) },
                openAnnotate = { nav.navigate(Routes.annotate(it)) },
                openPagesTool = { mode -> nav.navigate(Routes.pagesTool(documentId, mode)) },
                nav = toolNavigator(nav),
            )
        }
        composable(
            Routes.REGION,
            arguments = listOf(
                navArgument("pageId") { type = NavType.LongType },
                navArgument("tool") { type = NavType.StringType },
            ),
        ) { entry ->
            val tool = RegionTool.valueOf(entry.arguments!!.getString("tool")!!)
            if (tool == RegionTool.CROP) {
                CropPageScreen(pageId = entry.arguments!!.getLong("pageId"), onDone = { nav.popBackStack() })
            } else {
                RegionEditScreen(pageId = entry.arguments!!.getLong("pageId"), tool = tool, onDone = { nav.popBackStack() })
            }
        }
        composable(
            Routes.PAGE,
            arguments = listOf(
                navArgument("documentId") { type = NavType.LongType },
                navArgument("index") { type = NavType.IntType },
            ),
        ) { entry ->
            val documentId = entry.arguments!!.getLong("documentId")
            PageScreen(
                documentId = documentId,
                initialIndex = entry.arguments!!.getInt("index"),
                onBack = { nav.popBackStack() },
                openSignature = { nav.navigate(Routes.sign(it)) },
                openAnnotate = { nav.navigate(Routes.annotate(it)) },
                openEditText = { index -> nav.navigate(Routes.editText(documentId, index)) },
                openCrop = { pageId -> nav.navigate(Routes.region(pageId, RegionTool.CROP)) },
                openTranslate = { index -> nav.navigate(Routes.translate(documentId, index)) },
            )
        }
        composable(
            Routes.EDIT_TEXT,
            arguments = listOf(
                navArgument("documentId") { type = NavType.LongType },
                navArgument("index") { type = NavType.IntType },
            ),
        ) { entry ->
            TextEditScreen(
                documentId = entry.arguments!!.getLong("documentId"),
                startIndex = entry.arguments!!.getInt("index"),
                onClose = { nav.popBackStack() },
            )
        }
        composable(
            Routes.PAGES_TOOL,
            arguments = listOf(
                navArgument("documentId") { type = NavType.LongType },
                navArgument("mode") { type = NavType.StringType },
            ),
        ) { entry ->
            PagesToolScreen(
                documentId = entry.arguments!!.getLong("documentId"),
                mode = PagesMode.valueOf(entry.arguments!!.getString("mode")!!),
                onBack = { nav.popBackStack() },
                openDocument = { id -> nav.navigate(Routes.document(id)) { popUpTo(Routes.MAIN) } },
            )
        }
        composable(Routes.SIGN, arguments = listOf(navArgument("pageId") { type = NavType.LongType })) { entry ->
            SignatureScreen(pageId = entry.arguments!!.getLong("pageId"), onDone = { nav.popBackStack() })
        }
        composable(Routes.ANNOTATE, arguments = listOf(navArgument("pageId") { type = NavType.LongType })) { entry ->
            AnnotateScreen(pageId = entry.arguments!!.getLong("pageId"), onDone = { nav.popBackStack() })
        }
        composable(Routes.SCAN_SETTINGS) { ScanSettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.MORE_SETTINGS) {
            MoreSettingsScreen(onBack = { nav.popBackStack() }, openSection = { nav.navigate(Routes.settingsSection(it)) })
        }
        composable(Routes.SETTINGS_SECTION, arguments = listOf(navArgument("section") { type = NavType.StringType })) { entry ->
            SettingsSectionScreen(SettingsSection.valueOf(entry.arguments!!.getString("section")!!), onBack = { nav.popBackStack() })
        }
        composable(
            Routes.TRANSLATE,
            arguments = listOf(
                navArgument("documentId") { type = NavType.LongType },
                navArgument("index") { type = NavType.IntType },
            ),
        ) { entry ->
            TranslatePagesScreen(
                documentId = entry.arguments!!.getLong("documentId"),
                startIndex = entry.arguments!!.getInt("index"),
                onClose = { nav.popBackStack() },
                openDocument = { id ->
                    nav.popBackStack()
                    nav.navigate(Routes.document(id))
                },
            )
        }
        composable(Routes.QR) { QrHistoryScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ID_CARD) {
            IdCardScreen(
                onBack = { nav.popBackStack() },
                openDocument = { id -> nav.navigate(Routes.document(id)) { popUpTo(Routes.MAIN) } },
            )
        }
    }
}
