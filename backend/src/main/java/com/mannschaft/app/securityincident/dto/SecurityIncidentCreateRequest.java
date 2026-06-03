package com.mannschaft.app.securityincident.dto;

import com.mannschaft.app.securityincident.SecurityIncidentSeverity;
import com.mannschaft.app.securityincident.SecurityIncidentType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * セキュリティインシデント登録リクエスト DTO。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SecurityIncidentCreateRequest {

    @NotNull
    private SecurityIncidentType incidentType;

    @NotNull
    private SecurityIncidentSeverity severity;

    @NotNull
    private LocalDateTime detectedAt;

    private Integer recordsAffected;

    @Size(max = 5000)
    private String description;
}
