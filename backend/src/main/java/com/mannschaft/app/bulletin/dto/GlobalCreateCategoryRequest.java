package com.mannschaft.app.bulletin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * グローバル方式カテゴリ作成リクエスト DTO（F17.1 村掲示板グローバル方式）。
 *
 * <p>FE は {@code POST /api/v1/bulletin/categories} の body（JSON）にスコープ情報
 * （{@code scopeType / scopeId / scopeVillageId}）とカテゴリフィールドを同梱して送る
 * （{@code frontend/app/composables/bulletin/useBulletinCategories.ts createCategory()}）。
 * VILLAGE スコープでは {@code scopeId=0} + {@code scopeVillageId=<村UUID>} を渡す。</p>
 *
 * <p>本 DTO は受信専用で、サービス層へは {@link #toCreateCategoryRequest()} で
 * 既存 {@link CreateCategoryRequest} に変換して委譲する。Jackson の bean バインディングに
 * 対応するため {@code @NoArgsConstructor + @Setter} とする。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class GlobalCreateCategoryRequest {

    /** スコープ種別（{@code VILLAGE / ORGANIZATION / TEAM / PERSONAL}）。 */
    @NotBlank
    private String scopeType;

    /** スコープ ID（VILLAGE 時は 0）。 */
    private Long scopeId;

    /** 村スコープ ID（VILLAGE 時必須）。 */
    private UUID scopeVillageId;

    @NotBlank
    @Size(max = 50)
    private String name;

    @Size(max = 200)
    private String description;

    private Integer displayOrder;

    @Size(max = 7)
    private String color;

    @Size(max = 20)
    private String postMinRole;

    /**
     * サービス層が要求する {@link CreateCategoryRequest} へ変換する。
     */
    public CreateCategoryRequest toCreateCategoryRequest() {
        return new CreateCategoryRequest(name, description, displayOrder, color, postMinRole);
    }
}
