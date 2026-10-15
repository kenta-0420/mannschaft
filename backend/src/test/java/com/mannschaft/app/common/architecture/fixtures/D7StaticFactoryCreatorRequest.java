package com.mannschaft.app.common.architecture.fixtures;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * D-7 番人の<b>偽陽性ゼロ</b>証明用 fixture: creator を<b>static ファクトリメソッド</b>で
 * 与えている DTO（コンストラクタは 2 本・いずれも無注釈）。
 *
 * <p>Jackson は {@code @JsonCreator} 付き static ファクトリも creator として採用するため、
 * コンストラクタが複数あっても壊れない。番人がコンストラクタだけでなく static ファクトリも
 * 見ていることの担保。
 */
public class D7StaticFactoryCreatorRequest {

    private final String title;

    private final String body;

    public D7StaticFactoryCreatorRequest(String title) {
        this(title, null);
    }

    public D7StaticFactoryCreatorRequest(String title, String body) {
        this.title = title;
        this.body = body;
    }

    /** Jackson のデシリアライズ入口となる static ファクトリ。 */
    @JsonCreator
    public static D7StaticFactoryCreatorRequest of(
            @JsonProperty("title") String title,
            @JsonProperty("body") String body) {
        return new D7StaticFactoryCreatorRequest(title, body);
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }
}
