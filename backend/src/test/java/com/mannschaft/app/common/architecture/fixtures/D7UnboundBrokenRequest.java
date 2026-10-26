package com.mannschaft.app.common.architecture.fixtures;

/**
 * D-7 番人の<b>検査範囲</b>証明用 fixture: {@link D7PreFixCreateThreadRequestReplica} と
 * 構造は同じ（＝コンストラクタ 2 本・{@code @JsonCreator} 無し・引数無しコンストラクタ無し）だが、
 * <b>どの Controller の {@code @RequestBody} からも到達しない</b>クラス。
 *
 * <p>JSON デシリアライズされない以上「常時 500」は起きないため、番人は<b>これを違反にしてはならない</b>。
 * {@code *Request} という命名だけで検査対象を決めると本 fixture を誤検出することになる
 * （番人が到達可能性で対象を決めていることの担保）。
 */
public class D7UnboundBrokenRequest {

    private final String title;

    private final String body;

    public D7UnboundBrokenRequest(String title) {
        this(title, null);
    }

    public D7UnboundBrokenRequest(String title, String body) {
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
