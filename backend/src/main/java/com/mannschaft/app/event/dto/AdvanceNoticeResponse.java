package com.mannschaft.app.event.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 事前通知（遅刻・欠席）レスポンスDTO。F03.12 §15 事前遅刻・欠席連絡。
 *
 * <p>1件の事前通知（遅刻連絡または欠席連絡）の内容を返す。</p>
 */
@Getter
@RequiredArgsConstructor
public class AdvanceNoticeResponse {

    /** ユーザーID。 */
    private final Long userId;

    /** 表示名。 */
    private final String displayName;

    /**
     * 通知種別。LATE（遅刻）または ABSENCE（欠席）のいずれか。
     */
    private final String noticeType;

    /**
     * 遅刻予定分数。noticeType が LATE の場合のみ設定される。
     * ABSENCE の場合は null。
     */
    private final Integer expectedArrivalMinutesLate;

    /**
     * 欠席理由。noticeType が ABSENCE の場合のみ設定される。
     * LATE の場合は null。
     * 値: SICK / PERSONAL_REASON / OTHER
     */
    private final String absenceReason;

    /** コメント（任意）。 */
    private final String comment;

    /** レコード作成日時（＝申告日時）。 */
    private final LocalDateTime createdAt;
}
