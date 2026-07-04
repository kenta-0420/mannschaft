package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約通知メール宛先の更新リクエストDTO（機能D・PATCH）。
 *
 * <p>{@code label} / {@code isEnabled} の部分更新。null フィールドは据え置き。
 * Jackson 手本は {@link CreateNotificationRecipientRequest} と同じ（全 final ＋ 単一コンストラクタ）。</p>
 *
 * <p>{@code isEnabled=false} にしても件数ゲートのカウント対象からは外れない（登録行として数える）。</p>
 */
@Getter
@RequiredArgsConstructor
public class UpdateNotificationRecipientRequest {

    /** 宛先ラベル（null=据え置き・最大100文字）。 */
    @Size(max = 100)
    private final String label;

    /** 有効フラグ（null=据え置き）。 */
    private final Boolean isEnabled;
}
