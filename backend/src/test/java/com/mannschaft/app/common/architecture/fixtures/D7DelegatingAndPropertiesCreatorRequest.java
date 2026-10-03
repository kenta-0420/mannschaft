package com.mannschaft.app.common.architecture.fixtures;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * D-7 番人の<b>偽陽性</b>防止 fixture:
 * {@code @JsonCreator} が 2 本あるが、片方が delegating・もう片方が properties-based の DTO。
 *
 * <p>Jackson は delegating creator と properties-based creator を<b>各 1 本ずつ</b>持てるため、
 * この形は正常に往復できる。「{@code @JsonCreator} が 2 本あれば違反」という素朴な条件にすると
 * この形を誤検出するため、番人は<b>properties-based の本数</b>だけを数える。
 *
 * <p>往復できることの実測固定は
 * {@link com.mannschaft.app.common.architecture.JsonRequestBodyCreatorRuntimeProofTest}。
 */
public class D7DelegatingAndPropertiesCreatorRequest {

    private final Long categoryId;

    private final String title;

    /** delegating creator（JSON 値そのものを 1 引数で受ける）。 */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public D7DelegatingAndPropertiesCreatorRequest(String title) {
        this(null, title);
    }

    /** properties-based creator。 */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public D7DelegatingAndPropertiesCreatorRequest(
            @JsonProperty("categoryId") Long categoryId,
            @JsonProperty("title") String title) {
        this.categoryId = categoryId;
        this.title = title;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getTitle() {
        return title;
    }
}
