package com.mannschaft.app.reservation.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 臨時休業一括通知リクエスト。
 *
 * <p>部分時間帯休業:
 * <ul>
 *   <li>{@code startTime} / {@code endTime} を両方省略 → 終日休業</li>
 *   <li>両方指定 → 指定時間帯のみの休業（例: 09:00〜11:00 を 4/8〜4/10 の各日に適用）</li>
 *   <li>片方だけ指定はバリデーションエラー</li>
 *   <li>時刻は HH:00 のみ受け付ける（時間単位運用）</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
public class CreateEmergencyClosureRequest {

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    /** 部分時間帯休業の開始時刻（HH:00 のみ）。終日休業の場合は null */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    /** 部分時間帯休業の終了時刻（HH:00 のみ）。終日休業の場合は null */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;

    /** 休業理由（先生体調不良 など） */
    @NotBlank
    @Size(max = 200)
    private String reason;

    /** メール件名 */
    @NotBlank
    @Size(max = 200)
    private String subject;

    /** メール本文 */
    @NotBlank
    private String messageBody;

    /** 対象予約を自動キャンセルするか */
    private boolean cancelReservations;
}
