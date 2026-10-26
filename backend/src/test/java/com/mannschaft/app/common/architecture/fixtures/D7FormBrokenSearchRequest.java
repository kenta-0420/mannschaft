package com.mannschaft.app.common.architecture.fixtures;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * D-7 番人の<b>フォーム経路</b>証明用 fixture:
 * {@code @ModelAttribute} でバインドされる、コンストラクタ 2 本・引数無しコンストラクタ無しの DTO。
 *
 * <p>あえて <b>{@code @JsonCreator} を付けてある</b>。フォームバインドを担う
 * {@code ModelAttributeMethodProcessor} は {@code BeanUtils.getResolvableConstructor} で実体を作り、
 * これは「Kotlin primary → 宣言コンストラクタがちょうど 1 本 → 引数無しコンストラクタ」の順にしか
 * 解決せず <b>Jackson の注釈を一切見ない</b>。よって {@code @JsonCreator} があっても
 * {@code IllegalStateException} で 500 になる。
 *
 * <p>番人がフォーム経路で Jackson 免責を適用していないことの担保であり、
 * これは<b>検出しなければならない</b>。
 */
public class D7FormBrokenSearchRequest {

    private final String keyword;

    private final Integer page;

    public D7FormBrokenSearchRequest(String keyword) {
        this(keyword, null);
    }

    @JsonCreator
    public D7FormBrokenSearchRequest(
            @JsonProperty("keyword") String keyword,
            @JsonProperty("page") Integer page) {
        this.keyword = keyword;
        this.page = page;
    }

    public String getKeyword() {
        return keyword;
    }

    public Integer getPage() {
        return page;
    }
}
