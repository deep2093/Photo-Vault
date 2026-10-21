package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.GlassCardShape
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.glassPanel

@Composable
fun AlbumsScreen() {
    GlassScreen(
        title = "Albums",
        subtitle = "${SampleData.albums.size} albums · 2 shared with family",
        actions = { GlassIconButton(Icons.Rounded.Add, "New album", onClick = {}) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(SampleData.albums, key = { it.name }) { album -> AlbumCard(album) }
        }
    }
}

@Composable
private fun AlbumCard(album: SampleAlbum) {
    val theme = LocalGlassTheme.current
    val hues = theme.sampleHues
    Column(
        Modifier
            .fillMaxWidth()
            .glassPanel(GlassCardShape)
            .clickable(role = Role.Button, onClick = {})
            .semantics(mergeDescendants = true) {
                contentDescription = "${album.name}, ${album.count} items" + if (album.shared) ", shared" else ""
            }
            .padding(8.dp),
    ) {
        // A 2 x 2 cover mosaic of the album's first photos.
        Column(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp))) {
            album.coverHues.chunked(2).forEach { row ->
                Row(Modifier.weight(1f)) {
                    row.forEach { hue ->
                        Box(Modifier.weight(1f).fillMaxSize().padding(1.dp).background(hues[hue % hues.size]))
                    }
                }
            }
        }
        Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(album.name, color = theme.onGlass, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${album.count} items", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (album.shared) {
                Icon(Icons.Rounded.Groups, contentDescription = null, tint = theme.accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}
