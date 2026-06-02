package com.mannschaft.app.securityincident;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.securityincident.dto.SecurityIncidentCreateRequest;
import com.mannschaft.app.securityincident.dto.SecurityIncidentResponse;
import com.mannschaft.app.securityincident.dto.SecurityIncidentUpdateRequest;
import com.mannschaft.app.securityincident.entity.SecurityIncidentEntity;
import com.mannschaft.app.securityincident.repository.SecurityIncidentRepository;
import com.mannschaft.app.securityincident.service.SecurityIncidentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SecurityIncidentService} のユニットテスト。
 */
@DisplayName("SecurityIncidentService テスト")
@ExtendWith(MockitoExtension.class)
class SecurityIncidentServiceTest {

    @Mock
    private SecurityIncidentRepository repo;

    @InjectMocks
    private SecurityIncidentService service;

    private static final LocalDateTime DETECTED_AT = LocalDateTime.of(2026, 6, 1, 10, 0);

    private SecurityIncidentEntity buildEntity(SecurityIncidentStatus status) {
        SecurityIncidentEntity entity = SecurityIncidentEntity.builder()
                .incidentType(SecurityIncidentType.DATA_BREACH)
                .severity(SecurityIncidentSeverity.HIGH)
                .detectedAt(DETECTED_AT)
                .status(status)
                .build();
        entity.setId(UUID.randomUUID());
        // @PrePersist の代わりに手動でセット
        try {
            java.lang.reflect.Field createdAt = SecurityIncidentEntity.class.getDeclaredField("createdAt");
            createdAt.setAccessible(true);
            createdAt.set(entity, DETECTED_AT);
            java.lang.reflect.Field updatedAt = SecurityIncidentEntity.class.getDeclaredField("updatedAt");
            updatedAt.setAccessible(true);
            updatedAt.set(entity, DETECTED_AT);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return entity;
    }

    @Test
    @DisplayName("create: エンティティが保存され、レスポンスが返される")
    void create_savesEntityAndReturnsResponse() {
        SecurityIncidentCreateRequest req = SecurityIncidentCreateRequest.builder()
                .incidentType(SecurityIncidentType.DATA_BREACH)
                .severity(SecurityIncidentSeverity.HIGH)
                .detectedAt(DETECTED_AT)
                .description("個人データ漏洩の疑い")
                .build();

        SecurityIncidentEntity saved = buildEntity(SecurityIncidentStatus.OPEN);
        given(repo.save(any(SecurityIncidentEntity.class))).willReturn(saved);

        SecurityIncidentResponse response = service.create(req);

        assertThat(response).isNotNull();
        assertThat(response.getIncidentType()).isEqualTo(SecurityIncidentType.DATA_BREACH);
        assertThat(response.getSeverity()).isEqualTo(SecurityIncidentSeverity.HIGH);
        assertThat(response.getStatus()).isEqualTo(SecurityIncidentStatus.OPEN);
        verify(repo).save(any(SecurityIncidentEntity.class));
    }

    @Test
    @DisplayName("findAll: OPEN 優先ソート順が維持される")
    void findAll_returnsInCorrectOrder() {
        SecurityIncidentEntity open = buildEntity(SecurityIncidentStatus.OPEN);
        SecurityIncidentEntity investigating = buildEntity(SecurityIncidentStatus.INVESTIGATING);
        SecurityIncidentEntity closed = buildEntity(SecurityIncidentStatus.CLOSED);

        // リポジトリは OPEN -> INVESTIGATING -> CLOSED の順を返す
        given(repo.findAllByOrderByStatusAscDetectedAtDesc())
                .willReturn(List.of(open, investigating, closed));

        List<SecurityIncidentResponse> result = service.findAll();

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getStatus()).isEqualTo(SecurityIncidentStatus.OPEN);
        assertThat(result.get(1).getStatus()).isEqualTo(SecurityIncidentStatus.INVESTIGATING);
        assertThat(result.get(2).getStatus()).isEqualTo(SecurityIncidentStatus.CLOSED);
    }

    @Test
    @DisplayName("update: ステータスが正しく更新される")
    void update_updatesStatus() {
        UUID id = UUID.randomUUID();
        SecurityIncidentEntity entity = buildEntity(SecurityIncidentStatus.OPEN);
        entity.setId(id);

        given(repo.findById(id)).willReturn(Optional.of(entity));
        given(repo.save(any())).willReturn(entity);

        SecurityIncidentUpdateRequest req = SecurityIncidentUpdateRequest.builder()
                .status(SecurityIncidentStatus.INVESTIGATING)
                .build();

        service.update(id, req);

        verify(repo).save(entity);
    }

    @Test
    @DisplayName("update: DPA 通知日時が記録される")
    void update_marksDpaNotified() {
        UUID id = UUID.randomUUID();
        SecurityIncidentEntity entity = buildEntity(SecurityIncidentStatus.OPEN);
        entity.setId(id);

        given(repo.findById(id)).willReturn(Optional.of(entity));
        given(repo.save(any())).willReturn(entity);

        SecurityIncidentUpdateRequest req = SecurityIncidentUpdateRequest.builder()
                .markDpaNotified(true)
                .build();

        service.update(id, req);

        assertThat(entity.getNotifiedDpaAt()).isNotNull();
        verify(repo).save(entity);
    }

    @Test
    @DisplayName("update: 存在しない ID で BusinessException が発生する")
    void update_notFound_throwsBusinessException() {
        UUID unknownId = UUID.randomUUID();
        given(repo.findById(unknownId)).willReturn(Optional.empty());

        SecurityIncidentUpdateRequest req = SecurityIncidentUpdateRequest.builder()
                .status(SecurityIncidentStatus.CLOSED)
                .build();

        assertThatThrownBy(() -> service.update(unknownId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(SecurityIncidentErrorCode.SECURITY_INCIDENT_NOT_FOUND);
                });
    }
}
