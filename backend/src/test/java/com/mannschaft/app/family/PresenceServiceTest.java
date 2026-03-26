package com.mannschaft.app.family;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.dto.PresenceEventResponse;
import com.mannschaft.app.family.dto.PresenceGoingOutRequest;
import com.mannschaft.app.family.dto.PresenceHomeRequest;
import com.mannschaft.app.family.entity.PresenceEventEntity;
import com.mannschaft.app.family.repository.PresenceEventRepository;
import com.mannschaft.app.family.service.PresenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("PresenceService 単体テスト")
class PresenceServiceTest {

    @Mock private PresenceEventRepository presenceEventRepository;
    @InjectMocks private PresenceService service;

    @Nested
    @DisplayName("sendHome")
    class SendHome {

        @Test
        @DisplayName("正常系: 在宅イベントが保存される")
        void 送信_正常_保存() {
            // Given
            given(presenceEventRepository.findFirstByTeamIdAndUserIdAndEventTypeAndReturnedAtIsNullOrderByCreatedAtDesc(
                    eq(1L), eq(100L), eq(EventType.GOING_OUT))).willReturn(Optional.empty());
            PresenceEventEntity saved = PresenceEventEntity.builder()
                    .teamId(1L).userId(100L).eventType(EventType.HOME).build();
            given(presenceEventRepository.save(any(PresenceEventEntity.class))).willReturn(saved);

            // When
            ApiResponse<PresenceEventResponse> result = service.sendHome(1L, 100L, new PresenceHomeRequest(null));

            // Then
            assertThat(result.getData().getEventType()).isEqualTo("HOME");
        }
    }

    @Nested
    @DisplayName("sendGoingOut")
    class SendGoingOut {

        @Test
        @DisplayName("異常系: 帰宅予定時刻が過去でFAMILY_001例外")
        void 送信_過去時刻_例外() {
            // Given
            PresenceGoingOutRequest req = new PresenceGoingOutRequest(
                    "スーパー", LocalDateTime.now().minusHours(1), null);

            // When / Then
            assertThatThrownBy(() -> service.sendGoingOut(1L, 100L, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_001"));
        }
    }
}
