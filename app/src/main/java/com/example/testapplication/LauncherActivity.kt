package com.example.testapplication

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import android.widget.Toast

/** 咸鱼/闲鱼 应用包名，用于显示自定义图标 */
private const val XIANYU_PACKAGE = "com.taobao.idlefish"

/**
 * 自定义桌面 Launcher Demo
 * 可替代系统默认桌面，需在「设置 → 应用 → 默认应用 → 主屏幕」中设为默认
 */
class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LauncherScreen()
        }
    }
}

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isLauncher: Boolean = false
)

@Composable
fun LauncherScreen() {
    val context = LocalContext.current
    val packageManager = context.packageManager

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isXianyuVisible by remember { mutableStateOf(false) } // 默认隐藏
    var tapCount by remember { mutableStateOf(0) }
    var phase2Ready by remember { mutableStateOf(false) }

    fun onEmptyAreaInteraction(isLongPress: Boolean, durationMs: Long) {
        if (isLongPress && durationMs >= 3000) {
            phase2Ready = true
            tapCount = 0
        } else if (!isLongPress && phase2Ready) {
            tapCount++
            if (tapCount >= 3) {
                isXianyuVisible = !isXianyuVisible
                tapCount = 0
                phase2Ready = false
            }
        } else if (!isLongPress && !phase2Ready) {
            tapCount = 0
        }
    }

    LaunchedEffect(Unit) {
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = packageManager.queryIntentActivities(mainIntent, 0)
        val list = resolveList
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
            .map { info ->
                AppInfo(
                    packageName = info.packageName,
                    label = info.loadLabel(packageManager).toString(),
                    icon = info.loadIcon(packageManager)
                )
            }
        apps = listOf(
            AppInfo(
                packageName = context.packageName,
                label = "Test应用",
                icon = context.applicationInfo.loadIcon(packageManager),
                isLauncher = true
            )
        ) + list
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "自定义桌面",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    IconButton(
                        onClick = {
                            val action = if (Build.VERSION.SDK_INT >= 33) {
                                Settings.ACTION_HOME_SETTINGS
                            } else {
                                Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
                            }
                            val intent = Intent(action)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "请手动进入：设置 → 应用 → 默认应用 → 主屏幕", Toast.LENGTH_LONG).show()
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "设为默认桌面",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // 应用网格（空白处长按超 3 秒 → 再点击 3 下 可切换咸鱼显示/隐藏）
            val displayApps = if (isXianyuVisible) apps else apps.filter { it.packageName != XIANYU_PACKAGE }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                val start = System.currentTimeMillis()
                                awaitRelease()
                                val duration = System.currentTimeMillis() - start
                                onEmptyAreaInteraction(duration >= 3000, duration)
                            }
                        )
                    }
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 80.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(displayApps) { app ->
                        AppItem(
                            app = app,
                            onClick = {
                                if (app.isLauncher) {
                                    context.startActivity(Intent(context, MainActivity::class.java))
                                } else {
                                    val intent = packageManager.getLaunchIntentForPackage(app.packageName)
                                    intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    intent?.let { context.startActivity(it) }
                                }
                            }
                        )
                    }
                }
            }
        }

    }
}

@Composable
fun AppItem(app: AppInfo, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(80.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (app.packageName == XIANYU_PACKAGE) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_background),
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                AppIcon(drawable = app.icon)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AppIcon(drawable: Drawable) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                setImageDrawable(drawable)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        },
        modifier = Modifier.size(48.dp)
    )
}
