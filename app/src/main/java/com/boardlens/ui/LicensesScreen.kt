package com.boardlens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.boardlens.ui.theme.BoardLensType
import com.boardlens.ui.theme.TossIcon
import com.boardlens.ui.theme.blColors

/**
 * Attribution and license texts for the bundled typeface and icons.
 * Tossface asks for a credit line naming Toss Team plus the license text; Pretendard
 * (SIL OFL 1.1) asks for the copyright and license to travel with the font.
 */
@Composable
fun LicensesScreen(onClose: () -> Unit) {
    val c = blColors
    val context = LocalContext.current
    fun asset(path: String) = runCatching {
        context.assets.open(path).bufferedReader().use { it.readText() }
    }.getOrDefault("")
    val tossface = remember { asset("licenses/tossface_license.txt") }
    val pretendard = remember { asset("licenses/pretendard_ofl.txt") }

    Surface(Modifier.fillMaxSize(), color = c.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconSquareButton(TossIcon.Back, "Back", onClose)
                Text("Licenses", style = BoardLensType.heading, color = c.textPrimary, modifier = Modifier.semantics { heading() })
            }

            LicenseCard(
                title = "Tossface",
                body = "BoardLens uses Tossface, provided by Toss Team.\n" +
                    "BoardLens에는 토스팀에서 제공한 토스페이스가 적용되어 있습니다.\n" +
                    "Copyright notice: toss.im/tossface/copyright",
                license = tossface,
            )
            LicenseCard(
                title = "Pretendard",
                body = "Typeface by Kil Hyung-jin, SIL Open Font License 1.1.",
                license = pretendard,
            )
            LicenseCard(
                title = "AndroidSVG",
                body = "Draws the Tossface icons. Copyright Paul LeBeau, Apache License 2.0.",
                license = null,
            )
        }
    }
}

@Composable
private fun LicenseCard(title: String, body: String, license: String?) {
    val c = blColors
    BlCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = BoardLensType.cardTitle, color = c.textPrimary, modifier = Modifier.semantics { heading() })
            Text(body, style = BoardLensType.body, color = c.textPrimary)
            if (!license.isNullOrBlank()) {
                Text(license, style = BoardLensType.small, color = c.textSecondary)
            }
        }
    }
}
