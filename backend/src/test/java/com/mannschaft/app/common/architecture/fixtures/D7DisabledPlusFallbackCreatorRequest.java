package com.mannschaft.app.common.architecture.fixtures;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * D-7 番人の<b>偽陽性</b>防止 fixture: コンストラクタ 2 本のうち片方だけに
 * {@code @JsonCreator(mode = Mode.DISABLED)} が付いた DTO。
 *
 * <p><b>実測（2026-08-05）でこの形は正常に往復する</b>。打ち消しによって候補が 1 本に絞られ、
 * 残ったコンストラクタが {@code -parameters} ＋ {@code ParameterNamesModule} で
 * 暗黙 creator として採用されるためである。
 * 「{@code DISABLED} が付いていたら違反」という素朴な条件にすると、この形を誤検出する。
 *
 * <p>実測固定は
 * {@link com.mannschaft.app.common.architecture.JsonRequestBodyCreatorRuntimeProofTest}。
 */
public class D7DisabledPlusFallbackCreatorRequest {

    private final Long categoryId;

    private final String title;

    /** 打ち消されていない候補。これが暗黙 creator になる。 */
    public D7DisabledPlusFallbackCreatorRequest(Long categoryId, String title) {
        this.categoryId = categoryId;
        this.title = title;
    }

    /** creator としては使わせないコンストラクタ。 */
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    public D7DisabledPlusFallbackCreatorRequest(
            @JsonProperty("categoryId") Long categoryId) {
        this(categoryId, null);
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getTitle() {
        return title;
    }
}
