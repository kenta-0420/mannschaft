package com.mannschaft.app.social.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * フレンドフォルダ View DTO。{@code GET /api/v1/teams/{id}/friend-folders} の
 * レスポンス要素として使用する。
 *
 * <p>
 * 自チーム視点のみの非対称フォルダで、所属するフレンド数の集計値
 * {@link #memberCount} も含めて返却する。
 * </p>
 *
 * <p>
 * Phase 1 では {@code auto_forward_enabled} / {@code auto_forward_target}
 * は存在しないため、Phase 3 で追加予定。
 * </p>
 */
@Getter
@Builder
public class TeamFriendFolderView {

    /** フォルダ ID */
    private final Long id;

    /** フォルダ名（1〜50 文字、チーム内一意） */
    private final String name;

    /** フォルダの説明（任意、最大 300 文字） */
    private final String description;

    /** 表示色（HEX、例: "#10B981"） */
    private final String color;

    /** 並び替え順（小さいほど上に表示） */
    private final Integer sortOrder;

    /** デフォルトフォルダかどうか（システム自動生成フラグ） */
    private final Boolean isDefault;

    /** フォルダに登録されているフレンド数 */
    private final long memberCount;

    /** フォルダ作成日時 */
    private final LocalDateTime createdAt;

    /** フォルダ最終更新日時 */
    private final LocalDateTime updatedAt;
}
