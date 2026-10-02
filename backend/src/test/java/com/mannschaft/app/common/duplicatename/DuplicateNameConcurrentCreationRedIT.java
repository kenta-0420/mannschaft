package com.mannschaft.app.common.duplicatename;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * CMP-260901-1538 柱③-A 検分第3巡是正: 「トランザクションが commit するまで行ロックが
 * 保持される」ことを実 DB（Testcontainers MySQL）で検証する。
 *
 * <p>第1〜2巡では MySQL 名前付きアドバイザリロック（{@code GET_LOCK}/{@code RELEASE_LOCK}）
 * 方式を試みたが、解放タイミング（rollback 経路での早期解放）と接続管理
 * （Hikari 経由では {@code close()} が物理切断ではなくプール返却になる、専用接続の保持による
 * 接続プール枯渇）に構造的な問題が消えなかったため、{@link DuplicateNameGuardServiceImpl} は
 * {@code duplicate_name_locks} テーブルの行ロック方式（呼び出し元と同一トランザクション内で
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} により X ロックを取得し、明示的な解放処理は
 * 一切書かない＝InnoDB が commit/rollback で自動解放する設計）へ転換済み。</p>
 *
 * <p>本 IT は {@link DuplicateNameGuardTransactionalTestHelper}（実 {@code @Transactional}
 * 境界を持つテスト専用 Bean）を使い、T1 の {@code createAction} を
 * {@link CountDownLatch} で意図的に保留した状態（＝T1 のトランザクションはまだ commit
 * していない）を作る。この間に T2 が同じ正規化名で行ロックを取ろうとすると
 * <b>ブロックされる</b>（＝{@code future2.isDone()} が false のまま）ことを確認したのち、
 * T1 を解放して commit させると、T2 は直ちにアンブロックされ、T1 がコミットした行を
 * 候補として検知して確認要求（409）を受け取ることを検証する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱③-A 同名確認フロー 並行作成(行ロックはTX完了まで保持される)統合テスト")
class DuplicateNameConcurrentCreationRedIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private DuplicateNameGuardTransactionalTestHelper transactionalHelper;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String createdOrganizationName;

    @AfterEach
    void cleanUp() {
        if (createdOrganizationName != null) {
            jdbcTemplate.update("DELETE FROM organizations WHERE name = ?", createdOrganizationName);
        }
    }

    @Test
    @DisplayName("T1のTXがcommitするまでT2は同名の行ロックでブロックされ、"
            + "T1のcommit後にT2がアンブロックされて409(確認要求)を受け取る")
    void t2IsBlockedUntilT1CommitsThenDetectsTheCommittedDuplicate() throws Exception {
        createdOrganizationName = "並行作成IT組織" + System.nanoTime();
        CountDownLatch t1EnteredCreateAction = new CountDownLatch(1);
        CountDownLatch t1MayFinishCreateAction = new CountDownLatch(1);
        CountDownLatch t2NoPause = new CountDownLatch(0); // T2 は候補検知後に例外を投げる想定で
        // createAction まで到達しないため、pause は不要（既に解放済みのラッチを渡す）。

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // T1: 実トランザクション内で行ロックを取得し、createAction 内で意図的に保留する
            // （＝この間、T1 のトランザクションはまだ commit していない＝行ロックはまだ保持中）。
            Future<Long> future1 = executor.submit(() -> transactionalHelper.createWithPause(
                    DuplicateNameScopeKind.ORGANIZATION, createdOrganizationName, 9001L, List::of,
                    t1EnteredCreateAction, t1MayFinishCreateAction,
                    () -> insertOrganizationRow(createdOrganizationName)));

            assertThat(t1EnteredCreateAction.await(10, TimeUnit.SECONDS))
                    .as("T1 が createAction に到達しなかった（行ロック取得自体に失敗した疑い）")
                    .isTrue();

            // T2: 同名で行ロックを取ろうとする。T1 がまだ commit していないため、
            // T2 の INSERT ... ON DUPLICATE KEY UPDATE はブロックされるはず。
            Future<Object> future2 = executor.submit(() -> {
                try {
                    return transactionalHelper.createWithPause(
                            DuplicateNameScopeKind.ORGANIZATION, createdOrganizationName, 9002L,
                            () -> organizationRepository.findActiveByNormalizedNameForUpdate(createdOrganizationName)
                                    .stream()
                                    .map(this::toDuplicateNameCandidate)
                                    .toList(),
                            new CountDownLatch(1), t2NoPause,
                            () -> "should-not-reach-createAction");
                } catch (DuplicateNameConfirmationRequiredException e) {
                    return e;
                }
            });

            // T2 が一定時間ブロックされ続けている（T1 の行ロックを取得できていない）ことを確認する。
            // これが是正の核心: 是正前の GET_LOCK 方式であれば finally で即座に解放されており、
            // T2 はここで既にロックを取得できてしまっていた。Thread.sleep ではなく Awaitility の
            // during() で「その間ずっと isDone()==false のまま」であることを検証する
            // （途中で true になった時点で失敗する。テスト規約: 固定 sleep 禁止）。
            await("T2 は T1 の TX が commit するまで行ロックでブロックされ続けるはず")
                    .pollDelay(Duration.ofMillis(200))
                    .pollInterval(Duration.ofMillis(200))
                    .during(Duration.ofMillis(1300))
                    .atMost(Duration.ofMillis(2000))
                    .until(() -> !future2.isDone());

            // T1 を解放して createAction（実際の組織作成）を完了させ、トランザクションを commit させる。
            t1MayFinishCreateAction.countDown();
            Long createdOrgId = future1.get(10, TimeUnit.SECONDS);
            assertThat(createdOrgId).isNotNull();

            // T1 の commit により行ロックが解放され、T2 がアンブロックされる。
            // T2 は最新のコミット済みデータ（T1 が作った組織）を候補として検知し、
            // 未確認のため確認要求（409）を受け取る。
            Object result2 = future2.get(15, TimeUnit.SECONDS);
            assertThat(result2).isInstanceOf(DuplicateNameConfirmationRequiredException.class);
            DuplicateNameConfirmationDetails details =
                    ((DuplicateNameConfirmationRequiredException) result2).getDetails();
            assertThat(details.visibleCandidates()).hasSize(1);
            assertThat(details.visibleCandidates().get(0).id()).isEqualTo(String.valueOf(createdOrgId));
        } finally {
            executor.shutdownNow();
        }
    }

    private Long insertOrganizationRow(String name) {
        OrganizationEntity org = OrganizationEntity.builder()
                .name(name)
                .slug("dupn-cc-" + (System.nanoTime() % 100_000_000L))
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .build();
        organizationRepository.saveAndFlush(org);
        return org.getId();
    }

    private DuplicateNameCandidate toDuplicateNameCandidate(OrganizationEntity candidate) {
        boolean nameVisible = candidate.getVisibility() == OrganizationEntity.Visibility.PUBLIC;
        return new DuplicateNameCandidate(
                String.valueOf(candidate.getId()), nameVisible, nameVisible ? candidate.getName() : null);
    }
}
