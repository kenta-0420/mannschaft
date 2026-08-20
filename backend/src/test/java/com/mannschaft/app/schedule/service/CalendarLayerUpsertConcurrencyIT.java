package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.dto.CalendarLayerUpdateRequest;
import com.mannschaft.app.schedule.entity.UserCalendarLayerSettingEntity;
import com.mannschaft.app.schedule.repository.UserCalendarLayerSettingRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * F03.19 — レイヤー設定 PATCH の upsert 原子性を<b>実 MySQL・実 2 トランザクション</b>で裏取りする統合テスト。
 *
 * <h2>なぜモックの単体テストでは足りなかったか</h2>
 * <p>一次修正（{@code INSERT IGNORE} ＋ 0 件なら通常 SELECT で取り直し）に付けられていた単体テストは
 * モックが常に最新値を返す作りで、<b>スナップショット分離を再現していなかった</b>ため緑になっていた。
 * だが本番 MySQL の分離レベルは {@code REPEATABLE-READ}（実 DB の
 * {@code @@global.transaction_isolation} / {@code @@session.transaction_isolation} で確認済み）であり、
 * 通常の SELECT はトランザクション冒頭のスナップショットを見続ける。
 * 先着がコミットした行は取り直しても見えず、{@code IllegalStateException}（＝ 500）に落ちていた。</p>
 *
 * <h2>本テストの再現手順</h2>
 * <ol>
 *   <li>後着トランザクション T2 を開き、<b>行が無い時点で SELECT を 1 本流してスナップショットを張る</b></li>
 *   <li>別スレッドの独立トランザクション T1 が同じキーの行を作って<b>コミット</b>する（先着）</li>
 *   <li>T2 のまま {@code updateLayer} を呼ぶ — T2 の通常 SELECT には T1 の行は<b>見えない</b>ので
 *       {@code INSERT IGNORE} が 0 件を返し、取り直しの経路に入る</li>
 * </ol>
 * <p>ロック付き読み取り（{@code SELECT ... FOR UPDATE}＝現在読み取り）で取り直す実装だけがここを通過する。</p>
 *
 * <p>スコープは {@code PERSONAL}（{@code scope_id = 0}）を使う。所属検証を持たないため
 * 実 DB 側に team/organization のシードを用意せずに PATCH の並行性だけを純粋に測れる。</p>
 *
 * <p><b>Docker 未起動環境では丸ごと skip される</b>（{@code @EnabledIf}）。
 * skip されたときは「並行性が検証できていない」のであって「緑」ではない。</p>
 */
@DisplayName("F03.19 レイヤー設定 PATCH の upsert 原子性（実 MySQL・REPEATABLE READ・実 2 トランザクション）")
// JUnit 5 の @EnabledIf は @Inherited ではないため、派生クラスでも明示的に再宣言する必要がある
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class CalendarLayerUpsertConcurrencyIT extends AbstractMySqlIntegrationTest {

    private static final Long ME = 990_319_001L;
    private static final String PERSONAL = "PERSONAL";
    private static final Long PERSONAL_SCOPE_ID = 0L;

    @Autowired
    private CalendarLayerService service;

    @Autowired
    private UserCalendarLayerSettingRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private EntityManager entityManager;

    private TransactionTemplate newTx() {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tt;
    }

    @BeforeEach
    void cleanUp() {
        newTx().executeWithoutResult(status -> repository.deleteByUserId(ME));
    }

    @AfterEach
    void tearDown() {
        newTx().executeWithoutResult(status -> repository.deleteByUserId(ME));
    }

    @Test
    @DisplayName("分離レベルが REPEATABLE-READ であること（本テストの前提の実測）")
    void isolationLevel_isRepeatableRead() {
        String isolation = newTx().execute(status -> (String) entityManager
                .createNativeQuery("SELECT @@session.transaction_isolation")
                .getSingleResult());

        // 前提が崩れると本テストは「並行を測っていない」ことになるので、前提そのものを固定する。
        assertThat(isolation).isEqualTo("REPEATABLE-READ");
    }

    @Test
    @DisplayName("〔陽性〕スナップショットを張った後に先着がコミットしても 500 にならず冪等に確定する")
    void concurrentPatch_afterSnapshotTaken_doesNotFail() {
        assertThatCode(() -> newTx().executeWithoutResult(status -> {
            // 1) 行が無い時点で SELECT を流し、この T2 のスナップショットを確定させる。
            Optional<UserCalendarLayerSettingEntity> before =
                    repository.findByUserIdAndScopeTypeAndScopeId(ME, PERSONAL, PERSONAL_SCOPE_ID);
            assertThat(before).isEmpty();

            // 2) 別スレッドの独立トランザクション T1（先着）が同じ行を作ってコミットする。
            rivalCommitsRowInSeparateTransaction();

            // 3) T2 のまま PATCH。T2 の通常 SELECT には T1 の行は見えない（REPEATABLE READ）。
            //    ここで通常 SELECT で取り直す実装は IllegalStateException（＝500）に落ちる。
            service.updateLayer(ME, PERSONAL, PERSONAL_SCOPE_ID,
                    new CalendarLayerUpdateRequest("#dc2626", null));
        })).doesNotThrowAnyException();

        // 冪等: 行は 1 本だけ・自分の指定色が載り、送っていない hidden は先着の値を壊さない。
        newTx().executeWithoutResult(status -> {
            var rows = repository.findByUserId(ME);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getColor()).isEqualTo("#DC2626");
            assertThat(rows.get(0).getHidden()).isTrue();
        });
    }

    /**
     * 先着トランザクション T1 を<b>別スレッド</b>で走らせてコミットする。
     *
     * <p>同一スレッドで {@code REQUIRES_NEW} しても InnoDB のコネクションは別になるが、
     * 「別リクエスト」であることを明示するため別スレッドで走らせる。</p>
     */
    private void rivalCommitsRowInSeparateTransaction() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> newTx().executeWithoutResult(status ->
                    service.updateLayer(ME, PERSONAL, PERSONAL_SCOPE_ID,
                            new CalendarLayerUpdateRequest("#059669", true))))
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("先着トランザクションの実行に失敗した", e);
        } finally {
            pool.shutdownNow();
        }
    }
}
