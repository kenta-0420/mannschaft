package com.mannschaft.app.disclosure.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 重要事項説明書 ドラフト作成 / 更新リクエスト DTO（F09.14 Phase 2-β-4）。
 *
 * <p>設計書 §4 POST /disclosure-drafts および PUT /disclosure-drafts/{id} のリクエスト形状。
 * 作成時は {@code templateId} 必須、{@code formData} は省略可（{@code {}} で初期化）。
 * 更新時は楽観的ロック用に {@code version} を必ず指定する。</p>
 *
 * @param templateId            様式テンプレート ID（作成時必須、更新時不要 → 別フィールドで上書き禁止）
 * @param title                 ドラフトタイトル（必須）
 * @param targetDwellingUnitId  対象居室 ID（任意、物件全体ドラフトでは null）
 * @param formData              入力済み JSON（任意、null の場合は呼び出し側で空オブジェクト初期化）
 * @param version               楽観的ロック用バージョン（更新時必須、作成時 null）
 */
public record DisclosureFormDraftRequest(
        Long templateId,
        @NotBlank(message = "title は必須です")
        @Size(max = 200, message = "title は200文字以下で指定してください")
        String title,
        Long targetDwellingUnitId,
        JsonNode formData,
        Long version
) {
}
