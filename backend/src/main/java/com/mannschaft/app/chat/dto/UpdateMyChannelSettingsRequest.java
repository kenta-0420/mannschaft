package com.mannschaft.app.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F04.2 Phase 11 第二陣 2-β: 自分のチャンネル個人設定更新リクエスト DTO。
 *
 * <p>{@code PATCH /api/v1/chat/channels/{id}/members/me} で使用する。
 * 設計書 F04.2 §4 で「チャンネル全体設定（{@link ChannelSettingsRequest} 系の {@code /settings}）」
 * と「メンバー個人設定（本 DTO）」を別リソースとして扱う設計に合わせて新設。</p>
 *
 * <p>すべてのフィールドは任意。指定したフィールドのみ更新する（PATCH セマンティクス）。
 * Jackson は {@link JsonProperty} を介してスネークケース {@code is_muted / is_pinned / category}
 * を受理する。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateMyChannelSettingsRequest {

    /** ミュート設定。{@code true} で通知 OFF。 */
    @JsonProperty("is_muted")
    private Boolean isMuted;

    /** ピン留め設定。{@code true} でサイドバー上部に固定表示。 */
    @JsonProperty("is_pinned")
    private Boolean isPinned;

    /** 個人用カテゴリ名（最大50文字）。サイドバーのセクション分けに使用。{@code null} 指定でカテゴリ解除。 */
    @JsonProperty("category")
    @Size(max = 50)
    private String category;
}
