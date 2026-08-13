package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * F03.11.1 募集キャンセル料の記録一覧レスポンス（設計書 §10・免除 UI のための一覧）。
 *
 * <p>受取先側の管理者・本人が「自分が受け取るべきキャンセル料」を確認し、免除の対象を
 * 選ぶための最小限の情報のみを返す。対象者の識別に {@code userId} を含むが、
 * メールアドレス等の追加の個人情報は含めない。</p>
 */
@Getter
@AllArgsConstructor
public class RecruitmentCancellationRecordSummaryResponse {

    private final Long id;
    private final Long listingId;
    private final String listingTitle;
    private final Long participantId;
    private final Long userId;
    private final Integer feeAmount;
    private final String paymentStatus;
    private final LocalDateTime cancelledAt;
    private final Integer hoursBeforeStart;
}
