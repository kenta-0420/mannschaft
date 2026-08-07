package com.mannschaft.app.common.architecture.fixtures;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * D-7 番人の偽陰性 fixture: {@code @JsonCreator} が<b>2 本のコンストラクタに二重付与</b>された DTO。
 *
 * <p>「{@code @JsonCreator} が 1 つでもあれば合格」という判定は、この形を素通りさせる。
 * Jackson は properties-based creator を<b>ちょうど 1 本</b>しか採れないため、
 * 2 本宣言されると {@code InvalidDefinitionException}（Conflicting property-based creators）で
 * デシリアライザの構築に失敗し、当該エンドポイントは常時 500 になる。
 *
 * <p>この「本当に壊れる」ことは
 * {@link com.mannschaft.app.common.architecture.JsonRequestBodyCreatorRuntimeProofTest}
 * が本番同等設定の実 {@code ObjectMapper} で実測して固定している。
 */
public class D7DualJsonCreatorRequest {

    private final Long categoryId;

    private final String title;

    private final String body;

    /** 1 本目の properties-based creator。 */
    @JsonCreator
    public D7DualJsonCreatorRequest(
            @JsonProperty("categoryId") Long categoryId,
            @JsonProperty("title") String title) {
        this(categoryId, title, null);
    }

    /** 2 本目の properties-based creator（この二重宣言が病因）。 */
    @JsonCreator
    public D7DualJsonCreatorRequest(
            @JsonProperty("categoryId") Long categoryId,
            @JsonProperty("title") String title,
            @JsonProperty("body") String body) {
        this.categoryId = categoryId;
        this.title = title;
        this.body = body;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }
}
