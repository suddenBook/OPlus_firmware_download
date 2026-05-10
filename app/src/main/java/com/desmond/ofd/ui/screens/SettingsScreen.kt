package com.desmond.ofd.ui.screens

import android.app.LocaleManager
import android.content.Intent
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.desmond.ofd.BuildConfig
import com.desmond.ofd.R
import com.desmond.ofd.update.UpdateChecker
import kotlinx.coroutines.launch

private const val URL_REALME_OTA = "https://github.com/R0rt1z2/realme-ota"
private const val URL_DANIELSPRINGER = "https://roms.danielspringer.at/index.php?view=ota"
private const val URL_MOBILE_MODELS = "https://github.com/KHwang9883/MobileModels"
private const val URL_SOURCE = "https://github.com/suddenBook/OPlus_firmware_download"

private data class LanguageOption(
    val tag: String,
    @param:StringRes val labelRes: Int,
)

private val languageOptions = listOf(
    LanguageOption("", R.string.language_system),
    LanguageOption("en-US", R.string.language_english),
    LanguageOption("zh-Hans", R.string.language_simplified_chinese),
    LanguageOption("zh-Hant", R.string.language_traditional_chinese),
)

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val current: String) : UpdateUiState
    data class Newer(val latestTag: String, val htmlUrl: String, val notes: String?) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val localeManager = remember(ctx) { ctx.getSystemService(LocaleManager::class.java) }
    var selectedLanguageTag by remember(localeManager) {
        mutableStateOf(localeManager.applicationLocales.toLanguageTags())
    }
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var showReleaseDialog by remember { mutableStateOf<UpdateUiState.Newer?>(null) }

    fun open(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    fun setLanguage(tag: String) {
        selectedLanguageTag = tag
        localeManager.applicationLocales = if (tag.isBlank()) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(tag)
        }
    }

    fun checkForUpdate() {
        if (updateState is UpdateUiState.Checking) return
        updateState = UpdateUiState.Checking
        scope.launch {
            updateState = when (val r = UpdateChecker.check(BuildConfig.VERSION_NAME)) {
                is UpdateChecker.Result.UpToDate -> UpdateUiState.UpToDate(r.current)
                is UpdateChecker.Result.Newer -> {
                    val s = UpdateUiState.Newer(r.latestTag, r.htmlUrl, r.notes)
                    showReleaseDialog = s
                    s
                }
                is UpdateChecker.Result.Failed -> UpdateUiState.Failed(r.message)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.nav_info)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.info_language))
            LanguageCard(
                selectedTag = selectedLanguageTag,
                onSelect = ::setLanguage,
            )

            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.info_backends))
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column {
                    LinkRow(
                        title = "realme-ota",
                        subtitle = stringResource(R.string.backend_realme_ota_summary),
                        onClick = { open(URL_REALME_OTA) },
                    )
                    HorizontalDivider()
                    LinkRow(
                        title = "danielspringer.at",
                        subtitle = stringResource(R.string.backend_danielspringer_summary),
                        onClick = { open(URL_DANIELSPRINGER) },
                    )
                    HorizontalDivider()
                    LinkRow(
                        title = "MobileModels",
                        subtitle = stringResource(R.string.backend_mobile_models_summary),
                        onClick = { open(URL_MOBILE_MODELS) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.info_about))
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.app_version)) },
                        supportingContent = { Text(BuildConfig.VERSION_NAME) },
                    )
                    HorizontalDivider()
                    UpdateRow(state = updateState, onCheck = ::checkForUpdate)
                    HorizontalDivider()
                    LinkRow(
                        title = stringResource(R.string.source_code),
                        subtitle = URL_SOURCE,
                        onClick = { open(URL_SOURCE) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    showReleaseDialog?.let { release ->
        AlertDialog(
            onDismissRequest = { showReleaseDialog = null },
            title = { Text(stringResource(R.string.info_new_version_available, release.latestTag)) },
            text = {
                Text(release.notes ?: stringResource(R.string.info_no_release_notes))
            },
            confirmButton = {
                TextButton(onClick = {
                    showReleaseDialog = null
                    open(release.htmlUrl)
                }) { Text(stringResource(R.string.info_open_release_page)) }
            },
            dismissButton = {
                TextButton(onClick = { showReleaseDialog = null }) {
                    Text(stringResource(R.string.dismiss))
                }
            },
        )
    }
}

@Composable
private fun LanguageCard(selectedTag: String, onSelect: (String) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column {
            languageOptions.forEachIndexed { index, option ->
                LanguageRow(
                    option = option,
                    selected = option.tag == selectedTag,
                    onClick = { onSelect(option.tag) },
                )
                if (index != languageOptions.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LanguageRow(option: LanguageOption, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(option.labelRes)) },
        trailingContent = {
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = stringResource(R.string.state_selected),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun UpdateRow(state: UpdateUiState, onCheck: () -> Unit) {
    val (subtitle, subtitleColor) = when (state) {
        UpdateUiState.Idle -> stringResource(R.string.info_tap_to_check) to MaterialTheme.colorScheme.onSurfaceVariant
        UpdateUiState.Checking -> stringResource(R.string.checking) to MaterialTheme.colorScheme.onSurfaceVariant
        is UpdateUiState.UpToDate -> stringResource(R.string.info_up_to_date) to MaterialTheme.colorScheme.primary
        is UpdateUiState.Newer -> stringResource(R.string.info_new_version_short, state.latestTag) to MaterialTheme.colorScheme.primary
        is UpdateUiState.Failed -> stringResource(R.string.info_check_failed, state.message) to MaterialTheme.colorScheme.error
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.info_check_for_updates)) },
        supportingContent = {
            Text(subtitle, color = subtitleColor)
        },
        trailingContent = {
            if (state is UpdateUiState.Checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = Modifier.clickable(enabled = state !is UpdateUiState.Checking, onClick = onCheck),
    )
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = stringResource(R.string.open_externally),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}
