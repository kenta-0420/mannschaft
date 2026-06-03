package com.mannschaft.app.securityincident.dto;

import com.mannschaft.app.securityincident.SecurityIncidentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * セキュリティインシデント更新リクエスト DTO。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SecurityIncidentUpdateRequest {

    /** 更新後のステータス（null の場合は変更しない） */
    private SecurityIncidentStatus status;

    /** true にすると notifiedDpaAt = now() をセットする */
    private Boolean markDpaNotified;

    /** 解決日時（CLOSED 移行時に設定する） */
    private LocalDateTime resolvedAt;
}
