package com.mannschaft.app.dashboard.dto;

import java.util.List;

/**
 * チャットハブ全体レスポンス。
 *
 * <ul>
 *   <li>{@code teamChannels}  — グループチャンネル（TEAM_PUBLIC / TEAM_PRIVATE / ORG_PUBLIC / ORG_PRIVATE）</li>
 *   <li>{@code directMessages} — DM（DM / GROUP_DM）</li>
 *   <li>{@code contacts}      — フォルダ別の連絡先（sortOrder 昇順、isPinned=true アイテムを先頭）</li>
 *   <li>{@code summary}       — 集計サマリー</li>
 * </ul>
 */
public record ChatHubResponse(
        List<TeamChannelItemDto> teamChannels,
        List<DirectMessageItemDto> directMessages,
        List<ContactFolderDto> contacts,
        ChatHubSummaryDto summary
) {}
