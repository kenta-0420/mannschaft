package com.mannschaft.app.reservation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約公開設定の更新リクエストDTO。
 *
 * <p>一般公開予約（{@code allow_public_reservation}）の許可/不許可を切り替える。
 * ADMIN 限定操作。</p>
 */
@Getter
@RequiredArgsConstructor
public class UpdateReservationSettingRequest {

    /** 一般公開予約を許可するか（true=ログイン済みなら誰でも予約可 / false=チーム所属者のみ）。 */
    @NotNull
    @Schema(description = "一般公開予約を許可するか（true=ログイン済みなら誰でも予約可 / false=チーム所属者のみ）", example = "false")
    private final Boolean allowPublicReservation;
}
