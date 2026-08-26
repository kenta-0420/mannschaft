package com.mannschaft.app.inbox.dto;

import com.mannschaft.app.inbox.InboxSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.11 統合通知インボックス：自動ラベリング提案の 1 タップ付与リクエスト DTO（案C）。
 *
 * <p>提案チップ（{@link SuggestedLabelDto}）のタップで送信する。{@code name}（FE が i18n 解決した提案の表示名）と
 * {@code color}（提案の既定色）を渡し、サーバ側で同名ラベルを find-or-create して当該通知へ付与する。
 * 設計書: 02_api_design.md §3.5a。形式検証（色 #RRGGBB）はサービス層（{@code createLabel}）で行う。</p>
 *
 * @see com.mannschaft.app.inbox.service.InboxLabelService#suggestApply
 */
@Getter
@RequiredArgsConstructor
public class SuggestApplyRequest {

    /** ラベル名（必須・最大 50・FE が提案キーから i18n 解決した表示名） */
    @NotBlank
    @Size(max = 50)
    private final String name;

    /** 表示色 #RRGGBB（任意・最大 7・提案の既定色） */
    @Size(max = 7)
    private final String color;

    /** 通知ソース種別 */
    @NotNull
    private final InboxSourceType sourceType;

    /** 各ソース PK */
    @NotNull
    private final Long sourceId;
}
