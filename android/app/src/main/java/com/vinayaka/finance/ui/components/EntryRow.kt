package com.vinayaka.finance.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.EntryType
import com.vinayaka.finance.ui.theme.LocalMoneyColors
import com.vinayaka.finance.util.formatRupees
import com.vinayaka.finance.util.prettyDay

fun typeIcon(type: EntryType): ImageVector = when (type) {
    EntryType.EXPENDITURE -> Icons.AutoMirrored.Rounded.ReceiptLong
    EntryType.DONATION -> Icons.Rounded.VolunteerActivism
    EntryType.OTHER -> Icons.Rounded.Savings
}

/**
 * One record. For income, the line under the name says who collected it, or "Not collected" (tap it, via
 * [onAssign], to say who collected it).
 */
@Composable
fun EntryRow(
    entry: Entry,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onAssign: (() -> Unit)? = null,
) {
    val money = LocalMoneyColors.current
    val tint = if (entry.type.isIncome) money.income else money.expense
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().clip(shape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(typeIcon(entry.type), null, Modifier.size(22.dp), tint = tint)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name.ifBlank { entry.type.label },
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(
                    entry.category.takeIf { it.isNotBlank() },
                    entry.description.takeIf { it.isNotBlank() },
                    prettyDay(entry.date),
                ).joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.person.isNotBlank()) {
                    Text(
                        (if (entry.type.isIncome) "Collected by " else "Paid by ") + entry.person,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = if (onAssign != null) Modifier.clickable(onClick = onAssign) else Modifier,
                    )
                } else if (entry.type.isIncome) {
                    Text(
                        "Not collected" + if (onAssign != null) " · tap to set" else "",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold,
                        modifier = if (onAssign != null) Modifier.clickable(onClick = onAssign) else Modifier,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (entry.amount == 0L) "In kind" else (if (entry.type.isIncome) "+" else "−") + formatRupees(entry.amount),
                    color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                )
                if (entry.dirty) {
                    Icon(Icons.Rounded.CloudOff, "Not synced yet", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
