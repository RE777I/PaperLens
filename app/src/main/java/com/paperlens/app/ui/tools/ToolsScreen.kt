package com.paperlens.app.ui.tools

import com.paperlens.app.S
import com.paperlens.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paperlens.app.ui.components.ToolButton
import com.paperlens.app.ui.home.SearchField

/** Tools tab: every tool, grouped like Scan / Import / Convert / Edit / Utilities. */
@Composable
fun ToolsTab(runner: ToolRunner) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val sections = ToolSection.entries.map { section ->
        section to Tool.entries.filter { it.section == section && (query.isBlank() || it.label.contains(query.trim(), ignoreCase = true)) }
    }.filter { it.second.isNotEmpty() }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(
                Modifier
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searching) {
                    SearchField(query, { query = it }, S.str(R.string.search_tools), Modifier.weight(1f))
                } else {
                    Text(S.str(R.string.tools), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Filled.Search, S.str(R.string.search_tools)) }
            }
        }
        if (!searching) item { Banner() }
        items(sections, key = { it.first.name }) { (section, tools) ->
            Column(Modifier.padding(horizontal = 8.dp)) {
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 4.dp),
                )
                tools.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { tool -> ToolButton(tool.label, tool.icon, tool.tint, { runner.run(tool) }, Modifier.weight(1f)) }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Banner() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Box(
            Modifier
                .background(Brush.horizontalGradient(listOf(Color(0xFFE3ECFF), Color(0xFFDDF5EF))))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(S.str(R.string.toolkit), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = Color(0xFF14213D))
                    Text(
                        S.str(R.string.scan_convert_and_edit_documents_everything_r),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF3A4660),
                    )
                }
                Icon(Icons.Filled.Shield, null, Modifier.size(48.dp), tint = Color(0xFF14A38B))
            }
        }
    }
}
