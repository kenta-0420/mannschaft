package com.mannschaft.app.shift.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * シフト希望提出サマリーレスポンス DTO。
 *
 * <p>F03.5 v2 で 5 段階希望に対応するため、preference 別カウンタを追加した。
 * 後方互換性のため既存 v1 クライアント向けに {@code unavailableCount} フィールドも保持する
 * （{@link #strongRestCount} + {@link #absoluteRestCount} の合計値）。</p>
 */
@Getter
@RequiredArgsConstructor
public class ShiftRequestSummaryResponse {

    private final Long scheduleId;
    private final long totalMembers;

    /** 重複を除いたシフト希望提出済みメンバー数。 */
    private final long submittedCount;

    /** 未提出メンバー数。 */
    private final long pendingCount;

    // ==========================================
    // v2 新規: 5 段階 preference 別カウンタ
    // （提出された希望レコード単位の集計であり、メンバー単位ではない）
    // ==========================================

    /** PREFERRED 希望の件数。 */
    private final long preferredCount;

    /** AVAILABLE 希望の件数。 */
    private final long availableCount;

    /** WEAK_REST 希望の件数。 */
    private final long weakRestCount;

    /** STRONG_REST 希望の件数（旧 UNAVAILABLE 相当）。 */
    private final long strongRestCount;

    /** ABSOLUTE_REST 希望の件数。 */
    private final long absoluteRestCount;

    /**
     * v1 互換フィールド: STRONG_REST + ABSOLUTE_REST の合計。
     *
     * <p>旧 v1 API（3 段階）のクライアントは UNAVAILABLE カテゴリを参照している可能性があるため、
     * 「休み系」(STRONG_REST + ABSOLUTE_REST) を合算した値を返して互換性を維持する。</p>
     */
    public long getUnavailableCount() {
        return strongRestCount + absoluteRestCount;
    }
}
