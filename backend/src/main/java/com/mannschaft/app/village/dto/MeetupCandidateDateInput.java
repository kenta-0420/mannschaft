package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * F17.1 Phase 3-β — 寄合作成時の候補日 1 件（#2357）。
 *
 * <p>候補日は「日付（必須）＋時刻（任意）」の 2 本立て。
 * {@code time} が {@code null} の場合は終日候補を意味する。</p>
 *
 * <p>従来は {@code List<LocalDate>}（素の日付配列）だったが、
 * 時刻を持てるようにするため object 配列 {@code {date, time?}} へ拡張した。</p>
 */
public record MeetupCandidateDateInput(
        @NotNull LocalDate date,
        LocalTime time) {
}
