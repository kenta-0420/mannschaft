package com.mannschaft.app.common.architecture.fixtures;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * D-7 番人の<b>抜け道封じ</b>証明用 fixture:
 * 構造は壊れている（コンストラクタ 2 本・{@code @JsonCreator} 無し・引数無しコンストラクタ無し）うえ、
 * {@code @JsonDeserialize} は付いているが指定が {@code contentUsing}
 * ＝<b>要素型の deserializer</b> であり、<b>このクラス自身の生成手段は一切与えていない</b>。
 *
 * <p>「{@code @JsonDeserialize} が付いていれば免責」という緩い実装だと本 fixture を素通りさせてしまう。
 * 番人は {@code using} / {@code builder} のみを免責し、これは<b>検出しなければならない</b>。
 * （{@code as} / {@code contentAs} / {@code keyAs} / {@code keyUsing} も同様に免責しない。）
 */
@JsonDeserialize(contentUsing = D7CustomDeserializer.class)
public class D7ContentDeserializeBrokenRequest {

    private final List<String> tags;

    private final String title;

    public D7ContentDeserializeBrokenRequest(List<String> tags) {
        this(tags, null);
    }

    public D7ContentDeserializeBrokenRequest(List<String> tags, String title) {
        this.tags = tags;
        this.title = title;
    }

    public List<String> getTags() {
        return tags;
    }

    public String getTitle() {
        return title;
    }
}
