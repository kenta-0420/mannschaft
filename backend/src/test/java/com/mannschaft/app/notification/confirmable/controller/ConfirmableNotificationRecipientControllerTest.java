package com.mannschaft.app.notification.confirmable.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.notification.confirmable.mapper.ConfirmableNotificationMapper;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConfirmableNotificationRecipientController 単体テスト")
class ConfirmableNotificationRecipientControllerTest {

    @Mock
    private ConfirmableNotificationService notificationService;

    @Mock
    private ConfirmableNotificationMapper mapper;

    @InjectMocks
    private ConfirmableNotificationRecipientController controller;

    @Test
    @DisplayName("POST_confirm_自分宛て通知を確認して204を返す")
    void confirm_自分宛て通知を確認して204を返す() {
        long userId = 10L;
        long notificationId = 20L;
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(userId);

            ResponseEntity<Void> response = controller.confirm(notificationId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(notificationService).confirm(notificationId, userId);
        }
    }
}
