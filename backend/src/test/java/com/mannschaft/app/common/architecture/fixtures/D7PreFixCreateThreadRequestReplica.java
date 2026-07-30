package com.mannschaft.app.common.architecture.fixtures;

/**
 * D-7 番人の<b>回帰固定</b>用 fixture:
 * 是正前の {@code bulletin.dto.CreateThreadRequest} と<b>構造的に同型</b>の壊れた DTO。
 *
 * <p>特徴（＝ D-7 が捕まえるべき病型）:
 * 全フィールド {@code final}・getter のみで setter 無し・引数無しコンストラクタ無し・
 * 後方互換用の短いコンストラクタと完全コンストラクタの<b>2 本</b>があり
 * {@code @JsonCreator} が<b>付いていない</b>。
 *
 * <p>この形の DTO を {@code @RequestBody} で受けると Jackson がデシリアライザを構築できず
 * （no suitable creator）、当該エンドポイントは body の内容によらず<b>常に 500</b> になる。
 * 本 fixture が検出されなくなったら番人が壊れている。
 *
 * <p>注: 本リポの test ソースセットには Lombok が入っていない（{@code build.gradle.kts} で
 * {@code compileOnly}/{@code annotationProcessor} のみ＝ main 限定）ため、fixture は
 * 素の Java で書く。Lombok 生成物の本数の裏取りは、実際に Lombok でビルドされる
 * <b>main 配下の実 DTO</b> に対して {@link
 * com.mannschaft.app.common.architecture.JsonRequestBodyCreatorConditionTest} が行う。
 */
public class D7PreFixCreateThreadRequestReplica {

    private final Long categoryId;

    private final String title;

    private final String body;

    /** 後方互換用の短いコンストラクタ。 */
    public D7PreFixCreateThreadRequestReplica(Long categoryId, String title) {
        this(categoryId, title, null);
    }

    /** 完全コンストラクタ（是正前は {@code @JsonCreator} が付いていなかった）。 */
    public D7PreFixCreateThreadRequestReplica(Long categoryId, String title, String body) {
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
