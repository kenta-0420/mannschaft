package com.mannschaft.app.organization.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

/**
 * 祖先組織1件のレスポンス DTO。
 *
 * <p>{@code GET /api/v1/organizations/{id}/ancestors} の {@code data} 配列要素として使用する。
 * {@code hidden = true} の場合は {@code id} のみを返し、他フィールドは null として
 * Jackson の {@link JsonInclude} 設定により JSON 出力から除外する。これは
 * F01.2 設計書「祖先個別の返却フィルタ」に従う情報漏洩防止策である。</p>
 *
 * <p>各フィールドが返るかどうかは「呼び出し者の所属」と「祖先の visibility / hierarchyVisibility」の
 * 組み合わせで決まる。詳細は {@code OrganizationService#getAncestors(Long, Long)} 参照。</p>
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AncestorOrganizationResponse {

    /** 祖先組織 ID（常に返す） */
    private final Long id;

    /** 組織名 */
    private final String name;

    /** ニックネーム1 */
    private final String nickname1;

    /** 組織概要（hierarchyVisibility=BASIC でも返す） */
    private final String description;

    /** アイコン URL */
    private final String iconUrl;

    /** 公開範囲（PUBLIC/PRIVATE）。フル情報・PUBLIC 限定情報のみ含める */
    private final String visibility;

    /**
     * プレースホルダフラグ。{@code true} の場合、フィールドは {@code id} のみ。
     * {@code false} の場合、表示可能なフィールドが含まれる。
     */
    private final boolean hidden;
}
