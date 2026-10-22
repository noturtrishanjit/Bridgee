package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.DeviceProfile
import com.example.ui.screens.ReceiveScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ShareScreen
import com.example.ui.screens.TransfersScreen
import com.example.ui.theme.LocalIsDarkTheme
import com.example.ui.theme.ThemeMode

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val currentTab by viewModel.currentTab.collectAsState()
    val activeTransfers by viewModel.activeTransfers.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val deviceProfile by viewModel.deviceProfile.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CleanTopAppBar(
                isDark = isDark,
                deviceProfile = deviceProfile,
                onProfileClick = { viewModel.setTab(3) },
                onToggleTheme = {
                    val nextMode = if (isDark) ThemeMode.LIGHT else ThemeMode.DARK
                    viewModel.setThemeMode(nextMode)
                }
            )
        },
        bottomBar = {
            CleanNavigationBar(
                currentTab = currentTab,
                onSelectTab = { viewModel.setTab(it) },
                activeTransferCount = activeTransfers.size
            )
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentTab,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(
                        initialOffsetX = { (it * 0.18f).toInt() },
                        animationSpec = tween(260, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(260)))
                    .togetherWith(
                        slideOutHorizontally(
                            targetOffsetX = { (-it * 0.18f).toInt() },
                            animationSpec = tween(260, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(180))
                    )
                } else {
                    (slideInHorizontally(
                        initialOffsetX = { (-it * 0.18f).toInt() },
                        animationSpec = tween(260, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(260)))
                    .togetherWith(
                        slideOutHorizontally(
                            targetOffsetX = { (it * 0.18f).toInt() },
                            animationSpec = tween(260, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(180))
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            label = "tab_slide_animation"
        ) { tab ->
            when (tab) {
                0 -> ShareScreen(viewModel = viewModel)
                1 -> ReceiveScreen(viewModel = viewModel)
                2 -> TransfersScreen(viewModel = viewModel)
                3 -> SettingsScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanTopAppBar(
    isDark: Boolean,
    deviceProfile: DeviceProfile,
    onProfileClick: () -> Unit,
    onToggleTheme: () -> Unit
) {
    CenterAlignedTopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_bridge_logo_1792567884823),
                    contentDescription = "Bridge Logo",
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Bridge",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        navigationIcon = {
            // Profile & Avatar Button
            Row(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    .clickable(onClick = onProfileClick)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = deviceProfile.avatar.symbol,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = deviceProfile.deviceName.take(10),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            // Subtle Encrypted Lock Icon
            if (deviceProfile.isEncryptionEnabled) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0x1F059669))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Encrypted",
                            tint = Color(0xFF059669),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "AES",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF059669)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            // Theme Toggle Button
            IconButton(
                onClick = onToggleTheme,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .testTag("theme_toggle_button")
            ) {
                Icon(
                    imageVector = if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                    contentDescription = "Toggle theme",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}

@Composable
fun CleanNavigationBar(
    currentTab: Int,
    onSelectTab: (Int) -> Unit,
    activeTransferCount: Int
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        NavigationBarItem(
            selected = currentTab == 0,
            onClick = { onSelectTab(0) },
            icon = {
                Icon(
                    imageVector = if (currentTab == 0) Icons.Filled.Share else Icons.Outlined.Share,
                    contentDescription = "Share"
                )
            },
            label = { Text("Share", fontWeight = if (currentTab == 0) FontWeight.Bold else FontWeight.Normal) },
            modifier = Modifier.testTag("nav_share")
        )

        NavigationBarItem(
            selected = currentTab == 1,
            onClick = { onSelectTab(1) },
            icon = {
                Icon(
                    imageVector = if (currentTab == 1) Icons.Filled.Download else Icons.Outlined.Download,
                    contentDescription = "Receive"
                )
            },
            label = { Text("Receive", fontWeight = if (currentTab == 1) FontWeight.Bold else FontWeight.Normal) },
            modifier = Modifier.testTag("nav_receive")
        )

        NavigationBarItem(
            selected = currentTab == 2,
            onClick = { onSelectTab(2) },
            icon = {
                if (activeTransferCount > 0) {
                    BadgedBox(
                        badge = {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White
                            ) {
                                Text("$activeTransferCount", fontSize = 10.sp)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (currentTab == 2) Icons.Filled.SyncAlt else Icons.Outlined.SyncAlt,
                            contentDescription = "Transfers"
                        )
                    }
                } else {
                    Icon(
                        imageVector = if (currentTab == 2) Icons.Filled.SyncAlt else Icons.Outlined.SyncAlt,
                        contentDescription = "Transfers"
                    )
                }
            },
            label = { Text("Transfers", fontWeight = if (currentTab == 2) FontWeight.Bold else FontWeight.Normal) },
            modifier = Modifier.testTag("nav_transfers")
        )

        NavigationBarItem(
            selected = currentTab == 3,
            onClick = { onSelectTab(3) },
            icon = {
                Icon(
                    imageVector = if (currentTab == 3) Icons.Filled.Settings else Icons.Outlined.Settings,
                    contentDescription = "Settings"
                )
            },
            label = { Text("Settings", fontWeight = if (currentTab == 3) FontWeight.Bold else FontWeight.Normal) },
            modifier = Modifier.testTag("nav_settings")
        )
    }
}
