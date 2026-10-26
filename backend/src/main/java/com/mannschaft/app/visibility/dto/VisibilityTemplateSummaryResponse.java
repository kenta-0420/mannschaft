package com.mannschaft.app.visibility.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 公開範囲テンプレートのサマリーレスポンス DTO（一覧表示用）。
 */
@Getter
@Builder
public class VisibilityTemplateSummaryResponse {

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

    /** プリセットキー（プリセットのみ設定） */
    private final String presetKey;

    /** ルール数 */
    private final long ruleCount;

    /** 作成日時 */
    private final LocalDateTime createdAt;

    /** 更新日時 */
    private final LocalDateTime updatedAt;
}
