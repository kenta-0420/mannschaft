package com.mannschaft.app.reservation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 予約メニュー削除（論理削除）レスポンス DTO（F03.4.1 §4 DELETE・200 OK）。
 */
@Getter
@Builder
@Schema(name = "ReservationMenuDeleteResponse", description = "予約メニュー削除結果")
public class ReservationMenuDeleteResponse {

    /** 削除したメニューID。 */
    private final UUID id;

    /** 論理削除日時。 */
    private final LocalDateTime deletedAt;
}
