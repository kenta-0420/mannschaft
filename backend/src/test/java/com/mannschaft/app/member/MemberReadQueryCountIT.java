package com.mannschaft.app.member;

import org.awaitility.Awaitility;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #3387 D-3T 根治 試練 — AC-D3T-11（性能。軍議書 gungi-3387-d3t.md 第3版 §7・§8）。
 *
 * <p><b>テストの形</b>: 非 {@code @Transactional}（{@link MemberTxBoundaryITSupport}）。テスト側に TX があると
 * 各 Repository の TX がそこへ合流し、変更前後の差が消える。フィクスチャは測定窓より前に確定している。</p>
 *
 * <p><b>測定窓</b>: 直前に Statistics の元の有効状態を退避して有効化・clear、1経路を1回呼び、値を読んだ後
 * {@code finally} で元に戻す。差分ありの PUT は監査行を待ってから窓を閉じる（非同期の監査の SQL を毎回
 * 同じように窓へ入れる）。</p>
 *
 * <p><b>指標</b>: SQL 数（{@code getPrepareStatementCount}）・TX 数（{@code getTransactionCount}）・接続取得数
 * （{@code getConnectCount}）を別々に記録する。合否は SQL 数のみ {@code ≤ ceil(基準値 × 1.5)}。TX 数と
 * 接続数は標準出力に記録するだけ（PR 本文と F06.6 の表に転記する）。</p>
 *
 * <p><b>排他</b>: Statistics は SessionFactory 全体で共有されるため {@link Isolated}。</p>
 */
@Isolated
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR #3387 D-3T 試練 AC-11: 閲覧・更新の SQL 数は基準値×1.5 以内（実 DB）")
class MemberReadQueryCountIT extends MemberTxBoundaryITSupport {

    /**
     * 経路ごとの基準値（SQL 数）。
     *
     * <p>実測: 本修正の直前の PR HEAD {@code 3bc428174c}（main 取り込みマージ {@code 4903b319e8} 上。
     * member／AccessControlService／NameResolverService の本番コードは {@code 3bc428174c} と差分なし）、
     * 2026-09-30、CI（GitHub Actions run 36666196989）で本クラスを実行し、標準出力の
     * {@code prepareStatementCount} を転記した。
     * main を基準にしない理由は軍議書 AC-11（PR 自身が判定クエリを意図的に追加しているため）。</p>
     */
    enum MeasuredPath {
        GET_PAGE_MEM(15),
        LIST_SECTIONS_MEM(14),
        LIST_PROFILES_MEM(17),
        GET_PROFILE_MEM(14),
        LOOKUP_MEM(8),
        LIST_ORG_PAGES_MEM(19),
        GET_SETTINGS_MEM(9),
        PUT_SETTINGS_DIFF_ADM(11),
        PUT_SETTINGS_NO_DIFF_ADM(8);

        final long baselinePrepareStatements;

        MeasuredPath(long baselinePrepareStatements) {
            this.baselinePrepareStatements = baselinePrepareStatements;
        }
    }

    private RequestBuilder request(MeasuredPath path) throws Exception {
        return switch (path) {
            case GET_PAGE_MEM -> getPageRequest(pubPageId);
            case LIST_SECTIONS_MEM -> listSectionsRequest(pubPageId);
            case LIST_PROFILES_MEM -> listProfilesRequest(pubPageId);
            case GET_PROFILE_MEM -> getProfileRequest(visibleProfileId);
            case LOOKUP_MEM -> lookupRequest(pubPageId);
            case LIST_ORG_PAGES_MEM -> listOrgPagesRequest();
            case GET_SETTINGS_MEM -> getSettingsRequest();
            case PUT_SETTINGS_DIFF_ADM -> putSettingsRequest("member_profiles", "PUBLIC");
            case PUT_SETTINGS_NO_DIFF_ADM -> putSettingsRequest("member_profiles", "MEMBER");
        };
    }

    private Long viewer(MeasuredPath path) {
        return path.name().endsWith("_ADM") ? admId : memId;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MeasuredPath.class)
    @DisplayName("AC-11: 経路ごとの SQL 数が ceil(基準値×1.5) 以内（TX 数・接続数は記録のみ）")
    void SQL数は基準値の1_5倍以内(MeasuredPath path) throws Exception {
        if (path == MeasuredPath.GET_SETTINGS_MEM) {
            // 更新者名の解決まで通る形で測る（設定行が無いと名前解決が走らない）
            setProfilesSubtab(com.mannschaft.app.dashboard.MinRole.SUPPORTER);
        }
        RequestBuilder request = request(path);
        long auditBefore = countAuditRows();
        setAuth(viewer(path));
        awaitEventPoolIdle();

        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean originallyEnabled = stats.isStatisticsEnabled();
        long prepared;
        long transactions;
        long connects;
        int status;
        try {
            stats.setStatisticsEnabled(true);
            stats.clear();

            status = mockMvc.perform(request).andReturn().getResponse().getStatus();
            if (path == MeasuredPath.PUT_SETTINGS_DIFF_ADM) {
                Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> countAuditRows() == auditBefore + 1);
                awaitEventPoolIdle();
            }

            prepared = stats.getPrepareStatementCount();
            transactions = stats.getTransactionCount();
            connects = stats.getConnectCount();
        } finally {
            stats.setStatisticsEnabled(originallyEnabled);
        }

        System.out.printf("AC-11 実測 path=%s status=%d prepareStatementCount=%d transactionCount=%d connectCount=%d%n",
                path, status, prepared, transactions, connects);

        assertThat(status).as("前提: %s は 200", path).isEqualTo(200);
        assertThat(path.baselinePrepareStatements)
                .as("基準値が未設定（実測値 prepareStatementCount=%d を定数へ転記すること）", prepared)
                .isGreaterThanOrEqualTo(0);
        long limit = (long) Math.ceil(path.baselinePrepareStatements * 1.5);
        assertThat(prepared)
                .as("%s の SQL 数（基準値 %d・上限 %d）", path, path.baselinePrepareStatements, limit)
                .isLessThanOrEqualTo(limit);
    }
}
