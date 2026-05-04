package com.mannschaft.app.common.visibility.perf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SqlIntentCounter} の自己検証テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §13.4。
 *
 * <p>Hibernate との結合検証は Phase B 以降の実 Resolver 性能テストで担保するため、
 * 本テストでは純粋に「捕捉リスト・カウンタ・絞り込み」の振る舞いのみ検証する。
 */
@DisplayName("SqlIntentCounter ヘルパ")
class SqlIntentCounterTest {

    private SqlIntentCounter counter;

    @BeforeEach
    void setUp() {
        // Hibernate がインスタンス化することを想定して no-arg で生成
        this.counter = new SqlIntentCounter();
        SqlIntentCounter.reset();
    }

    @Test
    @DisplayName("reset 直後は totalCount が 0")
    void totalCount_isZeroAfterReset() {
        assertThat(SqlIntentCounter.totalCount()).isZero();
        assertThat(SqlIntentCounter.capturedSqls()).isEmpty();
    }

    @Test
    @DisplayName("inspect は SQL を捕捉し原文を返す")
    void inspect_capturesAndReturnsOriginalSql() {
        String sql = "SELECT * FROM blog_posts WHERE id = ?";

        String returned = counter.inspect(sql);

        assertThat(returned).isEqualTo(sql);
        assertThat(SqlIntentCounter.totalCount()).isEqualTo(1);
        assertThat(SqlIntentCounter.capturedSqls()).containsExactly(sql);
    }

    @Test
    @DisplayName("intentCount はテーブル名ヒントを含む SQL のみカウントする")
    void intentCount_filtersByTableHint() {
        counter.inspect("SELECT * FROM blog_posts WHERE id IN (?, ?)");
        counter.inspect("SELECT * FROM user_roles WHERE user_id = ?");
        counter.inspect("SELECT id FROM blog_posts WHERE org_id = ?");
        counter.inspect("SELECT 1 FROM dual");

        assertThat(SqlIntentCounter.intentCount("blog_posts")).isEqualTo(2);
        assertThat(SqlIntentCounter.intentCount("user_roles")).isEqualTo(1);
        assertThat(SqlIntentCounter.intentCount("nonexistent")).isZero();
        assertThat(SqlIntentCounter.totalCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("intentCount は大文字小文字を無視する")
    void intentCount_isCaseInsensitive() {
        counter.inspect("SELECT * FROM BLOG_POSTS WHERE id = ?");
        counter.inspect("select * from blog_posts where id = ?");

        assertThat(SqlIntentCounter.intentCount("blog_posts")).isEqualTo(2);
        assertThat(SqlIntentCounter.intentCount("BLOG_POSTS")).isEqualTo(2);
        assertThat(SqlIntentCounter.intentCount("Blog_Posts")).isEqualTo(2);
    }

    @Test
    @DisplayName("reset で捕捉済み SQL がクリアされる")
    void reset_clearsCapturedSqls() {
        counter.inspect("SELECT * FROM blog_posts");
        counter.inspect("SELECT * FROM user_roles");
        assertThat(SqlIntentCounter.totalCount()).isEqualTo(2);

        SqlIntentCounter.reset();

        assertThat(SqlIntentCounter.totalCount()).isZero();
        assertThat(SqlIntentCounter.capturedSqls()).isEmpty();
        assertThat(SqlIntentCounter.intentCount("blog_posts")).isZero();
    }

    @Test
    @DisplayName("capturedSqls は不変コピーを返す")
    void capturedSqls_returnsImmutableCopy() {
        counter.inspect("SELECT * FROM blog_posts");

        var snapshot = SqlIntentCounter.capturedSqls();

        assertThat(snapshot).hasSize(1);
        // 不変リストへの変更は UnsupportedOperationException になる
        assertThat(snapshot).isUnmodifiable();

        // 取得後の inspect は snapshot に影響しない
        counter.inspect("SELECT * FROM user_roles");
        assertThat(snapshot).hasSize(1);
        assertThat(SqlIntentCounter.totalCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("複数の SqlIntentCounter インスタンスは静的状態を共有する")
    void multipleInstances_shareStaticState() {
        // Hibernate が複数回インスタンス化するケースを想定
        SqlIntentCounter another = new SqlIntentCounter();
        counter.inspect("SELECT * FROM blog_posts");
        another.inspect("SELECT * FROM user_roles");

        assertThat(SqlIntentCounter.totalCount()).isEqualTo(2);
        assertThat(SqlIntentCounter.intentCount("blog_posts")).isEqualTo(1);
        assertThat(SqlIntentCounter.intentCount("user_roles")).isEqualTo(1);
    }
}
