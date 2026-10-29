package com.mannschaft.app.social.announcement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 組織お知らせの一括既読（{@code POST /api/v1/organizations/{orgId}/announcements/read-batch}）のリクエスト（ALIC-1 §2.4）。
 *
 * <p>画面に取得済みの一覧の未読項目の ID を最大 50 件まで送る。重複はサーバ側で除く。
 * 1 件でも不正（null・0 以下・非数値）、空、51 件以上なら 400 COMMON_001 で 1 件も書かない。</p>
 */
@Schema(description = "組織お知らせの一括既読リクエスト（表示中の未読項目の ID。1〜50件）")
public record AnnouncementReadBatchRequestDto(
        @Schema(description = "既読にする組織お知らせの ID（1〜50件・重複は除く）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Size(min = 1, max = 50) List<@NotNull @Positive Long> feedIds) {
}
