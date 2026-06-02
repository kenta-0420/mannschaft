package com.mannschaft.app.securityincident;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.securityincident.entity.SecurityIncidentEntity;
import com.mannschaft.app.securityincident.repository.SecurityIncidentRepository;
import com.mannschaft.app.securityincident.service.SecurityIncident70hAlertBatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SecurityIncident70hAlertBatchService} のユニットテスト。
 */
@DisplayName("SecurityIncident70hAlertBatchService テスト")
@ExtendWith(MockitoExtension.class)
class SecurityIncident70hAlertBatchServiceTest {

    @Mock
    private SecurityIncidentRepository securityIncidentRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailOutboxService emailOutboxService;

    @InjectMocks
    private SecurityIncident70hAlertBatchService batchService;

    private static final LocalDateTime NOW = LocalDateTime.now();
    private static final LocalDateTime OVER_70H_AGO = NOW.minusHours(71);

    private SecurityIncidentEntity buildIncident(SecurityIncidentStatus status) {
        SecurityIncidentEntity entity = SecurityIncidentEntity.builder()
                .incidentType(SecurityIncidentType.DATA_BREACH)
                .severity(SecurityIncidentSeverity.CRITICAL)
                .detectedAt(OVER_70H_AGO)
                .status(status)
                .build();
        entity.setId(UUID.randomUUID());
        try {
            java.lang.reflect.Field createdAt = SecurityIncidentEntity.class.getDeclaredField("createdAt");
            createdAt.setAccessible(true);
            createdAt.set(entity, OVER_70H_AGO);
            java.lang.reflect.Field updatedAt = SecurityIncidentEntity.class.getDeclaredField("updatedAt");
            updatedAt.setAccessible(true);
            updatedAt.set(entity, OVER_70H_AGO);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return entity;
    }

    @Test
    @DisplayName("70時間超過の OPEN インシデントがある場合にメールを enqueue する")
    void checkAndAlert_withAlertTarget_enqueueMail() {
        SecurityIncidentEntity incident = buildIncident(SecurityIncidentStatus.OPEN);
        given(securityIncidentRepository.findAlertTargets(any(LocalDateTime.class)))
                .willReturn(List.of(incident));

        UserEntity admin = UserEntity.builder()
                .email("admin@example.com")
                .lastName("管理")
                .firstName("者")
                .displayName("管理者")
                .isSearchable(false)
                .build();
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(1L));
        given(userRepository.findByIdIn(List.of(1L))).willReturn(List.of(admin));

        batchService.checkAndAlert();

        verify(emailOutboxService).enqueue(any());
    }

    @Test
    @DisplayName("アラート対象が存在しない場合にメールを送信しない")
    void checkAndAlert_noTarget_doesNotEnqueueMail() {
        given(securityIncidentRepository.findAlertTargets(any(LocalDateTime.class)))
                .willReturn(List.of());

        batchService.checkAndAlert();

        verify(emailOutboxService, never()).enqueue(any());
        verify(userRoleRepository, never()).findSystemAdminUserIds();
    }

    @Test
    @DisplayName("CLOSED インシデントはアラート対象に含まれない（リポジトリの責務）")
    void checkAndAlert_closedIncidentNotInTargets() {
        // CLOSED インシデントはリポジトリのクエリ段階で除外されるため、
        // findAlertTargets が空を返した場合のテスト
        given(securityIncidentRepository.findAlertTargets(any(LocalDateTime.class)))
                .willReturn(List.of()); // CLOSED はリポジトリが除外済み

        batchService.checkAndAlert();

        verify(emailOutboxService, never()).enqueue(any());
    }

    @Test
    @DisplayName("SYSTEM_ADMIN が存在しない場合はメールを送信しない")
    void checkAndAlert_noSystemAdmin_doesNotEnqueueMail() {
        SecurityIncidentEntity incident = buildIncident(SecurityIncidentStatus.OPEN);
        given(securityIncidentRepository.findAlertTargets(any(LocalDateTime.class)))
                .willReturn(List.of(incident));
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of());

        batchService.checkAndAlert();

        verify(emailOutboxService, never()).enqueue(any());
    }
}
