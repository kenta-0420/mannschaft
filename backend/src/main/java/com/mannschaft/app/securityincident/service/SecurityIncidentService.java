package com.mannschaft.app.securityincident.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.securityincident.SecurityIncidentErrorCode;
import com.mannschaft.app.securityincident.dto.SecurityIncidentCreateRequest;
import com.mannschaft.app.securityincident.dto.SecurityIncidentResponse;
import com.mannschaft.app.securityincident.dto.SecurityIncidentUpdateRequest;
import com.mannschaft.app.securityincident.entity.SecurityIncidentEntity;
import com.mannschaft.app.securityincident.repository.SecurityIncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * セキュリティインシデント管理サービス。
 *
 * <p>GDPR Article 33 に基づく DPA 通知義務の追跡・管理を担う。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityIncidentService {

    private final SecurityIncidentRepository repo;

    /**
     * セキュリティインシデントを新規登録する。
     *
     * @param req 登録リクエスト
     * @return 登録したインシデントのレスポンス
     */
    @Transactional
    public SecurityIncidentResponse create(SecurityIncidentCreateRequest req) {
        SecurityIncidentEntity entity = SecurityIncidentEntity.builder()
                .incidentType(req.getIncidentType())
                .severity(req.getSeverity())
                .detectedAt(req.getDetectedAt())
                .recordsAffected(req.getRecordsAffected())
                .description(req.getDescription())
                .build();
        SecurityIncidentEntity saved = repo.save(entity);
        log.info("セキュリティインシデント登録: id={}, type={}, severity={}",
                saved.getId(), saved.getIncidentType(), saved.getSeverity());
        return SecurityIncidentResponse.from(saved);
    }

    /**
     * セキュリティインシデント一覧を取得する（OPEN 優先・検出時刻降順）。
     *
     * @return インシデント一覧
     */
    @Transactional(readOnly = true)
    public List<SecurityIncidentResponse> findAll() {
        return repo.findAllByOrderByStatusAscDetectedAtDesc().stream()
                .map(SecurityIncidentResponse::from)
                .toList();
    }

    /**
     * セキュリティインシデントを更新する（ステータス変更・DPA通知記録）。
     *
     * @param id  更新対象のインシデント ID
     * @param req 更新リクエスト
     * @return 更新後のインシデントのレスポンス
     * @throws BusinessException インシデントが見つからない場合
     */
    @Transactional
    public SecurityIncidentResponse update(UUID id, SecurityIncidentUpdateRequest req) {
        SecurityIncidentEntity entity = repo.findById(id)
                .orElseThrow(() -> new BusinessException(SecurityIncidentErrorCode.SECURITY_INCIDENT_NOT_FOUND));

        if (req.getStatus() != null) {
            entity.updateStatus(req.getStatus(), req.getResolvedAt());
        }
        if (Boolean.TRUE.equals(req.getMarkDpaNotified())) {
            entity.markDpaNotified(LocalDateTime.now());
        }

        SecurityIncidentEntity saved = repo.save(entity);
        log.info("セキュリティインシデント更新: id={}, status={}, notifiedDpaAt={}",
                saved.getId(), saved.getStatus(), saved.getNotifiedDpaAt());
        return SecurityIncidentResponse.from(saved);
    }
}
