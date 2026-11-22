# Graphite Design System — Android

Follow this guide for all UI in this app. The app is dark-only and minimal: quiet warm neutrals plus one muted accent. If a screen looks "flashy", it is wrong.

## Tokens

| Token | Hex | Use |
|---|---|---|
| bg | `#101010` | App background |
| surface | `#161616` | Cards, sheets, bottom bar |
| raised | `#1C1C1C` | Pressed states, dialogs, menus |
| line | `#262626` | 1dp borders, dividers |
| text | `#E6E4E0` | Primary text, primary button fill |
| muted | `#8A8782` | Secondary text, icons |
| faint | `#55534F` | Hints, disabled, section labels |
| accent | `#C8B89A` | Status dots, small labels, active tab indicator ONLY |

- Spacing: steps of 4dp/8dp. Use 16dp screen padding, 12dp gaps between cards, and 20dp padding inside cards.
- Corner radius: always 6dp (chips/pills may use 50%).
- Elevation: always 0. Use borders to separate things, never shadows.
- Type: Inter for UI text, IBM Plex Mono for labels. If you can't bundle the fonts, use the system default sans and monospace.

## Compose theme (put in `ui/theme/Theme.kt`)

```kotlin
object G {
    val Bg = Color(0xFF101010); val Surface = Color(0xFF161616)
    val Raised = Color(0xFF1C1C1C); val Line = Color(0xFF262626)
    val Text = Color(0xFFE6E4E0); val Muted = Color(0xFF8A8782)
    val Faint = Color(0xFF55534F); val Accent = Color(0xFFC8B89A)
    val Radius = RoundedCornerShape(6.dp)
}

private val Scheme = darkColorScheme(
    primary = G.Text, onPrimary = G.Bg,
    secondary = G.Accent, onSecondary = G.Bg,
    background = G.Bg, onBackground = G.Text,
    surface = G.Surface, onSurface = G.Text,
    surfaceVariant = G.Raised, onSurfaceVariant = G.Muted,
    surfaceContainer = G.Surface, surfaceContainerHigh = G.Raised,
    outline = G.Line, outlineVariant = G.Line,
    error = Color(0xFFC98A7E), onError = G.Bg,
)

private val Type = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.4).sp),
    titleMedium  = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium   = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall    = TextStyle(fontSize = 12.sp, color = G.Muted),
    labelSmall   = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp),
)

@Composable
fun GraphiteTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Scheme, typography = Type,
        shapes = Shapes(small = G.Radius, medium = G.Radius, large = G.Radius), content = content)
```

Call `enableEdgeToEdge()` in the Activity so the status bar and nav bar are transparent over `G.Bg`. Turn dynamic color OFF.

## Components

- **Card**: `Surface(color = G.Surface, shape = G.Radius, border = BorderStroke(1.dp, G.Line), tonalElevation = 0.dp)`
- **Primary button**: `G.Text` fill with `G.Bg` text, 6dp radius, 40dp height. Use one per screen at most.
- **Secondary button**: `OutlinedButton` with transparent fill, a 1dp `G.Line` border and `G.Text` text.
- **Text input**: `OutlinedTextField` on `G.Bg` with a `G.Line` border. When focused, the border becomes `G.Muted` (not accent).
- **Section label**: UPPERCASE `labelSmall` in `G.Faint`, with a 1dp divider under it.
- **Status / tag**: a 6dp `G.Accent` dot followed by a `labelSmall` text in accent.
- **Bottom nav**: `G.Surface` background with a 1dp top border in `G.Line`. Unselected icons are `G.Muted`; the selected icon is `G.Text` with a small accent indicator. Don't use a pill highlight.
- **Lists**: separate items with 1dp `G.Line` dividers rather than wrapping each one in its own card.
- **Icons**: Material Symbols Outlined at 20–24dp in `G.Muted`.

## Rules

1. Don't use gradients, glows, shadows or colored backgrounds.
2. Use the accent only on small things (dots, labels, indicators). Never use it for buttons or large fills.
3. Don't use pure white `#FFFFFF` or pure black `#000000`.
4. Keep motion to 150ms color/alpha fades. Don't use bouncy or scale animations.
5. Separate things with whitespace first, a border second, and a background color change last.
6. When in doubt, remove it.
