package com.mannschaft.app.common.duplicatename;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260901-1538 柱③-A 検分第2巡是正: 「トランザクションが commit するまでアドバイザリロックが
 * 解放されない」ことを実 DB（Testcontainers MySQL）で検証する。
 *
 * <p>検分第1巡では「候補再計算 → 作成」の一体化のみを検証していたが、第2巡で
 * 「{@code RELEASE_LOCK} が同一接続上の {@code finally} から実行されるため、
 * {@code @Transactional} の commit（サービスメソッド終了後）より前にロックが解放されてしまう」
 * という TOCTOU 残存が指摘された。{@link DuplicateNameGuardServiceImpl} は
 * 専用 JDBC 接続方式（{@code GET_LOCK} を専用接続で取得し、トランザクション完了後
 * （{@code afterCompletion}）まで解放を遅延させる設計）で是正済み。</p>
 *
 * <p>本 IT は {@link DuplicateNameGuardTransactionalTestHelper}（実 {@code @Transactional}
 * 境界を持つテスト専用 Bean）を使い、T1 の {@code createAction} を
 * {@link CountDownLatch} で意図的に保留した状態（＝T1 のトランザクションはまだ commit していない）
 * を作り、その間に T2 が同じ正規化名でロック取得を試みると<b>待機の末にタイムアウトし
 * DUPNAME_002（409）になる</b>ことを検証する。これが確認できれば、ロックの実際の解放が
 * T1 のトランザクション完了より前に起きていないことの直接証拠になる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱③-A 同名確認フロー 並行作成(ロックはTX完了まで解放されない)統合テスト")
class DuplicateNameConcurrentCreationRedIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private DuplicateNameGuardTransactionalTestHelper transactionalHelper;

    @Autowired
    private DuplicateNameGuardService duplicateNameGuardService;

    @Test
    @DisplayName("T1のTXがcommitする前にT2が同名でロック取得を試みると、待機の末にDUPNAME_002になる"
            + "（＝ロック解放がTX完了より前に起きていないことの直接証拠）")
    void lockIsNotReleasedBeforeTransactionCommits() throws Exception {
        String name = "並行作成IT組織" + System.nanoTime();
        CountDownLatch t1EnteredCreateAction = new CountDownLatch(1);
        CountDownLatch t1MayFinishCreateAction = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // T1: 実トランザクション内でロックを取得し、createAction 内で意図的に保留する
            // （＝この間、T1 のトランザクションはまだ commit していない）。
            Future<String> future1 = executor.submit(() -> transactionalHelper.createWithPause(
                    DuplicateNameScopeKind.ORGANIZATION, name, 9001L, List::of,
                    t1EnteredCreateAction, t1MayFinishCreateAction));

            // T1 が createAction に入る（＝GET_LOCK に成功し、候補ゼロで作成処理中）まで待つ。
            assertThat(t1EnteredCreateAction.await(10, TimeUnit.SECONDS))
                    .as("T1 が createAction に到達しなかった（ロック取得自体に失敗した疑い）")
                    .isTrue();

            // この時点で T1 は createAction 内で保留中＝トランザクションは commit していない。
            // 是正前の実装（同一接続の finally で即座に RELEASE_LOCK）であれば、この時点で
            // 既にロックが解放されており、T2 は即座に GET_LOCK に成功してしまう。
            // 是正後は専用接続方式によりロックが T1 の TX 完了まで保持されているため、
            // T2 は GET_LOCK のタイムアウト（5秒）を待たされたのち DUPNAME_002 を受け取る。
            Future<Boolean> future2 = executor.submit(() -> {
                try {
                    duplicateNameGuardService.checkForCreateAndRun(
                            DuplicateNameScopeKind.ORGANIZATION, name, 9002L,
                            false, null, List::of, () -> "should-not-be-created");
                    return false; // ロックが取れて作成できてしまった＝直列化が効いていない
                } catch (BusinessException e) {
                    return "DUPNAME_002".equals(e.getErrorCode().getCode());
                }
            });

            // GET_LOCK のタイムアウト（5秒）分の待ちを見込む。
            Boolean t2GotLockTimeout = future2.get(15, TimeUnit.SECONDS);
            assertThat(t2GotLockTimeout)
                    .as("T2 は T1 のTX完了前にロックを取得できてはならず、DUPNAME_002 を受けるはず")
                    .isTrue();

            // T1 を解放して createAction を完了させ、トランザクションを commit させる。
            t1MayFinishCreateAction.countDown();
            String result1 = future1.get(10, TimeUnit.SECONDS);
            assertThat(result1).isEqualTo("created");

            // T1 の commit（afterCompletion）後は、同名でロックを再取得できる。
            String result3 = duplicateNameGuardService.checkForCreateAndRun(
                    DuplicateNameScopeKind.ORGANIZATION, name, 9003L,
                    false, null, List::of, () -> "created-after-t1-commit");
            assertThat(result3).isEqualTo("created-after-t1-commit");
        } finally {
            executor.shutdownNow();
        }
    }
}
