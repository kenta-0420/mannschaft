package com.mannschaft.app.property.dto;

import com.mannschaft.app.property.WorkPackageStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 物件履歴パッケージのステータス変更リクエスト DTO（F09.13 Phase 1-δ）。
 *
 * <p>設計書 §4 {@code PATCH /property-history/{id}/status} に対応。</p>
 */
public record ChangeStatusRequest(
        @NotNull
        WorkPackageStatus status) {
}
