package com.mannschaft.app.todo.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * マイルストーンレスポンスDTO。
 *
 * <p>F02.7 Phase 15-3 にてゲート関連フィールド（progressRate, isLocked, lockedByMilestoneId,
 * lockedByMilestoneTitle, completionMode, lockedTodoCount, forceUnlocked, lockedAt, unlockedAt）を追加。</p>
 */
@Getter
@RequiredArgsConstructor
public class MilestoneResponse {

    private final Long id;
    private final Long projectId;
    private final String title;
    private final LocalDate dueDate;
    private final short sortOrder;
    private final boolean isCompleted;
    private final LocalDateTime completedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    // --- F02.7 ゲート関連 ---

    /** 達成率（%。紐付く TODO の完了率から算出） */
    private final BigDecimal progressRate;

    /** ロック状態フラグ */
    private final boolean isLocked;

    /** ロック原因の前マイルストーン ID（null 許容） */
    private final Long lockedByMilestoneId;

    /** ロック原因の前マイルストーン title（サービス層で解決。null 許容） */
    private final String lockedByMilestoneTitle;

    /** 完了判定モード（"AUTO" / "MANUAL"） */
    private final String completionMode;

    /** ロック中の配下 TODO 数 */
    private final long lockedTodoCount;

    /** 強制アンロック済みフラグ */
    private final boolean forceUnlocked;

    /** ロック開始日時（null 許容） */
    private final LocalDateTime lockedAt;

    /** アンロック日時（null 許容） */
    private final LocalDateTime unlockedAt;
}
