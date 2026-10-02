package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * キャンセル待ち（waitlist）エントリのレスポンス（F03.4.5 §6.1）。
 *
 * <p>本人の待ち一覧・登録直後の応答に用いる。枠情報（日付・時間帯・タイトル）を同梱するが、
 * 他人の userId・氏名・人数は含めない（§7 PII）。</p>
 */
@Getter
@Builder
public class WaitlistEntryResponse {

    /** エントリID（UUIDv7）。 */
    private final UUID id;

    /** チームID。 */
    private final Long teamId;

    /** 対象枠ID。 */
    private final Long slotId;

    /** 状態（WAITING/CANCELLED/CONVERTED）。 */
    private final String status;

    /** 枠の日付（枠情報同梱）。 */
    private final LocalDate slotDate;

    /** 枠の開始時刻。 */
    private final LocalTime startTime;

    /** 枠の終了時刻。 */
    private final LocalTime endTime;

    /** 枠のタイトル（NULL 可）。 */
    private final String slotTitle;

    /** 登録日時。 */
    private final LocalDateTime createdAt;
}
