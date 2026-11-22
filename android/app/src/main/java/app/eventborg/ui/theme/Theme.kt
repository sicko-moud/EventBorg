package app.eventborg.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.eventborg.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Graphite (DESIGN.md): dark only, warm neutrals, one muted accent for dots, small labels and the tab indicator.
object G {
    val Bg = Color(0xFF101010)
    val Surface = Color(0xFF161616)
    val Raised = Color(0xFF1C1C1C)
    val Line = Color(0xFF262626)
    val Text = Color(0xFFE6E4E0)
    val Muted = Color(0xFF8A8782)
    val Faint = Color(0xFF55534F)
    val Accent = Color(0xFFC8B89A)
    val Error = Color(0xFFC98A7E)
    val Radius = RoundedCornerShape(6.dp)
    val Border = BorderStroke(1.dp, Line)
}

private val Scheme = darkColorScheme(
    primary = G.Text, onPrimary = G.Bg, primaryContainer = G.Raised, onPrimaryContainer = G.Text, inversePrimary = G.Bg,
    secondary = G.Accent, onSecondary = G.Bg, secondaryContainer = G.Raised, onSecondaryContainer = G.Text,
    tertiary = G.Accent, onTertiary = G.Bg, tertiaryContainer = G.Raised, onTertiaryContainer = G.Text,
    background = G.Bg, onBackground = G.Text,
    surface = G.Surface, onSurface = G.Text, surfaceVariant = G.Raised, onSurfaceVariant = G.Muted,
    surfaceTint = Color.Transparent, inverseSurface = G.Text, inverseOnSurface = G.Bg,
    error = G.Error, onError = G.Bg, errorContainer = G.Raised, onErrorContainer = G.Error,
    outline = G.Line, outlineVariant = G.Line, scrim = G.Bg,
    surfaceBright = G.Raised, surfaceDim = G.Bg, surfaceContainerLowest = G.Bg, surfaceContainerLow = G.Surface,
    surfaceContainer = G.Surface, surfaceContainerHigh = G.Raised, surfaceContainerHighest = G.Raised,
)

// Inter (static Latin subsets of the variable font, opsz 14) for UI text; IBM Plex Mono, unmodified, for labels.
// Licences (SIL OFL 1.1) ship in assets/licenses.
private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_semibold, FontWeight.Bold),
)
private val PlexMono = FontFamily(Font(R.font.ibm_plex_mono_regular, FontWeight.Normal))

private fun TextStyle.inter() = copy(fontFamily = Inter)

private val Base = Typography().run {
    Typography(
        displayLarge = displayLarge.inter(), displayMedium = displayMedium.inter(), displaySmall = displaySmall.inter(),
        headlineLarge = headlineLarge.inter(), headlineMedium = headlineMedium.inter(), headlineSmall = headlineSmall.inter(),
        titleLarge = titleLarge.inter(), titleMedium = titleMedium.inter(), titleSmall = titleSmall.inter(),
        bodyLarge = bodyLarge.inter(), bodyMedium = bodyMedium.inter(), bodySmall = bodySmall.inter(),
        labelLarge = labelLarge.inter(), labelMedium = labelMedium.inter(), labelSmall = labelSmall.inter(),
    )
}
private val Type = Base.copy(
    headlineSmall = Base.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.4).sp),
    titleLarge = Base.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    titleMedium = Base.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = Base.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyMedium = Base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = Base.bodySmall.copy(fontSize = 12.sp, color = G.Muted),
    labelSmall = Base.labelSmall.copy(fontSize = 11.sp, fontFamily = PlexMono, letterSpacing = 1.2.sp),
)

@Composable
fun GraphiteTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Scheme, typography = Type, shapes = Shapes(G.Radius, G.Radius, G.Radius, G.Radius, G.Radius), content = content)

// One per screen at most: light fill, dark text, 6dp corners, 40dp tall.
@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    Button(onClick, modifier.heightIn(min = 40.dp), enabled, shape = G.Radius, content = content)

@Composable
fun SecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    OutlinedButton(
        onClick, modifier.heightIn(min = 40.dp), enabled, shape = G.Radius, border = G.Border,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = G.Text), content = content,
    )

@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) = OutlinedTextField(
    value, onValueChange, modifier, enabled, readOnly,
    label = label, placeholder = placeholder, trailingIcon = trailingIcon, supportingText = supportingText, isError = isError,
    keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, singleLine = singleLine, maxLines = maxLines, minLines = minLines,
    shape = G.Radius,
    colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = G.Muted, unfocusedBorderColor = G.Line, focusedLabelColor = G.Muted, cursorColor = G.Text,
        focusedContainerColor = G.Bg, unfocusedContainerColor = G.Bg,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) = TopAppBar(
    title = title, modifier = modifier, navigationIcon = navigationIcon, actions = actions,
    colors = TopAppBarDefaults.topAppBarColors(containerColor = G.Bg, scrolledContainerColor = G.Bg),
)

@Composable
fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = G.Bg, checkedTrackColor = G.Text, uncheckedThumbColor = G.Muted,
    uncheckedTrackColor = G.Raised, uncheckedBorderColor = G.Faint,
)

private val Gray = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }

// Emoji (category art, empty states) drawn without colour, so the screen stays quiet.
fun Modifier.grayscale(): Modifier = drawWithContent {
    drawIntoCanvas { c ->
        c.saveLayer(Rect(Offset.Zero, size), Gray)
        drawContent()
        c.restore()
    }
}
