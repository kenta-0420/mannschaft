package com.mannschaft.app.directmail;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.directmail.dto.CreateDirectMailRequest;
import com.mannschaft.app.directmail.dto.DirectMailResponse;
import com.mannschaft.app.directmail.dto.UpdateDirectMailRequest;
import com.mannschaft.app.directmail.entity.DirectMailLogEntity;
import com.mannschaft.app.directmail.repository.DirectMailLogRepository;
import com.mannschaft.app.directmail.repository.DirectMailRecipientRepository;
import com.mannschaft.app.directmail.service.DirectMailService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("DirectMailService 単体テスト")
class DirectMailServiceTest {

    @Mock private DirectMailLogRepository mailLogRepository;
    @Mock private DirectMailRecipientRepository recipientRepository;
    @Mock private DirectMailMapper directMailMapper;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private DomainEventPublisher eventPublisher;
    @InjectMocks private DirectMailService service;

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 1L;

    @Nested
    @DisplayName("createMail")
    class CreateMail {

        @Test
        @DisplayName("正常系: メールが下書き保存される")
        void 作成_正常_保存() {
            // Given
            CreateDirectMailRequest req = new CreateDirectMailRequest(
                    "件名", "# 本文", null, "ALL", null, 100);
            given(mailLogRepository.save(any(DirectMailLogEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(directMailMapper.toMailResponse(any(DirectMailLogEntity.class)))
                    .willReturn(new DirectMailResponse(1L, SCOPE_TYPE, SCOPE_ID, 100L,
                            "件名", "# 本文", null, "ALL", null, 100, null, 0, 0, 0, "DRAFT", null, null, null, null, null));

            // When
            DirectMailResponse result = service.createMail(SCOPE_TYPE, SCOPE_ID, 100L, req);

            // Then
            assertThat(result.getSubject()).isEqualTo("件名");
            verify(mailLogRepository).save(any(DirectMailLogEntity.class));
        }
    }

    @Nested
    @DisplayName("updateMail")
    class UpdateMail {

        @Test
        @DisplayName("異常系: 下書きでない場合DM_003例外")
        void 更新_下書きでない_例外() {
            // Given
            DirectMailLogEntity entity = DirectMailLogEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).senderId(100L)
                    .subject("件名").build();
            try {
                var field = DirectMailLogEntity.class.getDeclaredField("status");
                field.setAccessible(true);
                field.set(entity, "SENT");
            } catch (Exception ignored) {}
            given(mailLogRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.updateMail(SCOPE_TYPE, SCOPE_ID, 1L,
                    new UpdateDirectMailRequest("新件名", null, null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DM_003"));
        }
    }

    @Nested
    @DisplayName("sendMail")
    class SendMail {

        @Test
        @DisplayName("異常系: メール不在でDM_001例外")
        void 送信_不在_例外() {
            // Given
            given(mailLogRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.sendMail(SCOPE_TYPE, SCOPE_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DM_001"));
        }
    }

    @Nested
    @DisplayName("cancelMail")
    class CancelMail {

        @Test
        @DisplayName("異常系: 送信中のメールはキャンセル不可でDM_004例外")
        void キャンセル_送信中_例外() {
            // Given
            DirectMailLogEntity entity = DirectMailLogEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).senderId(100L)
                    .subject("件名").build();
            try {
                var field = DirectMailLogEntity.class.getDeclaredField("status");
                field.setAccessible(true);
                field.set(entity, "SENDING");
            } catch (Exception ignored) {}
            given(mailLogRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.cancelMail(SCOPE_TYPE, SCOPE_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DM_004"));
        }
    }
}
