package com.mannschaft.app.social.announcement.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 組織お知らせの一括既読の結果（ALIC-1 §2.4）。<b>件数だけ</b>を返し、どの ID が無効だったか（存在しない・宛先外・
 * 見えない・課金 HIDDEN）は一切返さない（存在オラクルを作らない）。
 */
@Schema(description = "組織お知らせの一括既読の結果")
public record AnnouncementReadBatchResultDto(
        @Schema(description = "判定を通った ID のうち、このリクエストで新たに既読になった件数（既読済みは数えない）",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
        int markedCount) {
}
