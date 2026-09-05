package com.mannschaft.app.common.duplicatename;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * CMP-260901-1538 柱③-A 検分第2巡是正専用のテストヘルパー。
 *
 * <p>{@link DuplicateNameConcurrentCreationRedIT} が「トランザクションが commit するまで
 * アドバイザリロックが解放されない」ことを実 DB で検証できるよう、実際の
 * {@code @Transactional} 境界内で {@link DuplicateNameGuardService#checkForCreateAndRun} を呼び、
 * {@code createAction} の実行を {@link CountDownLatch} で任意の時間だけ保留できるようにする。
 * プロダクションコードには一切手を入れず、テスト専用の Spring Bean として同一パッケージに置く
 * （component scan で自動登録される）。
 *
 * <p>本クラスのみテストソースセットのため Lombok を使わず明示的にコンストラクタを書く
 * （テストコンパイル時に Lombok annotation processor が構成されていないため）。</p>
 */
@Service
public class DuplicateNameGuardTransactionalTestHelper {

    private final DuplicateNameGuardService duplicateNameGuardService;

    public DuplicateNameGuardTransactionalTestHelper(DuplicateNameGuardService duplicateNameGuardService) {
        this.duplicateNameGuardService = duplicateNameGuardService;
    }

    /**
     * 実トランザクション内で {@code checkForCreateAndRun} を呼ぶ。{@code createAction} は
     * {@code enteredCreateActionSignal} を countDown してから {@code releaseCreateActionLatch}
     * が countDown されるまで待機する（＝呼び出し元がこの間に「TX はまだ commit していない」
     * ことを利用した検証を行える）。
     */
    @Transactional
    public String createWithPause(DuplicateNameScopeKind scopeKind, String rawName, Long actorUserId,
            Supplier<List<DuplicateNameCandidate>> candidateSupplier,
            CountDownLatch enteredCreateActionSignal, CountDownLatch releaseCreateActionLatch) {
        return duplicateNameGuardService.checkForCreateAndRun(
                scopeKind, rawName, actorUserId, false, null, candidateSupplier,
                () -> {
                    enteredCreateActionSignal.countDown();
                    try {
                        boolean released = releaseCreateActionLatch.await(10, TimeUnit.SECONDS);
                        if (!released) {
                            throw new IllegalStateException(
                                    "releaseCreateActionLatch が10秒以内に解放されなかった");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "created";
                });
    }
}
