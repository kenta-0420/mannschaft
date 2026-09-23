package com.mannschaft.app.shift.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * シフト希望レスポンスDTO。
 *
 * <p>{@code @RequiredArgsConstructor} を残しているのは既存テスト
 * （{@code new ShiftRequestResponse(...)}）との後方互換のため。{@code @Builder(toBuilder = true)}
 * は {@code listMyRequests} が {@code scheduleDeleted} だけを上書きするために追加した
 *（案C / CMP-260917-1136）。</p>
 */
@Builder(toBuilder = true)
@RequiredArgsConstructor
@Getter
public class ShiftRequestResponse {

    private final Long id;
    private final Long scheduleId;
    private final Long userId;
    private final Long slotId;
    private final LocalDate slotDate;
    private final String preference;
    private final String note;
    private final LocalDateTime submittedAt;

    /**
     * 親スケジュールが論理削除済みか（案C / CMP-260917-1136）。
     *
     * <p>{@code GET /shifts/my/requests} は提出履歴として残すため、親スケジュールが
     * 論理削除されても一覧からは消さない（提出履歴は利用者の記録であるため）。
     * ただし詳細（{@code GET /shifts/schedules/{id}} 等）は 404 になるため、
     * 一覧側にこのフラグを立てて FE が「削除済み」を示し詳細へのリンクを塞げるようにする。
     * 既定は {@code false}（{@link com.mannschaft.app.shift.ShiftMapper#toRequestResponse}
     * では判定しないため常に false で生成され、{@code listMyRequests} が toBuilder で上書きする）。</p>
     */
    @Builder.Default
    private final boolean scheduleDeleted = false;
}
