package com.mannschaft.app.common.architecture.fixtures;

/**
 * D-7 番人の偽陰性 fixture: <b>{@code @Valid} だけが付いた複合型引数</b>で受け取られる壊れた DTO。
 *
 * <p>{@code @Valid} は<b>バインド元を決める注釈ではない</b>（検証を要求するだけ）。
 * Spring から見ればバインド注釈が無いのと同じで、暗黙の {@code @ModelAttribute} として
 * {@code BeanUtils.getResolvableConstructor} で実体化される。
 * したがって「注釈が 1 つでも付いていればフォーム経路ではない」という判定にすると、
 * この形が<b>どの経路の検査対象にも入らず</b>素通りする。
 *
 * <p>構造は是正前 {@code CreateThreadRequest} と同型（2 ctor・引数無しコンストラクタ無し）で、
 * バインダは実体を作れず常時 500 になる。
 */
public class D7ValidOnlyBoundRequest {

    private final String keyword;

    private final Integer page;

    private final Integer size;

    /**
     * 後方互換用の短いコンストラクタ。
     *
     * <p>引数を 2 つ以上にしているのは意図的で、引数 1 本のコンストラクタを置くと Jackson が
     * それを delegating creator 候補として拾ってしまい、「creator を 1 本も作れない」という
     * 検証したい壊れ方（実測 2026-08-05）にならないため。
     */
    public D7ValidOnlyBoundRequest(String keyword, Integer page) {
        this(keyword, page, null);
    }

    /** 完全コンストラクタ。 */
    public D7ValidOnlyBoundRequest(String keyword, Integer page, Integer size) {
        this.keyword = keyword;
        this.page = page;
        this.size = size;
    }

    public Integer getSize() {
        return size;
    }

    public String getKeyword() {
        return keyword;
    }

    public Integer getPage() {
        return page;
    }
}
