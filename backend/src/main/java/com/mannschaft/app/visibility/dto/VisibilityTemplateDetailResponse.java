package com.mannschaft.app.visibility.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 公開範囲テンプレートの詳細レスポンス DTO（ルール含む）。
 */
@Getter
@Builder
public class VisibilityTemplateDetailResponse {

    /** テンプレート ID */
    private final Long id;

    /** テンプレート名 */
    private final String name;

    /** テンプレートの説明 */
    private final String description;

    /** アイコン絵文字 */
    private final String iconEmoji;

    /** システムプリセットフラグ */
    private final boolean isSystemPreset;

    /** ルール一覧 */
    private final List<RuleResponse> rules;

    /** 作成日時 */
    private final LocalDateTime createdAt;

    /** 更新日時 */
    private final LocalDateTime updatedAt;
}
