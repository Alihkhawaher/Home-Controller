package com.technlov.homecontroller.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.technlov.homecontroller.R
import com.technlov.homecontroller.data.HomeLocation
import com.technlov.homecontroller.location.PhoneLocation
import kotlinx.coroutines.launch

/**
 * Sets the home's location for sunrise and sunset: from this phone's location (asked for only when the user taps the
 * button, approximate is enough) or typed / pasted as "latitude, longitude". [onSave] gets the new location, or null
 * when the user removes it.
 */
@Composable
fun HomeLocationDialog(current: HomeLocation?, onSave: (HomeLocation?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf(current?.text().orEmpty()) }
    var locating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    val parsed = HomeLocation.parse(text)

    val locate: () -> Unit = {
        scope.launch {
            locating = true
            message = null
            when (val result = PhoneLocation.find(context)) {
                is PhoneLocation.Result.Found -> {
                    text = result.location.text()
                    message = R.string.location_found
                }
                PhoneLocation.Result.Off -> message = R.string.location_off
                PhoneLocation.Result.NoPermission -> message = R.string.location_denied
                PhoneLocation.Result.NotFound -> message = R.string.location_not_found
            }
            locating = false
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate() else message = R.string.location_denied
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.WbTwilight, contentDescription = null) },
        title = { Text(stringResource(R.string.location_title)) },
        text = {
            Column {
                Text(stringResource(R.string.location_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { if (PhoneLocation.permitted(context)) locate() else permission.launch(PhoneLocation.PERMISSION) },
                    enabled = !locating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (locating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (locating) R.string.location_finding else R.string.location_use_phone))
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it == R.string.location_found) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        message = null
                    },
                    label = { Text(stringResource(R.string.location_field)) },
                    placeholder = { Text(stringResource(R.string.location_placeholder)) },
                    isError = text.isNotBlank() && parsed == null,
                    supportingText = {
                        Text(stringResource(if (text.isNotBlank() && parsed == null) R.string.location_invalid else R.string.location_field_hint))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(parsed) }, enabled = parsed != null && !locating) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.action_remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
