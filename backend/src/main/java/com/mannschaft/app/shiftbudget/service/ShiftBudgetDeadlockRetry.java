package com.mannschaft.app.shiftbudget.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;

import java.util.function.Supplier;

/**
 * デッドロックの犠牲・ロック取得失敗（{@link ConcurrencyFailureException}）を TX の外で再実行する（CMP-260909-2152）。
 *
 * <p>締めの各段・期間行の確定・締め日変更は、先頭行や割当行のロックを取る独立 TX で、並行実行（手動とバッチ、締め日変更と締めなど）が
 * InnoDB のデッドロック検知で犠牲になりうる。犠牲 TX は全体がロールバックされているため、<b>呼び出し側の TX の外</b>（段取り役・Facade）で
 * 1回再実行すれば、他方の確定後の状態を読んで冪等に収束する（金額・件数が一致する）。再実行は最大 {@value #MAX_ATTEMPTS} 回。</p>
 */
@Slf4j
final class ShiftBudgetDeadlockRetry {

    /** 最大試行回数（初回を含む）。 */
    static final int MAX_ATTEMPTS = 3;

    private ShiftBudgetDeadlockRetry() {
    }

    /** {@code action} を実行し、並行失敗なら最大 {@value #MAX_ATTEMPTS} 回まで再実行する。 */
    static <T> T run(String what, Supplier<T> action) {
        ConcurrencyFailureException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (ConcurrencyFailureException e) {
                last = e;
                log.warn("F08.7 期間締め: 並行失敗（デッドロック犠牲・ロック取得失敗）のため再実行: what={}, attempt={}/{}",
                        what, attempt, MAX_ATTEMPTS);
            }
        }
        throw last;
    }

    /** 戻り値なし版。 */
    static void runVoid(String what, Runnable action) {
        run(what, () -> {
            action.run();
            return null;
        });
    }
}
