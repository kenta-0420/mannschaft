package com.mannschaft.app.securityincident.dto;

import com.mannschaft.app.securityincident.SecurityIncidentSeverity;
import com.mannschaft.app.securityincident.SecurityIncidentStatus;
import com.mannschaft.app.securityincident.SecurityIncidentType;
import com.mannschaft.app.securityincident.entity.SecurityIncidentEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * セキュリティインシデント レスポンス DTO。
 */
@Getter
@Builder
public class SecurityIncidentResponse {

    private UUID id;
    private SecurityIncidentType incidentType;
    private SecurityIncidentSeverity severity;
    private LocalDateTime detectedAt;
    private Integer recordsAffected;
    private String description;
    private SecurityIncidentStatus status;
    private LocalDateTime notifiedDpaAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 70時間アラートまでの残り時間（分）。
     * 負値はすでに超過していることを示す。
     */
    private long minutesUntil70hAlert;

    /**
     * エンティティからレスポンス DTO を生成する。
     *
     * @param entity セキュリティインシデントエンティティ
     * @return レスポンス DTO
     */
    public static SecurityIncidentResponse from(SecurityIncidentEntity entity) {
        long minutesUntil = ChronoUnit.MINUTES.between(
                LocalDateTime.now(),
                entity.getDetectedAt().plusHours(70)
        );
        return SecurityIncidentResponse.builder()
                .id(entity.getId())
                .incidentType(entity.getIncidentType())
                .severity(entity.getSeverity())
                .detectedAt(entity.getDetectedAt())
                .recordsAffected(entity.getRecordsAffected())
                .description(entity.getDescription())
                .status(entity.getStatus())
                .notifiedDpaAt(entity.getNotifiedDpaAt())
                .resolvedAt(entity.getResolvedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .minutesUntil70hAlert(minutesUntil)
                .build();
    }
}
