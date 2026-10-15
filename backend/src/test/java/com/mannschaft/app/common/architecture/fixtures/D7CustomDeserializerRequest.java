package com.mannschaft.app.common.architecture.fixtures;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * D-7 番人の<b>免責が正しく効く</b>ことの証明用 fixture:
 * 構造は壊れている（コンストラクタ 2 本・{@code @JsonCreator} 無し・引数無しコンストラクタ無し）が、
 * {@code @JsonDeserialize(using = ...)} で<b>クラス自身の生成手段</b>が与えられている DTO。
 *
 * <p>カスタム deserializer がある以上コンストラクタ事情に依存しないため、番人は<b>検出してはならない</b>。
 */
@JsonDeserialize(using = D7CustomDeserializer.class)
public class D7CustomDeserializerRequest {

    private final String title;

    private final String body;

    public D7CustomDeserializerRequest(String title) {
        this(title, null);
    }

    public D7CustomDeserializerRequest(String title, String body) {
        this.title = title;
        this.body = body;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }
}
