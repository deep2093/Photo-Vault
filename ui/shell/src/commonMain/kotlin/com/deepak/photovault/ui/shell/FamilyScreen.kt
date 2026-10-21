package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader

/** Shown in setup and on this screen: v1 keeps folders apart, it does not keep them secret. */
const val FOLDER_PRIVACY_NOTE =
    "Each person's photos go into their own folder, and PhotoVault only shows you yours. Anyone who knows " +
        "the drive password can still open every folder from a computer, so this works for a family that trusts each other."

@Composable
fun FamilyScreen() {
    val theme = LocalGlassTheme.current
    GlassListScreen(title = "Family", subtitle = "${SampleData.members.size} people share this drive") {
        item {
            GlassCard(Modifier.cardWidth()) {
                Row {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = theme.accent)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("How privacy works", color = theme.onGlass, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(FOLDER_PRIVACY_NOTE, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item {
            GlassCard(Modifier.cardWidth()) {
                SectionHeader("People")
                SampleData.members.forEach { member -> MemberRow(member) }
                Spacer(Modifier.padding(4.dp))
                GlassButton("Add a family member", onClick = {}, icon = Icons.Rounded.PersonAdd)
            }
        }
        item {
            GlassCard(Modifier.cardWidth()) {
                SectionHeader("Shared albums")
                SampleData.albums.filter { it.shared }.forEach { album ->
                    InfoRow(Icons.Rounded.Groups, album.name, "${album.count} items")
                }
            }
        }
    }
}

@Composable
private fun MemberRow(member: SampleMember) {
    val theme = LocalGlassTheme.current
    Row(
        Modifier.padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(theme.accent), contentAlignment = Alignment.Center) {
            Text(member.name.take(1), color = theme.onAccent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (member.isYou) "${member.name} (this account)" else member.name,
                color = theme.onGlass,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${member.photos} photos · ${member.phones.joinToString()}",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Last backup ${member.lastBackup}", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Rounded.Folder, contentDescription = "Own folder", tint = theme.onGlassSecondary)
    }
}
