package com.mannschaft.app.event.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 点呼候補者レスポンスDTO。F03.12 §14 主催者点呼機能。
 *
 * <p>RSVP で ATTENDING / MAYBE と回答した参加予定者1名分のスナップショットを返す。
 * 主催者は本 DTO の情報をもとに点呼画面を構築し、到着/遅刻/欠席を記録する。</p>
 */
@Getter
@Builder
public class RollCallCandidateResponse {

    /** ユーザーID。 */
    private final Long userId;

    /** 表示名。 */
    private final String displayName;

    /** アバター URL（任意）。 */
    private final String avatarUrl;

    /**
     * RSVP 回答状態。ATTENDING / MAYBE / NOT_ATTENDING / NO_RESPONSE のいずれか。
     * 候補者一覧は ATTENDING/MAYBE に絞っているが、表示用に保持する。
     */
    private final String rsvpStatus;

    /** 既にチェックイン済みかどうか。true の場合は点呼済み。 */
    private final boolean isAlreadyCheckedIn;

    /** ケア対象者フラグ。true の場合、PRESENT 記録時に保護者通知を送信する。 */
    private final boolean isUnderCare;

    /** 登録済み見守り者数。isUnderCare=true かつ 0 の場合は保護者未設定警告対象。 */
    private final int watcherCount;
}
