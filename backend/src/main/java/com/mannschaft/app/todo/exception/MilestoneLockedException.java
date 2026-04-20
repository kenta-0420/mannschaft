package com.mannschaft.app.todo.exception;

/**
 * ロック中マイルストーンの TODO 操作が試みられた際の例外。
 *
 * <p>HTTP 423 Locked にマッピングされる（GlobalExceptionHandler 対応は Phase 15-3）。
 * F02.7 TODO マイルストーンゲートの仕様に基づき、ロック中 TODO の
 * ステータス変更・更新・担当者変更等を一律拒否する際に送出する。</p>
 */
public class MilestoneLockedException extends RuntimeException {

    private final Long milestoneId;
    private final String lockedByMilestoneTitle;

    public MilestoneLockedException(Long milestoneId, String lockedByMilestoneTitle) {
        super(String.format(
                "マイルストーン %d はロック中のため操作できません。解除条件: 前マイルストーン『%s』を完了",
                milestoneId, lockedByMilestoneTitle));
        this.milestoneId = milestoneId;
        this.lockedByMilestoneTitle = lockedByMilestoneTitle;
    }

    public Long getMilestoneId() {
        return milestoneId;
    }

    public String getLockedByMilestoneTitle() {
        return lockedByMilestoneTitle;
    }
}
