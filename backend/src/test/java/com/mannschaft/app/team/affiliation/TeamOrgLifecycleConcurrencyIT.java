package com.mannschaft.app.team.affiliation;

import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 部隊 2-D — アーカイブと、申請・招待の作成の並行（§6.9）の統合テスト（試練）。
 *
 * <ul>
 *   <li>AC-E07: 申請と、アーカイブ（チーム・組織）を並行に実行しても、アーカイブ済みの相手への PENDING が残らない</li>
 *   <li>AC-E08: 招待と、アーカイブ（チーム・組織）を並行に実行しても PENDING が残らず、デッドロックも起きない</li>
 * </ul>
 *
 * <p><b>「ロックが無ければ落ちる」ことの担保</b>: 並行の IT は時間の偶然に頼ると、ロックを外しても緑になりうる。
 * そこで本クラスは、競走（自由に並行させる）に加えて、<b>行ロックを別トランザクションで保持して順序を固定する</b>
 * 決定的なテストを置く。</p>
 * <ul>
 *   <li><b>作成が先</b>: 作成側が（チーム行 → 組織行の順に {@code FOR UPDATE} を取って）PENDING を INSERT した状態で
 *       コミットを保留する。アーカイブはロック待ちになり（保留中は完了しない）、作成のコミット後に進んで、
 *       いま INSERT された PENDING も消し、相手側へ取消を通知する。アーカイブ側がロックを取らない・片付けない実装は、
 *       待たずに終わるか PENDING を残すので赤になる。</li>
 *   <li><b>アーカイブが先</b>: アーカイブ側がロックを取ってコミットを保留する。作成はロック待ちになり（保留中は完了しない）、
 *       アーカイブのコミット後にロックを得て、状態を再確認して 4xx で拒否する（PENDING を INSERT しない）。
 *       アーカイブがロックを取らない実装は、作成が待たずに PENDING を作るので赤になる。</li>
 * </ul>
 *
 * <p>時間は固定する必要のある判定を持たない（60日・30日などの期限は関係しない）ため、待ちは固定の
 * {@code Thread.sleep} とロックの保留だけで構成する。作成側の応答は、ルーティング抜けによる偽の緑を避けるため、
 * 201 か、業務のエラーコード（{@code TEAM_*}・{@code ORG_*}）であることを確かめる。</p>
 *
 * <p>E08 の招待 API（{@code POST /organizations/{slug}/team-invites}）は 2-C の成果物。ベースに 2-C が無いと
 * 作成側が 404 になって赤になる（出陣では最新の main を取り込んでから緑化する）。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-D アーカイブと申請・招待の並行（行ロックの直列化）")
class TeamOrgLifecycleConcurrencyIT extends TeamOrgLifecycleCommittedItSupport {

    private static final String CANCELLED = "TEAM_ORG_PENDING_CANCELLED_BY_SYSTEM";
    private static final int RACE_ROUNDS = 5;
    /** ロック待ちが「まだ終わっていない」ことを確かめるための待ち時間。 */
    private static final long BLOCK_CHECK_MILLIS = 1500;

    /** アーカイブする側。 */
    enum Target { TEAM, ORG }

    /** PENDING を作る側（APPLY＝チームからの申請、INVITE＝組織からの招待）。 */
    enum Creator { APPLY, INVITE }

    @Autowired
    private TeamService teamService;

    @Autowired
    private OrganizationService organizationService;

    @AfterEach
    void cleanUp() {
        deleteCommittedFixtures(jdbc);
    }

    // =====================================================================
    // AC-E07 申請とアーカイブ
    // =====================================================================

    @ParameterizedTest(name = "[{index}] {0} のアーカイブ")
    @EnumSource(Target.class)
    @DisplayName("AC-E07 申請が先にロックを取って PENDING を INSERT した状態では、アーカイブは待ち、"
            + "コミット後にその PENDING も消して相手側に取消を通知する")
    void E07_申請が先ならアーカイブは待ってPENDINGも消す(Target target) throws Exception {
        creatorFirstArchiveWaits(Creator.APPLY, target);
    }

    @ParameterizedTest(name = "[{index}] {0} のアーカイブ")
    @EnumSource(Target.class)
    @DisplayName("AC-E07 アーカイブが先にロックを取った状態では、申請は待ち、コミット後にアーカイブ済みとして拒否される（PENDING は作られない）")
    void E07_アーカイブが先なら申請は待って拒否される(Target target) throws Exception {
        archiveFirstCreatorWaits(Creator.APPLY, target);
    }

    @ParameterizedTest(name = "[{index}] {0} のアーカイブ")
    @EnumSource(Target.class)
    @DisplayName("AC-E07 申請とアーカイブを並行に何度実行しても、アーカイブ済みの相手への PENDING が残らず、デッドロックしない")
    void E07_並行に実行してもPENDINGが残らない(Target target) throws Exception {
        race(Creator.APPLY, target);
    }

    // =====================================================================
    // AC-E08 招待とアーカイブ
    // =====================================================================

    @ParameterizedTest(name = "[{index}] {0} のアーカイブ")
    @EnumSource(Target.class)
    @DisplayName("AC-E08 招待が先にロックを取って PENDING を INSERT した状態では、アーカイブは待ち、"
            + "コミット後にその PENDING も消して相手側に取消を通知する")
    void E08_招待が先ならアーカイブは待ってPENDINGも消す(Target target) throws Exception {
        creatorFirstArchiveWaits(Creator.INVITE, target);
    }

    @ParameterizedTest(name = "[{index}] {0} のアーカイブ")
    @EnumSource(Target.class)
    @DisplayName("AC-E08 アーカイブが先にロックを取った状態では、招待は待ち、コミット後にアーカイブ済みとして拒否される（PENDING は作られない）")
    void E08_アーカイブが先なら招待は待って拒否される(Target target) throws Exception {
        archiveFirstCreatorWaits(Creator.INVITE, target);
    }

    @ParameterizedTest(name = "[{index}] {0} のアーカイブ")
    @EnumSource(Target.class)
    @DisplayName("AC-E08 招待とアーカイブを並行に何度実行しても PENDING が残らず、デッドロックも起きない")
    void E08_並行に実行してもPENDINGが残らずデッドロックしない(Target target) throws Exception {
        race(Creator.INVITE, target);
    }

    // =====================================================================
    // 決定的な順序（行ロックを保持して固定する）
    // =====================================================================

    /** 作成側がロックを取って PENDING を INSERT した状態でコミットを保留し、その間にアーカイブを走らせる。 */
    private void creatorFirstArchiveWaits(Creator creator, Target target) throws Exception {
        Fixture fx = fixture();
        String direction = creator == Creator.APPLY ? "TEAM_APPLY" : "ORG_INVITE";
        long[] membershipId = new long[1];
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            HeldTx held = new HeldTx(executor, () -> {
                // 作成側の約束: チーム行 → 組織行の順に FOR UPDATE を取り、INSERT とコミットまで保持する（§6.9）
                em.createNativeQuery("SELECT id FROM teams WHERE id = :id FOR UPDATE")
                        .setParameter("id", fx.team().id()).getResultList();
                em.createNativeQuery("SELECT id FROM organizations WHERE id = :id FOR UPDATE")
                        .setParameter("id", fx.org().id()).getResultList();
                membershipId[0] = insertMembershipRow(fx.team().id(), fx.org().id(), "PENDING", direction, null,
                        LocalDateTime.now());
            });

            Future<Result> archive = executor.submit(() -> archive(target, fx));
            Thread.sleep(BLOCK_CHECK_MILLIS);
            assertThat(archive.isDone())
                    .as("%s のアーカイブは、作成側がロックを持っている間は完了しない", target).isFalse();

            held.commit();
            assertThat(archive.get(60, TimeUnit.SECONDS).status()).as("アーカイブは成功する").isEqualTo(200);

            awaitCondition("いま INSERT された PENDING も消える", () -> statusOf(membershipId[0]) == null);
            assertThat(pendingCount(fx.team().id(), fx.org().id())).isZero();
            awaitCondition("取消の通知ジョブが作られる", () -> jobIdsOf(membershipId[0], CANCELLED).size() == 1);
        } finally {
            executor.shutdownNow();
        }
    }

    /** アーカイブ側がロックを取った状態でコミットを保留し、その間に作成を走らせる。 */
    private void archiveFirstCreatorWaits(Creator creator, Target target) throws Exception {
        Fixture fx = fixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            HeldTx held = new HeldTx(executor, () -> {
                if (target == Target.TEAM) {
                    teamService.archiveTeam(fx.team().id());
                } else {
                    organizationService.archiveOrganization(fx.org().id());
                }
            });

            Future<Result> create = executor.submit(() -> create(creator, fx));
            Thread.sleep(BLOCK_CHECK_MILLIS);
            assertThat(create.isDone())
                    .as("%s の作成は、アーカイブ側がロックを持っている間は完了しない（待たずに PENDING を作らない）",
                            creator).isFalse();

            held.commit();
            Result result = create.get(60, TimeUnit.SECONDS);
            assertRejectedByBusinessRule(result);
            assertThat(countMemberships(fx.team().id(), fx.org().id())).as("PENDING は作られない").isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    // =====================================================================
    // 競走（自由に並行させる）
    // =====================================================================

    private void race(Creator creator, Target target) throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            Fixture fx = fixture();
            List<Result> results = runConcurrently(List.<Callable<Result>>of(
                    () -> create(creator, fx),
                    () -> archive(target, fx)));
            Result created = results.get(0);
            Result archived = results.get(1);

            assertThat(archived.status()).as("round %d: アーカイブは成功する（デッドロックの 500 にならない）", round)
                    .isEqualTo(200);
            assertThat(created.status()).as("round %d: 作成側は 201 か業務上の 4xx（5xx＝デッドロック等ではない）: %s",
                    round, created).isLessThan(500);
            if (created.status() != 201) {
                assertRejectedByBusinessRule(created);
            }
            awaitCondition("round " + round + ": アーカイブ済みの相手への PENDING が残らない",
                    () -> pendingCount(fx.team().id(), fx.org().id()) == 0);
        }
    }

    // =====================================================================
    // ヘルパー
    // =====================================================================

    private Result create(Creator creator, Fixture fx) throws Exception {
        return creator == Creator.APPLY
                ? apply(fx.ta(), fx.team().slug(), fx.org().slug())
                : invite(fx.xa(), fx.org().slug(), fx.team().slug());
    }

    private Result archive(Target target, Fixture fx) throws Exception {
        return target == Target.TEAM
                ? archiveTeamApi(fx.ta(), fx.team().slug())
                : archiveOrgApi(fx.xa(), fx.org().slug());
    }

    /** 業務ルールによる拒否（ルーティング抜け・認可の取り違えによる偽の緑を避けるため、エラーコードまで見る）。 */
    private void assertRejectedByBusinessRule(Result result) {
        assertThat(result.status()).isBetween(400, 499);
        assertThat(result.errorCode()).as("業務のエラーコード（TEAM_*・ORG_*）で拒否される: %s", result)
                .matches("(TEAM|ORG)_\\d+");
    }
}
