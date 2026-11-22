package app.eventborg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.alpha
import app.eventborg.ui.theme.G
import app.eventborg.ui.theme.grayscale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import app.eventborg.ui.theme.PrimaryButton
import app.eventborg.R
import app.eventborg.data.Ev
import app.eventborg.data.Section
import coil3.compose.AsyncImage

@Composable
fun CategoryArt(c: String, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Box(modifier.background(G.Raised), contentAlignment = Alignment.Center) {
        Text(catEmoji(c), fontSize = (size.value).sp, modifier = Modifier.grayscale().alpha(0.7f))
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
fun Tag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = G.Muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.border(1.dp, G.Line, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// Status: a 6dp dot and a small label in the accent (or the error tone for cancelled).
@Composable
fun StatusLabel(text: String, color: Color = G.Accent) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, softWrap = false)
    }
}

@Composable
fun StatusTag(ev: Ev) {
    when (ev.e.st) {
        "cancelled" -> StatusLabel(stringResource(R.string.cancelled), G.Error)
        "moved" -> StatusLabel(stringResource(R.string.moved))
    }
}

@Composable
fun StarButton(on: Boolean, onLabel: Int, offLabel: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (on) Icons.Filled.Star else Ic.StarBorder,
            contentDescription = stringResource(if (on) onLabel else offLabel),
            tint = if (on) G.Text else G.Muted,
        )
    }
}

@Composable
fun EventCard(ev: Ev, section: Section?, saved: Boolean, onSave: () -> Unit, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val locale = uiLocale()
    val e = ev.e
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(ev, Modifier.size(64.dp).clip(G.Radius).border(1.dp, G.Line, G.Radius))
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusTag(ev)
                if (ev.student && e.c != "students") StatusLabel(stringResource(R.string.q_students))
                Tag(stringResource(catLabel(e.c)))
                priceText(ctx, e)?.let { Tag(it) }
                e.src.firstOrNull()?.let { s ->
                    val more = if (e.src.size > 1) " +${e.src.size - 1}" else ""
                    Text(
                        stringResource(kindLabel(s.kind)) + more,
                        style = MaterialTheme.typography.labelSmall,
                        color = G.Faint,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        StarButton(saved, R.string.unsave, R.string.save, onSave)
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = G.Line)
}

@Composable
fun StudentRow(list: List<Ev>, open: (String) -> Unit, seeAll: () -> Unit) {
    val ctx = LocalContext.current
    val locale = uiLocale()
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.for_students).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = G.Faint,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(onClick = seeAll) { Text(stringResource(R.string.see_all), color = G.Muted) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.id }) { ev ->
                Surface(
                    color = G.Surface,
                    shape = G.Radius,
                    border = G.Border,
                    modifier = Modifier.width(168.dp).clip(G.Radius).clickable { open(ev.id) },
                ) {
                    Column {
                        Thumb(ev, Modifier.fillMaxWidth().height(96.dp))
                        Column(Modifier.padding(12.dp)) {
                            Text(ev.e.t, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(cardWhen(ctx, ev, null, locale), ev.e.v?.town).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// Section label: uppercase mono in the faint tone, a 1dp line under it; opaque so it works as a sticky header.
@Composable
fun SectionHeader(text: String) {
    Column(Modifier.fillMaxWidth().background(G.Bg)) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = G.Faint,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = G.Line)
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
        Text(emoji, fontSize = 36.sp, modifier = Modifier.grayscale().alpha(0.6f))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = G.Muted, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = G.Muted, strokeWidth = 2.dp) }
}

@Composable
fun Banner(text: String, action: String, onAction: () -> Unit) {
    Surface(color = G.Surface, border = G.Border, shape = G.Radius, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}
