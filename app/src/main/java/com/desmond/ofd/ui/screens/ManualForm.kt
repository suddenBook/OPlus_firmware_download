package com.desmond.ofd.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.desmond.ofd.R
import com.desmond.ofd.backend.realmeota.data.OtaRequestParams
import com.desmond.ofd.backend.realmeota.data.Region
import com.desmond.ofd.catalog.DeviceEntry

private val regionDefaultNv: Map<Region, String> = mapOf(
    Region.CN to "10010111",
    Region.EU to "01000100",
    Region.IN to "00011011",
    Region.GL to "00011011",
    Region.NA to "00011011",
)

private val knownDefaultNvIds: Set<String> = regionDefaultNv.values.toSet()

private val ruiOptions: List<RuiOption> = (1..7).map { rui ->
    val android = 10 + rui - 1
    val colorOs = if (rui == 1) 7 else 11 + rui - 2
    RuiOption(
        value = rui,
        androidVersion = android,
        colorOsVersion = colorOs,
    )
}

private data class RuiOption(
    val value: Int,
    val androidVersion: Int,
    val colorOsVersion: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualForm(
    catalog: List<DeviceEntry>,
    initialDevice: DeviceEntry?,
    initialOtaVersion: String,
    initialRegion: Region,
    initialNvId: String,
    initialRuiVersion: Int,
    isLoading: Boolean,
    onSubmit: (OtaRequestParams) -> Unit,
) {
    var selectedDevice by remember(initialDevice) { mutableStateOf(initialDevice) }
    var otaVersion by remember(initialOtaVersion) { mutableStateOf(initialOtaVersion) }
    var ruiVersion by remember(initialRuiVersion) { mutableIntStateOf(initialRuiVersion.coerceIn(1, 7)) }
    var region by remember(initialRegion) { mutableStateOf(initialRegion) }
    var nvId by remember(initialNvId) { mutableStateOf(initialNvId) }
    var imei by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }

    // Auto-match region to selected device's known sale regions.
    LaunchedEffect(selectedDevice) {
        val device = selectedDevice ?: return@LaunchedEffect
        if (device.regions.isNotEmpty() && region !in device.regions) {
            region = device.regions.first()
        }
    }

    // Auto-fill NV ID with the regional default — but only if the user hasn't typed
    // a custom value (we keep it stable when nvId equals one of the known regional defaults).
    LaunchedEffect(region) {
        if (nvId.isBlank() || nvId in knownDefaultNvIds) {
            nvId = regionDefaultNv[region].orEmpty()
        }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.manual_mode),
                style = MaterialTheme.typography.labelLarge.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            DevicePickerField(
                label = stringResource(R.string.device),
                selected = selectedDevice,
                onClick = { showPicker = true },
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = otaVersion,
                onValueChange = { otaVersion = it },
                label = { Text(stringResource(R.string.ota_version)) },
                supportingText = { Text(stringResource(R.string.ota_version_help)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(12.dp))
            RuiVersionField(
                value = ruiVersion,
                onValueChange = { ruiVersion = it },
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.region),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                Region.entries.forEachIndexed { i, r ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(i, Region.entries.size),
                        onClick = { region = r },
                        selected = region == r,
                        label = { Text(r.label) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = nvId,
                onValueChange = { nvId = it },
                label = { Text(stringResource(R.string.nv_id)) },
                supportingText = { Text(stringResource(R.string.nv_id_help)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = imei,
                onValueChange = { imei = it },
                label = { Text(stringResource(R.string.imei)) },
                supportingText = { Text(stringResource(R.string.optional_beta_channel)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val device = selectedDevice ?: return@Button
                    onSubmit(
                        OtaRequestParams(
                            model = device.model,
                            otaVersion = otaVersion,
                            ruiVersion = ruiVersion,
                            nvIdentifier = nvId.ifBlank { null },
                            imei0 = imei.ifBlank { null },
                            region = region,
                        )
                    )
                },
                enabled = selectedDevice != null && !isLoading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.checking))
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.check_for_firmware))
                }
            }
        }
    }

    if (showPicker) {
        DevicePickerSheet(
            catalog = catalog,
            currentSelection = selectedDevice,
            autoSuggest = initialDevice,
            onSelect = {
                selectedDevice = it
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuiVersionField(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = ruiOptions.firstOrNull { it.value == value } ?: ruiOptions.last()
    val selectedTitle = stringResource(R.string.rui_option_title, selected.colorOsVersion)
    val selectedSubtitle = stringResource(
        R.string.rui_option_subtitle,
        selected.androidVersion,
        selected.value,
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = "$selectedTitle  ·  $selectedSubtitle",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.realmeui_version)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ruiOptions.forEach { option ->
                val title = stringResource(R.string.rui_option_title, option.colorOsVersion)
                val subtitle = stringResource(
                    R.string.rui_option_subtitle,
                    option.androidVersion,
                    option.value,
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(title)
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onValueChange(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePickerField(
    label: String,
    selected: DeviceEntry?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                val display = selected?.let { entry ->
                    val regions = entry.regions.joinToString("/") { it.label }
                    if (regions.isNotEmpty()) "${entry.marketingName}  ·  ${entry.model}  ·  $regions"
                    else "${entry.marketingName}  ·  ${entry.model}"
                } ?: stringResource(R.string.tap_to_choose)
                Text(
                    text = display,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
