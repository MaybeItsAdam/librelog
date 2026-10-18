package uk.co.maybeitssoftware.librelog.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.maybeitssoftware.librelog.R

/**
 * "Continue with Google", drawn to Google's Sign-In branding guidelines
 * (developers.google.com/identity/branding-guidelines): the standard-colour
 * "G" at 20dp, 12dp in from the edge with 10dp before the label, Roboto
 * Medium 14/20, Google's fills, 1dp inside stroke and text colours, on a
 * 40dp button. The touch target around it is 48dp; the margin is invisible
 * so the painted button keeps Google's size. The theme follows the system's
 * light or dark mode unless one is given.
 */
@Composable
fun GoogleSignInButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    theme: GoogleButtonTheme = if (isSystemInDarkTheme()) GoogleButtonTheme.Dark else GoogleButtonTheme.Light,
) {
    val label = "Continue with Google"
    val shape = RoundedCornerShape(Radius.control)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = label }
            .pressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .alpha(if (enabled) 1f else 0.38f)
                .background(theme.fill, shape)
                .then(theme.stroke?.let { Modifier.border(1.dp, it, shape) } ?: Modifier)
                .padding(horizontal = 12.dp)
                // The button carries the label; don't read the text twice.
                .clearAndSetSemantics {},
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.ic_google_g), contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                color = theme.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
