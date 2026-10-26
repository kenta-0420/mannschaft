package com.mannschaft.app.common.architecture.fixtures;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * D-7 番人の<b>偽陽性ゼロ</b>証明用 fixture: 是正の金型
 * （{@code chat.dto.SendMessageRequest}）と同型の<b>合格</b>する DTO。
 *
 * <p>{@link D7PreFixCreateThreadRequestReplica} との差は完全コンストラクタの
 * {@code @JsonCreator} ＋ 各引数の {@code @JsonProperty} のみ。ここだけで合否が分かれることを
 * 担保する（＝番人がコンストラクタ本数ではなく creator の有無で判定していることの証明）。
 */
public class D7JsonCreatorRequest {

    private final Long categoryId;

    private final String title;

    private final String body;

    /** 後方互換用の短いコンストラクタ。 */
    public D7JsonCreatorRequest(Long categoryId, String title) {
        this(categoryId, title, null);
    }

    /** 完全コンストラクタ（Jackson のデシリアライズ用に一意特定される）。 */
    @JsonCreator
    public D7JsonCreatorRequest(
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
