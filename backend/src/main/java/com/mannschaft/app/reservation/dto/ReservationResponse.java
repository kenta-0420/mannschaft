package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 予約レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class ReservationResponse {

    Long id;
    ReservationIdentifierDto identifier;
    SlotSummaryDto slot;
    ReservationStatusDto status;
    CancellationDto cancellation;
    NotesDto notes;
    ReservationAuditDto audit;

    /**
     * 予約グループ要約（F03.4.3 §5.6 #10・additive）。
     * <b>単枠予約（group_id NULL）では null</b> — 既存契約不変。
     * グループ詳細の全量は別 DTO {@code ReservationGroupResponse}（グループ専用 API）が返す。
     */
    GroupSummaryDto group;

    public record ReservationIdentifierDto(Long reservationSlotId, Long lineId, Long teamId, Long userId, String userName) {}

    public record SlotSummaryDto(String lineName, String title, LocalDate slotDate, LocalTime startTime, LocalTime endTime) {}

    public record ReservationStatusDto(String status, LocalDateTime bookedAt, LocalDateTime confirmedAt, LocalDateTime completedAt) {}

    public record CancellationDto(LocalDateTime cancelledAt, String cancelReason, String cancelledBy) {}

    public record NotesDto(String userNote, String adminNote) {}

    public record ReservationAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}

    /**
     * 予約グループ要約（F03.4.3 §5.6 #10）。一覧で「10:00〜11:00（30分×2）」の 1 件表示に必要な最小情報。
     *
     * @param groupId      予約グループID
     * @param groupSize    グループの枠数（兄弟行数）
     * @param groupEndTime グループ末尾枠の終了時刻（slot.endTime は代表行の枠終了のため別途保持する）
     * @param menuName     メニュー名（削除済みメニューも履歴解決・G-14。メニューなしは null）
     */
    public record GroupSummaryDto(UUID groupId, Integer groupSize, LocalTime groupEndTime, String menuName) {}
}
