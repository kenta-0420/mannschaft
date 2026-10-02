package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約通知メール宛先の作成リクエストDTO（機能D・POST）。
 *
 * <p><b>Jackson 手本</b>: {@code @Getter @RequiredArgsConstructor} ＋ 全 final ＋ 単一コンストラクタ
 * （手本 {@code CreateReservationRequest} / {@code BlockedTimeRequest}）。全 final ＋ 複数コンストラクタは
 * Jackson no Creators で実 POST 500 になる既知の再発地雷のため避ける。</p>
 *
 * <p>{@code isEnabled} の既定 true は final DTO では表現できないため
 * Service 層で {@code null → true} 正規化する（設計 §4.D）。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreateNotificationRecipientRequest {

    /** 通知先メールアドレス（形式不正は {@code @Email} の 400）。 */
    @NotBlank
    @Email
    @Size(max = 255)
    private final String email;

    /** 宛先ラベル（任意・最大100文字）。 */
    @Size(max = 100)
    private final String label;

    /** 有効フラグ（任意・既定は Service 層で true 正規化）。 */
    private final Boolean isEnabled;
}
