package com.mannschaft.app.dashboard.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * F22.1: タグ表示順の一括更新リクエスト（UPSERT）。
 *
 * <p>JSON はキャメルケース（{@code scopeType} / {@code orders[].scopeId} / {@code orders[].sortOrder}）。
 * 設計書 02_api_design.md §3.2 / §4 のとおり。</p>
 *
 * <p>所属検証（非所属混入 → 全体 403 / SCOPE_TAB_001）と sortOrder の一意性（SCOPE_TAB_002）・
 * scopeType の妥当性（SCOPE_TAB_003）はサービス層で行う。Bean Validation は構造的な制約のみ。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ScopeTabOrderUpdateRequest {

    /**
     * スコープ種別（{@code TEAM} / {@code ORGANIZATION}）。
     * 値の妥当性検証はサービス層（SCOPE_TAB_003）で行う。
     */
    @NotNull
    private String scopeType;

    /** 並べ替え対象（1〜200 件）。 */
    @NotEmpty
    @Size(min = 1, max = 200)
    @Valid
    private List<OrderItem> orders;

    /**
     * 並べ替え 1 件。
     */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class OrderItem {

        /** チーム ID または 組織 ID。所属検証はサービス層（SCOPE_TAB_001）。 */
        @NotNull
        private Long scopeId;

        /** 表示順（0〜9999）。リクエスト内の一意性はサービス層（SCOPE_TAB_002）。 */
        @NotNull
        @Min(0)
        @Max(9999)
        private Integer sortOrder;
    }
}
