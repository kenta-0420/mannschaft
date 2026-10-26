package com.mannschaft.app.jobmatching.controller.dto;

import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import com.mannschaft.app.jobmatching.enums.JobContractStatus;

/**
 * QR チェックイン／アウト記録レスポンス。F13.1 Phase 13.1.2。
 *
 * <p>{@code POST /api/v1/jobs/check-ins} の正常系で返す。</p>
 *
 * @param checkInId           生成された {@code job_check_ins.id}
 * @param contractId          対象契約 ID
 * @param type                IN / OUT
 * @param newStatus           遷移後の契約ステータス（IN は IN_PROGRESS、OUT は CHECKED_OUT）
 * @param workDurationMinutes 業務時間（分、OUT 時のみ非 null）
 * @param geoAnomaly          Geolocation 乖離フラグ（閾値超なら true）
 */
public record CheckInResponse(
        Long checkInId,
        Long contractId,
        JobCheckInType type,
        JobContractStatus newStatus,
        Integer workDurationMinutes,
        boolean geoAnomaly
) {
}
