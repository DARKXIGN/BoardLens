package com.boardlens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boardlens.ui.BlCard
import com.boardlens.ui.PrimaryButton
import com.boardlens.ui.ReaderScreen
import com.boardlens.ui.ReaderViewModel
import com.boardlens.ui.theme.BoardLensTheme
import com.boardlens.ui.theme.BoardLensType
import com.boardlens.ui.theme.TossIcon
import com.boardlens.ui.theme.TossIconImage
import com.boardlens.ui.theme.blColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: ReaderViewModel = viewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()
            // "High contrast" switches the whole app to the yellow-on-black palette.
            BoardLensTheme(highContrast = settings.highContrast) {
                SystemBarIcons(lightBackground = !settings.highContrast)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(blColors.background)
                        .safeDrawingPadding(),
                ) {
                    CameraPermissionGate { ReaderScreen(vm) }
                }
            }
        }
    }
}

/** Dark status/navigation bar icons on the light palette, light icons on high contrast. */
@Composable
private fun SystemBarIcons(lightBackground: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightBackground
            isAppearanceLightNavigationBars = lightBackground
        }
    }
}

@Composable
private fun CameraPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (granted) {
        content()
    } else {
        val c = blColors
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            BlCard(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TossIconImage(TossIcon.Camera, size = 56.dp)
                    Text("BoardLens needs the camera", style = BoardLensType.title, color = c.textPrimary)
                    Text(
                        "It reads the board through the camera. Frames are processed on this phone and never leave it.",
                        style = BoardLensType.body,
                        color = c.textSecondary,
                    )
                    PrimaryButton("Allow camera", { launcher.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
