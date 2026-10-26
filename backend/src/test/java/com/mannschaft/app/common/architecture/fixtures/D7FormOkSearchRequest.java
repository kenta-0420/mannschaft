package com.mannschaft.app.common.architecture.fixtures;

/**
 * D-7 番人の<b>フォーム経路の偽陽性ゼロ</b>証明用 fixture:
 * main の実在例（{@code recruitment.dto.RecruitmentListingSearchRequest}）と同じく、
 * 引数無しコンストラクタ ＋ setter を持つ {@code @ModelAttribute} DTO。
 *
 * <p>{@code BeanUtils.getResolvableConstructor} が引数無しコンストラクタを解決できるため、
 * 番人は<b>検出してはならない</b>。
 */
public class D7FormOkSearchRequest {

    private String keyword;

    private Integer page;

    public D7FormOkSearchRequest() {
    }

    public D7FormOkSearchRequest(String keyword, Integer page) {
        this.keyword = keyword;
        this.page = page;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }
}
