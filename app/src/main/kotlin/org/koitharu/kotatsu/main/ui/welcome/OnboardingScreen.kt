package org.koitharu.kotatsu.main.ui.welcome

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ColorScheme
import org.koitharu.kotatsu.settings.compose.ColorSchemePickerRow

private const val PAGE_COUNT = 4
private val CARD_SHAPE = RoundedCornerShape(24.dp)
private val SCREEN_PADDING = 20.dp

// Back-gesture preview, matching the View-side PredictiveBackCallback so both read as one gesture.
private const val BACK_PREVIEW_SHRINK = 0.1f
private val BACK_PREVIEW_SHIFT = 8.dp
private val BACK_PREVIEW_CORNER = 28.dp

data class OnboardingPermissions(
    val hasInstall: Boolean,
    val hasNotifications: Boolean,
    val hasBattery: Boolean,
)

data class OnboardingActions(
    val onThemeChange: (Int) -> Unit,
    val onColorSchemeChange: (String) -> Unit,
    val onAmoledChange: (Boolean) -> Unit,
    val onAmoledReset: () -> Unit,
    val onSelectDestination: () -> Unit,
    val onPermissionInstall: () -> Unit,
    val onPermissionNotifications: () -> Unit,
    val onPermissionBattery: () -> Unit,
    val onSignInGoogle: () -> Unit,
    val onRestoreDropSauce: () -> Unit,
    val onRestoreTachiyomi: () -> Unit,
    val onOpenGithub: () -> Unit,
    val onOpenDiscord: () -> Unit,
    val onVisitWebsite: () -> Unit,
    val onFinish: () -> Unit,
)

@Composable
fun OnboardingScreen(
    selectedTheme: Int,
    selectedColorScheme: ColorScheme,
    isAmoledEnabled: Boolean,
    storageSummary: String?,
    isLoading: Boolean,
    permissions: OnboardingPermissions,
    actions: OnboardingActions,
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val isLastPage by remember { derivedStateOf { pagerState.currentPage == PAGE_COUNT - 1 } }
    val backEnabled by remember { derivedStateOf { pagerState.currentPage > 0 } }

    // Back here steps to the previous slide, so it gets the same follow-the-finger preview the rest
    // of the app's in-app back gestures use: the slide shrinks and rounds off as you drag, and
    // springs back if you let go early.
    var backProgress by remember { mutableFloatStateOf(0f) }
    var isBackFromLeftEdge by remember { mutableStateOf(true) }
    val backPreview by animateFloatAsState(
        targetValue = backProgress,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "backPreview",
    )
    PredictiveBackHandler(enabled = backEnabled) { events ->
        try {
            events.collect { event ->
                isBackFromLeftEdge = event.swipeEdge == BackEventCompat.EDGE_LEFT
                backProgress = event.progress
            }
            pagerState.animateScrollToPage(pagerState.currentPage - 1)
        } finally {
            backProgress = 0f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val progress = backPreview
                scaleX = 1f - BACK_PREVIEW_SHRINK * progress
                scaleY = scaleX
                translationX = (if (isBackFromLeftEdge) 1f else -1f) * BACK_PREVIEW_SHIFT.toPx() * progress
                transformOrigin = TransformOrigin(if (isBackFromLeftEdge) 1f else 0f, 0.5f)
                shape = RoundedCornerShape(BACK_PREVIEW_CORNER * progress)
                clip = progress > 0f
            },
    ) {
        HorizontalPager(
            state = pagerState,
            // Leave room at the bottom for the navigation row (dots + FAB)
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp),
        ) { pageIndex ->
            val iconRes = when (pageIndex) {
                0 -> R.drawable.ic_welcome
                1 -> R.drawable.ic_storage
                2 -> R.drawable.ic_sync
                else -> R.drawable.ic_save_ok
            }
            val titleRes = when (pageIndex) {
                0 -> R.string.welcome
                1 -> R.string.onboarding_storage_permissions_title
                2 -> R.string.onboarding_sync_title
                else -> R.string.onboarding_finish_title
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // Push content below the status bar (notch-safe)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(
                        top = 32.dp,
                        start = SCREEN_PADDING,
                        end = SCREEN_PADDING,
                        bottom = 24.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Hero icon bubble
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(96.dp),
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(22.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(20.dp))
                when (pageIndex) {
                    0 -> WelcomeSlide(
                        selectedTheme = selectedTheme,
                        selectedColorScheme = selectedColorScheme,
                        isAmoledEnabled = isAmoledEnabled,
                        actions = actions,
                    )
                    1 -> StorageSlide(
                        storageSummary = storageSummary,
                        permissions = permissions,
                        actions = actions,
                    )
                    2 -> SyncSlide(isLoading = isLoading, actions = actions)
                    else -> FinishSlide(actions = actions)
                }
            }
        }

        // Bottom navigation: animated pill dots + FAB
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = SCREEN_PADDING, end = SCREEN_PADDING, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(PAGE_COUNT) { index ->
                    val isSelected by remember(index) { derivedStateOf { index == pagerState.currentPage } }
                    val dotWidth by animateDpAsState(
                        targetValue = if (isSelected) 24.dp else 8.dp,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "dot_$index",
                    )
                    val dotColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        label = "dot_color_$index",
                    )
                    Box(
                        modifier = Modifier
                            .size(width = dotWidth, height = 8.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
            }
            FloatingActionButton(
                onClick = {
                    if (isLastPage) {
                        actions.onFinish()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    painter = painterResource(
                        if (isLastPage) R.drawable.ic_check else R.drawable.ic_arrow_forward,
                    ),
                    contentDescription = stringResource(
                        if (isLastPage) R.string.confirm else R.string.next,
                    ),
                )
            }
        }
    }
}

// ── Slide 0: Welcome ─────────────────────────────────────────────────────────

@Composable
private fun WelcomeSlide(
    selectedTheme: Int,
    selectedColorScheme: ColorScheme,
    isAmoledEnabled: Boolean,
    actions: OnboardingActions,
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDarkEnabled = when (selectedTheme) {
        AppCompatDelegate.MODE_NIGHT_YES -> true
        AppCompatDelegate.MODE_NIGHT_NO -> false
        else -> isSystemDark
    }

    LaunchedEffect(isDarkEnabled) {
        if (!isDarkEnabled && isAmoledEnabled) {
            actions.onAmoledReset()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_welcome_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Color scheme picker (has its own surfaceContainer card)
        ColorSchemePickerRow(
            title = stringResource(R.string.color_theme),
            selectedValue = selectedColorScheme.name,
            onValueChange = { actions.onColorSchemeChange(it) },
        )

        // Appearance card: M3-Expressive pill toggle + AMOLED switch
        Surface(
            shape = CARD_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.theme),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ThemeButtonGroup(
                    selectedTheme = selectedTheme,
                    onThemeChange = actions.onThemeChange,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                // AMOLED row
                val contentAlpha = if (isDarkEnabled) 1f else 0.38f
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = isDarkEnabled,
                            onClick = { actions.onAmoledChange(!isAmoledEnabled) },
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_eye_off),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = stringResource(R.string.onboarding_full_black_oled),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        )
                    }
                    Switch(
                        checked = isAmoledEnabled && isDarkEnabled,
                        onCheckedChange = { if (isDarkEnabled) actions.onAmoledChange(it) },
                        enabled = isDarkEnabled,
                    )
                }
            }
        }
    }
}

// M3 Expressive connected button group — replaces segmented button per the M3E spec.
// 2dp spacing, 8dp inner corners, fully rounded outer corners, fixed height to prevent wrapping.
@Composable
private fun ThemeButtonGroup(selectedTheme: Int, onThemeChange: (Int) -> Unit) {
    val items = listOf(
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM to R.string.follow_system,
        AppCompatDelegate.MODE_NIGHT_NO to R.string.light,
        AppCompatDelegate.MODE_NIGHT_YES to R.string.dark,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { index, (mode, labelRes) ->
            val isSelected = selectedTheme == mode
            val isFirst = index == 0
            val isLast = index == items.lastIndex
            val shape = RoundedCornerShape(
                topStart = if (isFirst) 50.dp else 8.dp,
                bottomStart = if (isFirst) 50.dp else 8.dp,
                topEnd = if (isLast) 50.dp else 8.dp,
                bottomEnd = if (isLast) 50.dp else 8.dp,
            )
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                              else MaterialTheme.colorScheme.surfaceVariant,
                label = "theme_btn_bg_$index",
            )
            val contentColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                              else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "theme_btn_fg_$index",
            )
            Surface(
                onClick = { onThemeChange(mode) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = shape,
                color = bgColor,
                contentColor = contentColor,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

// ── Slide 1: Storage & Permissions ───────────────────────────────────────────

@Composable
private fun StorageSlide(
    storageSummary: String?,
    permissions: OnboardingPermissions,
    actions: OnboardingActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_storage_permissions_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Storage destination card
        Surface(
            shape = CARD_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.manga_save_location),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = storageSummary ?: stringResource(R.string.onboarding_default_destination),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ThemedActionButton(
                    iconRes = R.drawable.ic_storage,
                    labelRes = R.string.onboarding_select_destination,
                    onClick = actions.onSelectDestination,
                )
            }
        }
        // Permissions card
        Surface(
            shape = CARD_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                PermissionRow(
                    iconRes = R.drawable.ic_plug_large,
                    titleRes = R.string.onboarding_permission_install,
                    isGranted = permissions.hasInstall,
                    onClick = actions.onPermissionInstall,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                )
                PermissionRow(
                    iconRes = R.drawable.ic_notification,
                    titleRes = R.string.onboarding_permission_notifications,
                    isGranted = permissions.hasNotifications,
                    onClick = actions.onPermissionNotifications,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                )
                PermissionRow(
                    iconRes = R.drawable.ic_battery_outline,
                    titleRes = R.string.onboarding_permission_battery,
                    isGranted = permissions.hasBattery,
                    onClick = actions.onPermissionBattery,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    @DrawableRes iconRes: Int,
    @StringRes titleRes: Int,
    isGranted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isGranted, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (isGranted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.bodyLarge,
            color = if (isGranted) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isGranted) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_forward),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ── Slide 2: Cloud Sync ───────────────────────────────────────────────────────

@Composable
private fun SyncSlide(isLoading: Boolean, actions: OnboardingActions) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_sync_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionCard(
            items = listOf(
                ActionItem(R.drawable.ic_google_g, R.string.sync_sign_in, !isLoading, actions.onSignInGoogle),
            ),
        )
        ActionCard(
            items = listOf(
                ActionItem(R.drawable.ic_backup_restore, R.string.onboarding_restore_dropsauce, !isLoading, actions.onRestoreDropSauce),
                ActionItem(R.drawable.ic_revert, R.string.onboarding_restore_tachiyomi, !isLoading, actions.onRestoreTachiyomi),
            ),
        )
    }
}

// ── Slide 3: Finish ───────────────────────────────────────────────────────────

@Composable
private fun FinishSlide(actions: OnboardingActions) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_finish_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionCard(
            items = listOf(
                ActionItem(R.drawable.ic_github, R.string.source_code, true, actions.onOpenGithub),
                ActionItem(R.drawable.ic_discord, R.string.onboarding_discord, true, actions.onOpenDiscord),
                ActionItem(R.drawable.ic_web, R.string.onboarding_visit_website, true, actions.onVisitWebsite),
            ),
        )
    }
}

// ── Shared components ─────────────────────────────────────────────────────────

private data class ActionItem(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun ActionCard(items: List<ActionItem>) {
    Surface(
        shape = CARD_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                ThemedActionButton(
                    iconRes = item.icon,
                    labelRes = item.label,
                    enabled = item.enabled,
                    onClick = item.onClick,
                )
            }
        }
    }
}

// Full-width button styled with primaryContainer so it visibly reacts to theme changes.
@Composable
private fun ThemedActionButton(
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.38f),
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(labelRes))
    }
}
