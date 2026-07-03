package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ReservationBlockedResourceType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * ブロック時間（予約不可枠）作成・更新リクエストDTO。
 *
 * <p><b>Jackson 手本</b>: 手本 {@code CreateReservationRequest} / 既存 {@code BlockedTimeRequest} に倣い
 * {@code @Getter @RequiredArgsConstructor} ＋ 全フィールド {@code final} ＋ <b>単一コンストラクタ</b>で構成する。
 * {@code @Builder} 併用・複数コンストラクタは {@code @JsonCreator} 必須の再発地雷になるため採らない
 * （{@code feedback_dto_all_final_multi_constructor_jackson_no_creators}）。</p>
 *
 * <p>機能B: {@code resourceType} / {@code resourceId} を追加。{@code resourceType} は
 * 文字列（{@code TEAM} / {@code STAFF}・将来 {@code LINE}/{@code RESOURCE}）で受け、
 * <b>既定値の適用と正規化は Service 層</b>で行う（final DTO は既定値を表現できず
 * 未指定＝{@code null} になるため）。</p>
 */
@Getter
@RequiredArgsConstructor
public class BlockedTimeRequest {

    @NotNull
    private final LocalDate blockedDate;

    private final LocalTime startTime;

    private final LocalTime endTime;

    @Size(max = 200)
    private final String reason;

    /**
     * 対象軸。{@code TEAM} / {@code STAFF}（将来 {@code LINE}/{@code RESOURCE}）。
     * 未指定（null）は Service 層で {@code TEAM} に正規化する。
     * 不正な文字列は Jackson の enum バインドで 400（{@code HttpMessageNotReadableException}）。
     */
    private final ReservationBlockedResourceType resourceType;

    /**
     * {@code resourceType='STAFF'} のときの対象スタッフ {@code staff_user_id}（必須）。
     * {@code TEAM} のときは Service 層で null に正規化する。
     */
    private final Long resourceId;
}
