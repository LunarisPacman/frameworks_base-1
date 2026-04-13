/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.axdynamicbar.ui.compose

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.AlphaIconBg
import com.android.systemui.axdynamicbar.shared.CardBorderBrush
import com.android.systemui.axdynamicbar.shared.DarkCard
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.shared.OnCardSecondary
import com.android.systemui.axdynamicbar.shared.OnCardText
import com.android.systemui.axdynamicbar.shared.ShapeChip
import com.android.systemui.axdynamicbar.shared.SizeCompactIcon
import com.android.systemui.axdynamicbar.shared.SpaceLg
import com.android.systemui.axdynamicbar.shared.SpaceMd
import com.android.systemui.axdynamicbar.shared.SpaceSm
import com.android.systemui.axdynamicbar.shared.SpaceXs
import com.android.systemui.axdynamicbar.shared.SubtleGray
import com.android.systemui.axdynamicbar.shared.TsBadge
import com.android.systemui.axdynamicbar.shared.accentColorFor
import com.android.systemui.axdynamicbar.shared.toScaledBitmap
import com.android.systemui.res.R

private val TeamPanelShape = RoundedCornerShape(24.dp)
private val FooterShape = RoundedCornerShape(18.dp)
private val TeamLogoSize = 52.dp

@Composable
internal fun SportsExpanded(event: IslandEvent.Sports, interactor: IslandActions) {
    val accent = accentColorFor(event)
    val badgeLabel = event.statusDetail.ifBlank { sportsStatusLabel(event.status) }
    val headerLabel =
        when {
            event.league.isNotBlank() -> event.league
            event.team2Name.isNotBlank() ->
                "${compactTeamLabel(event.team1Name)} vs ${compactTeamLabel(event.team2Name)}"
            else -> event.team1Name
        }
    val footerText =
        event.commentary.ifBlank {
            if (event.statusDetail.isNotBlank() && event.statusDetail != badgeLabel) {
                event.statusDetail
            } else {
                ""
            }
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpaceLg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceMd),
        ) {
            Text(
                headerLabel,
                modifier = Modifier.weight(1f),
                color = SubtleGray,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusBadge(event.status, accent, badgeLabel)
        }

        if (event.team2Name.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpaceMd),
                verticalAlignment = Alignment.Top,
            ) {
                TeamScorePanel(
                    name = event.team1Name,
                    score = event.score1,
                    icon = event.team1Icon,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                )
                TeamScorePanel(
                    name = event.team2Name,
                    score = event.score2,
                    icon = event.team2Icon,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            TeamScorePanel(
                name = event.team1Name,
                score = event.score1,
                icon = event.team1Icon ?: event.appIcon,
                accent = accent,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (footerText.isNotBlank()) {
            CommentaryStrip(
                text = footerText,
                accent = accent,
            )
        }
    }
}

@Composable
internal fun RowScope.CompactSportsRow(event: IslandEvent.Sports) {
    val accent = accentColorFor(event)
    val centerText =
        when {
            event.score1.isNotBlank() -> "${event.score1} - ${event.score2}"
            event.team2Name.isBlank() -> compactTeamLabel(event.team1Name)
            else -> stringResource(R.string.ax_dynamic_bar_sports_vs)
        }

    CompactTeamBadge(event.team1Name, event.team1Icon, accent)
    Spacer(Modifier.width(SpaceSm))

    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            centerText,
            color = accent,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            sportsStatusLabel(event.status),
            color = SubtleGray,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }

    if (event.team2Name.isNotBlank()) {
        Spacer(Modifier.width(SpaceSm))
        CompactTeamBadge(event.team2Name, event.team2Icon, accent)
    }
}

@Composable
private fun CompactTeamBadge(name: String, icon: Drawable?, accent: Color) {
    icon?.let {
        Image(
            bitmap = it.toScaledBitmap(SizeCompactIcon),
            contentDescription = name,
            modifier = Modifier.size(SizeCompactIcon).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } ?: Box(
        modifier =
            Modifier.size(SizeCompactIcon)
                .clip(CircleShape)
                .background(accent.copy(alpha = AlphaIconBg)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            compactTeamLabel(name),
            color = accent,
            style = TsBadge,
            maxLines = 1,
        )
    }
}

@Composable
private fun TeamScorePanel(
    name: String,
    score: String,
    icon: Drawable?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val primaryLabel = compactTeamLabel(name)
    val secondaryLabel = expandedTeamLabel(name, primaryLabel)

    Surface(
        modifier = modifier.border(1.dp, CardBorderBrush, TeamPanelShape),
        shape = TeamPanelShape,
        color = DarkCard.copy(alpha = 0.82f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SpaceLg, vertical = SpaceLg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SpaceSm),
        ) {
            TeamAvatar(name = name, icon = icon, accent = accent)

            if (score.isNotBlank()) {
                Text(
                    score,
                    color = OnCardText,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    primaryLabel,
                    color = OnCardText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            } else {
                Text(
                    primaryLabel,
                    color = OnCardText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }

            if (secondaryLabel.isNotBlank()) {
                Text(
                    secondaryLabel,
                    color = OnCardSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TeamAvatar(name: String, icon: Drawable?, accent: Color) {
    icon?.let {
        Image(
            bitmap = it.toScaledBitmap(TeamLogoSize),
            contentDescription = name,
            modifier = Modifier.size(TeamLogoSize).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } ?: Box(
        modifier =
            Modifier.size(TeamLogoSize)
                .clip(CircleShape)
                .background(accent.copy(alpha = AlphaIconBg)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            compactTeamLabel(name),
            color = accent,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun CommentaryStrip(text: String, accent: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = FooterShape,
        color = accent.copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            color = OnCardSecondary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = SpaceLg, vertical = SpaceMd),
        )
    }
}

@Composable
private fun StatusBadge(
    status: IslandEvent.GameStatus,
    accent: Color,
    label: String,
) {
    Surface(
        shape = ShapeChip,
        color = accent.copy(alpha = AlphaIconBg),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpaceLg, vertical = SpaceXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceXs),
        ) {
            if (status == IslandEvent.GameStatus.LIVE) {
                PulsingDot(color = accent, size = 6.dp)
            }
            Text(
                label,
                color = accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun sportsStatusLabel(status: IslandEvent.GameStatus): String {
    return when (status) {
        IslandEvent.GameStatus.LIVE -> stringResource(R.string.ax_dynamic_bar_sports_live)
        IslandEvent.GameStatus.FINAL -> stringResource(R.string.ax_dynamic_bar_sports_final)
        IslandEvent.GameStatus.HALFTIME -> stringResource(R.string.ax_dynamic_bar_sports_halftime)
        IslandEvent.GameStatus.PRE_GAME -> stringResource(R.string.ax_dynamic_bar_sports_upcoming)
    }
}

private fun compactTeamLabel(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "--"
    if (!trimmed.contains(' ') && trimmed.length <= 4) {
        return trimmed.uppercase()
    }

    val tokens =
        trimmed.split(Regex("""[\s\-/]+"""))
            .mapNotNull { token -> token.firstOrNull()?.takeIf { it.isLetterOrDigit() } }
    if (tokens.size >= 2) {
        return tokens.take(3).joinToString(separator = "") { it.uppercase() }
    }

    return trimmed.take(3).uppercase()
}

private fun expandedTeamLabel(name: String, compactLabel: String): String {
    val trimmed = name.trim()
    return if (trimmed.equals(compactLabel, ignoreCase = true)) {
        ""
    } else {
        trimmed
    }
}
