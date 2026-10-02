package com.mannschaft.app.membership.fanout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-4（TEAM fan-out キーセット被覆索引）の<b>DDL＋索引の存在を固定</b>するガードテスト（Spring context 不要）。
 *
 * <p>TEAM スコープの受信者ストリーム配信は memberships を
 * {@code (scope_type, scope_id, left_at, user_id)} の順で index-only 走査する。この被覆索引が
 * <b>マイグレーションに宣言されている</b>ことをクラスパス上の SQL から確認する（村 V170 と同型）。</p>
 *
 * <p>本テストは DDL 資産の存在ガードであり、DDL を作成した本試練では green で成立する
 * （受信者供給の red は {@code TeamFanoutRecipientSource.nextPage} を叩く IT 側で表現する）。</p>
 */
@DisplayName("AC-4 TEAM fan-out キーセット被覆索引 DDL 存在ガード")
class TeamFanoutKeysetIndexDdlTest {

    private static final String INDEX_DDL =
            "db/migration/V174.20260804095715__add_membership_fanout_keyset_index.sql";

    private static String readClasspath(String path) {
        try (InputStream in = TeamFanoutKeysetIndexDdlTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("マイグレーションがクラスパスに存在する: " + path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new AssertionError("マイグレーション読込に失敗: " + path, e);
        }
    }

    @Test
    @DisplayName("AC-4: memberships の (scope_type, scope_id, left_at, user_id) 被覆索引が宣言されている")
    void keysetIndexDeclared() {
        String sql = readClasspath(INDEX_DDL);
        assertThat(sql).as("索引作成対象は memberships").contains("on memberships");
        assertThat(sql).as("索引名").contains("idx_membership_fanout_keyset");
        // 列順は 等値（scope_type, scope_id）→ フィルタ（left_at）→ カーソル/ソート（user_id）。
        assertThat(sql)
                .as("被覆索引の列順（等値→フィルタ→カーソル）")
                .containsPattern("scope_type\\s*,\\s*scope_id\\s*,\\s*left_at\\s*,\\s*user_id");
    }
}
