package com.mannschaft.app.reservation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 予約通知メール宛先の一覧＋フリーミアム状態レスポンス DTO（機能D・GET）。
 *
 * <p>{@code freeLimit}/{@code maxLimit}/{@code hasPaidPlan}/件数は FE のロック表示
 * （3件超4件目の有料ロック・10件で追加不可）を駆動するために含める。
 * <b>FE 表示はあくまで補助で、実ゲートは BE（POST）で強制</b>する。</p>
 */
@Getter
@Builder
@Schema(description = "予約通知メール宛先の一覧＋フリーミアム状態")
public class NotificationRecipientListResponse {

    @Schema(description = "宛先一覧（有効・無効を含む全登録行）")
    private final List<NotificationRecipientResponse> recipients;

    @Schema(description = "有効宛先数（is_enabled=true の件数）", example = "1")
    private final int enabledCount;

    @Schema(description = "登録宛先の総数（有効・無効を問わない・件数ゲートの分母）", example = "1")
    private final int totalCount;

    @Schema(description = "無料プランで登録できる上限件数", example = "3")
    private final int freeLimit;

    @Schema(description = "有料プランでの最大登録件数", example = "10")
    private final int maxLimit;

    @Schema(description = "有料プラン加入中か", example = "false")
    private final boolean hasPaidPlan;
}
