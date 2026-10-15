package com.mannschaft.app.proxy.batch;

import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import com.mannschaft.app.auth.event.UserStatusChangedEvent;
import com.mannschaft.app.proxy.service.ProxyConsentLifecycleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProxyConsentLifeEventJobTest {

    @Mock
    private ProxyConsentLifecycleService lifecycleService;

    @InjectMocks
    private ProxyConsentLifeEventJob sut;

    @Test
    @DisplayName("DECEASED への変更で全同意書を失効させる")
    void deceasedTriggerRevocation() {
        UserStatusChangedEvent event =
                new UserStatusChangedEvent(this, 10L, UserStatus.ACTIVE, UserStatus.DECEASED);
        sut.onUserStatusChanged(event);
        verify(lifecycleService).revokeAllForUser(10L, "ユーザーステータス変更: DECEASED");
    }

    @Test
    @DisplayName("RELOCATED への変更で全同意書を失効させる")
    void relocatedTriggerRevocation() {
        UserStatusChangedEvent event =
                new UserStatusChangedEvent(this, 20L, UserStatus.ACTIVE, UserStatus.RELOCATED);
        sut.onUserStatusChanged(event);
        verify(lifecycleService).revokeAllForUser(20L, "ユーザーステータス変更: RELOCATED");
    }

    @Test
    @DisplayName("FROZEN への変更では失効させない（復帰可能なため）")
    void frozenDoesNotTriggerRevocation() {
        UserStatusChangedEvent event =
                new UserStatusChangedEvent(this, 30L, UserStatus.ACTIVE, UserStatus.FROZEN);
        sut.onUserStatusChanged(event);
        verify(lifecycleService, never()).revokeAllForUser(anyLong(), anyString());
    }

    @Test
    @DisplayName("ARCHIVED への変更では失効させない（復帰可能なため）")
    void archivedDoesNotTriggerRevocation() {
        UserStatusChangedEvent event =
                new UserStatusChangedEvent(this, 40L, UserStatus.ACTIVE, UserStatus.ARCHIVED);
        sut.onUserStatusChanged(event);
        verify(lifecycleService, never()).revokeAllForUser(anyLong(), anyString());
    }
}
