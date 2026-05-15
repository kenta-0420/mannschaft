package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageReportTargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 村内通報作成リクエスト（F17.1 Phase 1 B7 §4.11）。
 *
 * <p>通報者ユーザーID は {@code SecurityUtils.getCurrentUserId()} から取得し、
 * 本 DTO には含めない（被報告者非開示・設計書 §6.2 準拠）。</p>
 *
 * @param targetType   通報対象種別（POST / MESSAGE / MEMBERSHIP / VILLAGE）
 * @param targetRefId  対象 ID（型は target_type に依存・文字列で保持・最大 64 文字）
 * @param reasonCode   通報理由コード（spam / harassment / illegal 等・最大 64 文字）
 * @param detail       詳細説明（任意・最大 2000 文字）
 */
public record ReportCreateRequest(
        @NotNull VillageReportTargetType targetType,
        @NotBlank @Size(max = 64) String targetRefId,
        @NotBlank @Size(max = 64) String reasonCode,
        @Size(max = 2000) String detail
) {
}
