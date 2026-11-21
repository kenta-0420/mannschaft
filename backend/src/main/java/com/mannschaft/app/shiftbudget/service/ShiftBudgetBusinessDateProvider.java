package com.mannschaft.app.shiftbudget.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * シフト予算の「今日」（業務日）を返す（CMP-260909-2152 確定設計 r12 §2）。
 *
 * <p>未終了期間の締め拒否（SHIFT_BUDGET_036）、締め日変更の受理条件（今日を含む期間キー）、
 * {@code min_effective_period_key} の算出は、必ずこの Bean から今日を得ること（試練が spy で日付を固定する）。
 * 日付は業務壁時計（{@code wallClock}。Asia/Tokyo）から得る。</p>
 */
@Component
public class ShiftBudgetBusinessDateProvider {

    private final Clock wallClock;

    public ShiftBudgetBusinessDateProvider(@Qualifier("wallClock") Clock wallClock) {
        this.wallClock = wallClock;
    }

    /** 業務壁時計の今日。 */
    public LocalDate today() {
        return LocalDate.now(wallClock);
    }
}
