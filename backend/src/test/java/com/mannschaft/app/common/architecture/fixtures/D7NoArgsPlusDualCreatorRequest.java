package com.mannschaft.app.common.architecture.fixtures;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * D-7 番人の偽陰性 fixture（#2613）: <b>引数無しコンストラクタがあり</b>、かつ
 * properties-based な {@code @JsonCreator} が<b>2 本</b>宣言された DTO。
 *
 * <p>{@code lacksUsableJacksonCreator} の是正前は、引数無しコンストラクタの有無による
 * 早期 return が二重 creator の検査より<b>前</b>にあったため、この形は番人を素通りしていた。
 * しかし Jackson は {@code _addDeserializerConstructors} で properties-based creator が
 * 2 本以上あると<b>無条件で</b> Conflicting property-based creators を投げる。
 * 引数無しコンストラクタの存在はこの衝突を一切抑止しない
 * （Jackson は creator の探索を先に行い、そこで例外を投げてしまうため既定コンストラクタへ
 * フォールバックする機会が無い）ため、この形は実行時に<b>常に</b>失敗する。
 *
 * <p>この「本当に壊れる」ことは
 * {@link com.mannschaft.app.common.architecture.JsonRequestBodyCreatorRuntimeProofTest}
 * が本番同等設定の実 {@code ObjectMapper} で実測して固定している。
 */
public class D7NoArgsPlusDualCreatorRequest {

    private Long categoryId;

    private String title;

    private String body;

    /** 引数無しコンストラクタ（この存在だけでは二重 creator の衝突を抑止できない）。 */
    public D7NoArgsPlusDualCreatorRequest() {
    }

    /** 1 本目の properties-based creator。 */
    @JsonCreator
    public D7NoArgsPlusDualCreatorRequest(
            @JsonProperty("categoryId") Long categoryId,
            @JsonProperty("title") String title) {
        this.categoryId = categoryId;
        this.title = title;
    }

    /** 2 本目の properties-based creator（この二重宣言が病因）。 */
    @JsonCreator
    public D7NoArgsPlusDualCreatorRequest(
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
