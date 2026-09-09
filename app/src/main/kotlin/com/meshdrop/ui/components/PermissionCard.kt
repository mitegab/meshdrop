package com.meshdrop.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meshdrop.util.AppPermissions

/**
 * Shown at the top of the Home screen while a permission we cannot work without is missing.
 */
@Composable
fun PermissionCard(
    missing: List<String>,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (missing.isEmpty()) return
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "MeshDrop needs permission to find nearby devices",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 8.dp),
            )
            missing.forEach { permission ->
                Text(
                    text = "· ${AppPermissions.rationale(permission)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = onRequest) {
                Text("Grant")
            }
        }
    }
}
