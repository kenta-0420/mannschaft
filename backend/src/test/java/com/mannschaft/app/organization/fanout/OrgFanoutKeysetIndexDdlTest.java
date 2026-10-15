package com.mannschaft.app.organization.fanout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fan-out 抜本改修 Wave-2（ORG 耐久 fan-out）第一陣 DDL（V175）の<b>存在ガード</b>（Spring context 不要）。
 *
 * <p>ORG 版キーセット native クエリ
 * （{@code UserRoleRepository#findDistributionUserIdsForOrganizationRecursiveKeyset}）は
 * user_roles を {@code (organization_id, user_id)} / {@code (team_id, user_id)} の順で index レンジ走査し、
 * {@code ORDER BY user_id} を効かせて LIMIT に早期到達する。この被覆補助索引と、SUPPORTER 除外制御列
 * {@code include_supporters} が<b>マイグレーションに宣言されている</b>ことをクラスパス上の SQL から確認する
 * （TEAM 版 {@code TeamFanoutKeysetIndexDdlTest} と同型）。</p>
 *
 * <p>本テストは DDL 資産の存在ガードであり、DDL を作成済みの第一陣土台上では green で成立する
 * （受信者供給の red は {@link OrgFanoutRecipientSourceRedIT} 側で表現する）。</p>
 */
@DisplayName("fan-out Wave-2 ORG キーセット被覆索引／include_supporters 列 DDL 存在ガード")
class OrgFanoutKeysetIndexDdlTest {

    private static final String INDEX_DDL =
            "db/migration/V175.20260805030233__add_fanout_include_supporters.sql";

    private static String readClasspath(String path) {
        try (InputStream in = OrgFanoutKeysetIndexDdlTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("マイグレーションがクラスパスに存在する: " + path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new AssertionError("マイグレーション読込に失敗: " + path, e);
        }
    }

    @Test
    @DisplayName("AC-13: user_roles の (organization_id, user_id) / (team_id, user_id) キーセット被覆索引が宣言されている")
    void keysetIndexDeclared() {
        String sql = readClasspath(INDEX_DDL);
        assertThat(sql).as("索引作成対象は user_roles").contains("on user_roles");
        assertThat(sql).as("組織×user_id 被覆索引名").contains("idx_user_roles_org_user_keyset");
        assertThat(sql).as("チーム×user_id 被覆索引名").contains("idx_user_roles_team_user_keyset");
        // 列順は 等値（organization_id / team_id）→ カーソル/ソート（user_id）。
        assertThat(sql)
                .as("組織×user_id 索引の列順（等値→カーソル）")
                .containsPattern("organization_id\\s*,\\s*user_id");
        assertThat(sql)
                .as("チーム×user_id 索引の列順（等値→カーソル）")
                .containsPattern("team_id\\s*,\\s*user_id");
    }

    @Test
    @DisplayName("AC-5: notification_fanout_jobs に include_supporters 列が追加されている（既定 TRUE=旧経路互換）")
    void includeSupportersColumnDeclared() {
        String sql = readClasspath(INDEX_DDL);
        assertThat(sql).as("対象表は notification_fanout_jobs").contains("notification_fanout_jobs");
        assertThat(sql).as("SUPPORTER 除外制御列").contains("include_supporters");
        assertThat(sql)
                .as("後方互換のため既定 TRUE（旧 VILLAGE 経路と同じ全員配信）")
                .containsPattern("include_supporters\\s+boolean\\s+not\\s+null\\s+default\\s+true");
    }
}
