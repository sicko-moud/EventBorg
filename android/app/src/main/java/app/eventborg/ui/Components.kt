package app.eventborg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.eventborg.R
import app.eventborg.data.Ev
import app.eventborg.data.Section
import coil3.compose.AsyncImage

@Composable
fun CategoryArt(c: String, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Box(modifier.background(catColor(c, isSystemInDarkTheme())), contentAlignment = Alignment.Center) {
        Text(catEmoji(c), fontSize = (size.value).sp)
    }
}

@Composable
fun Thumb(ev: Ev, modifier: Modifier = Modifier) {
    Box(modifier) {
        CategoryArt(ev.e.c, Modifier.fillMaxSize())
        if (ev.e.img != null) {
            AsyncImage(model = ev.e.img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer, onColor: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(color = color, contentColor = onColor, shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
fun StatusTag(ev: Ev) {
    when (ev.e.st) {
        "cancelled" -> Tag(stringResource(R.string.cancelled), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        "moved" -> Tag(stringResource(R.string.moved), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
fun StarButton(on: Boolean, onLabel: Int, offLabel: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (on) Icons.Filled.Star else Ic.StarBorder,
            contentDescription = stringResource(if (on) onLabel else offLabel),
            tint = if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EventCard(ev: Ev, section: Section?, saved: Boolean, onSave: () -> Unit, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val locale = uiLocale()
    val e = ev.e
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(ev, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.t,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (ev.cancelled) TextDecoration.LineThrough else null,
            )
            Text(
                listOfNotNull(cardWhen(ctx, ev, section, locale), venueText(e)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusTag(ev)
                Tag(stringResource(catLabel(e.c)))
                priceText(ctx, e)?.let {
                    Tag(it, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                e.src.firstOrNull()?.let { s ->
                    val more = if (e.src.size > 1) " +${e.src.size - 1}" else ""
                    Text(
                        stringResource(kindLabel(s.kind)) + more,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        StarButton(saved, R.string.unsave, R.string.save, onSave)
    }
}

@Composable
fun SectionHeader(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        )
    }
}

@Composable
fun sectionLabel(s: Section): String = stringResource(
    when (s) {
        Section.TODAY -> R.string.sec_today
        Section.TOMORROW -> R.string.sec_tomorrow
        Section.WEEK -> R.string.sec_week
        Section.WEEKEND -> R.string.sec_weekend
        Section.NEXT_WEEK -> R.string.sec_next_week
        Section.LATER -> R.string.sec_later
        Section.ONGOING -> R.string.sec_ongoing
    },
)

@Composable
fun Message(emoji: String, title: String, body: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(emoji, fontSize = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun Banner(text: String, action: String, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}
