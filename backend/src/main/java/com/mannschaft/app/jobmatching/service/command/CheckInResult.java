package com.mannschaft.app.jobmatching.service.command;

import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import com.mannschaft.app.jobmatching.enums.JobContractStatus;

/**
 * QR チェックイン／アウト記録の成立結果。F13.1 Phase 13.1.2。
 *
 * <p>{@link com.mannschaft.app.jobmatching.service.JobCheckInService#recordCheckIn} の戻り値として
 * Controller 層に返される。Worker 側の UI ではステータス遷移（MATCHED → IN_PROGRESS 等）を表示し、
 * OUT 時は {@code workDurationMinutes} をもとに業務時間を確認画面に出す。</p>
 *
 * @param checkInId           生成された {@code job_check_ins.id}
 * @param contractId          対象契約 ID
 * @param type                IN / OUT
 * @param newStatus           遷移後の契約ステータス（IN 時は IN_PROGRESS、OUT 時は CHECKED_OUT）
 * @param workDurationMinutes 業務時間（分、OUT 時のみ非 null）
 * @param geoAnomaly          Geolocation 乖離フラグ（500 m 超などで true）
 */
public record CheckInResult(
        Long checkInId,
        Long contractId,
        JobCheckInType type,
        JobContractStatus newStatus,
        Integer workDurationMinutes,
        boolean geoAnomaly
) {
}
