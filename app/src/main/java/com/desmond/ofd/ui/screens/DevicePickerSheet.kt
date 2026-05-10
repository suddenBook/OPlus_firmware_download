package com.desmond.ofd.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.desmond.ofd.R
import com.desmond.ofd.catalog.DeviceEntry
import com.desmond.ofd.device.Brand

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DevicePickerSheet(
    catalog: List<DeviceEntry>,
    currentSelection: DeviceEntry?,
    autoSuggest: DeviceEntry?,
    onSelect: (DeviceEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filtered = remember(query, catalog) {
        if (query.isBlank()) {
            catalog
        } else {
            catalog.filter { entry ->
                entry.marketingName.contains(query, ignoreCase = true) ||
                    entry.model.contains(query, ignoreCase = true)
            }
        }
    }
    val byBrand: Map<Brand, List<DeviceEntry>> = filtered.groupBy { it.brand }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.pick_device),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.heightIn(min = 8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(pluralStringResource(R.plurals.search_devices, catalog.size, catalog.size))
                },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.clear),
                            )
                        }
                    }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 600.dp),
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.no_devices_match, query),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                Brand.entries.forEach { brand ->
                    val devices = byBrand[brand].orEmpty()
                    if (devices.isEmpty()) return@forEach

                    stickyHeader(key = "header-${brand.name}") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = brand.display,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(devices, key = { entry -> "${brand.name}-${entry.model}" }) { entry ->
                        val regionsLabel = entry.regions.joinToString("/") { it.label }
                        ListItem(
                            headlineContent = { Text(entry.marketingName) },
                            supportingContent = {
                                Text(
                                    if (regionsLabel.isNotEmpty())
                                        "${entry.model}  ·  $regionsLabel"
                                    else entry.model
                                )
                            },
                            leadingContent = if (entry.model == autoSuggest?.model) {
                                {
                                    Icon(
                                        Icons.Filled.Star,
                                        contentDescription = stringResource(R.string.your_device),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else null,
                            trailingContent = if (entry.model == currentSelection?.model) {
                                {
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        contentDescription = stringResource(R.string.state_selected),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else null,
                            modifier = Modifier.clickable { onSelect(entry) },
                        )
                    }
                }
            }
        }
    }
}
