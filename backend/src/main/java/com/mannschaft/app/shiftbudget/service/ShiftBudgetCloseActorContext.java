package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shiftbudget.ShiftBudgetCloseActorKind;

/**
 * 締めを起動した主体（手動／バッチ）を、段取り役から各段（Prepare）へ受け渡すスレッド単位の文脈（CMP-260909-2152）。
 *
 * <p>段の公開メソッドの形（{@code prepare(allocationId, periodKey)}）を変えずに actor_kind を残すための受け渡し。
 * 段取り役が設定・解除する。未設定のときは SecurityContext の有無で判定する（利用者あり＝MANUAL、なし＝BATCH）。</p>
 */
public final class ShiftBudgetCloseActorContext {

    private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

    private ShiftBudgetCloseActorContext() {
    }

    /** 主体。 */
    public record Actor(ShiftBudgetCloseActorKind kind, Long userId) {
    }

    /** 設定する（必ず {@link #clear()} と対で使う）。 */
    public static void set(ShiftBudgetCloseActorKind kind, Long userId) {
        CURRENT.set(new Actor(kind, userId));
    }

    /** 解除する。 */
    public static void clear() {
        CURRENT.remove();
    }

    /** 現在の主体。 */
    public static Actor current() {
        Actor actor = CURRENT.get();
        if (actor != null) {
            return actor;
        }
        Long userId = SecurityUtils.getCurrentUserIdOrNull();
        return userId != null
                ? new Actor(ShiftBudgetCloseActorKind.MANUAL, userId)
                : new Actor(ShiftBudgetCloseActorKind.BATCH, null);
    }
}
