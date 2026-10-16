package com.mannschaft.app.membership;

import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 本人状態の補完UT。PII抑制と集合境界を実UserServiceで固定し、実SQLはITが保証する。 */
@ExtendWith(MockitoExtension.class)
class UserActiveDisplayStateContractTest {
    @Mock private UserRepository users;
    @InjectMocks private UserService service;

    @Test
    void nullと空入力は照会しない() {
        assertThat(service.getActiveDisplayStates(null)).isEmpty();
        assertThat(service.getActiveDisplayStates(List.of())).isEmpty();
        verifyNoInteractions(users);
    }
    @Test
    void 重複nullを除いて一度照会しmissingを返さない() {
        when(users.findActiveDisplayStateRows(any())).thenReturn(List.of(row(UserStatus.ACTIVE, null, null)));
        assertThat(service.getActiveDisplayStates(Arrays.asList(7L, null, 7L, 99L))).containsOnlyKeys(7L);
        var ids=ArgumentCaptor.forClass(Collection.class);
        verify(users).findActiveDisplayStateRows(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(7L,99L);
        verifyNoMoreInteractions(users);
    }
    @Test
    void active本人の表示名だけを返す() {
        when(users.findActiveDisplayStateRows(any())).thenReturn(List.of(row(UserStatus.ACTIVE, null, null)));
        assertThat(service.getActiveDisplayStates(List.of(7L)).get(7L))
                .isEqualTo(new UserService.ActiveDisplayState(true,"秘匿する旧表示名"));
    }
    @ParameterizedTest
    @EnumSource(value=UserStatus.class, mode=EnumSource.Mode.EXCLUDE, names="ACTIVE")
    void active以外は表示PIIを返さない(UserStatus status) {
        when(users.findActiveDisplayStateRows(any())).thenReturn(List.of(row(status,null,null)));
        assertThat(service.getActiveDisplayStates(List.of(7L)).get(7L))
                .isEqualTo(new UserService.ActiveDisplayState(false,null));
    }
    @Test
    void statusがactiveでもarchive済みなら表示PIIを返さない() {
        when(users.findActiveDisplayStateRows(any())).thenReturn(List.of(row(UserStatus.ACTIVE,LocalDateTime.of(2026,1,1,0,0),null)));
        assertThat(service.getActiveDisplayStates(List.of(7L)).get(7L))
                .isEqualTo(new UserService.ActiveDisplayState(false,null));
    }
    @Test
    void 退会削除済みなら表示PIIを返さない() {
        when(users.findActiveDisplayStateRows(any())).thenReturn(List.of(row(UserStatus.ACTIVE,null,LocalDateTime.of(2026,1,1,0,0))));
        assertThat(service.getActiveDisplayStates(List.of(7L)).get(7L))
                .isEqualTo(new UserService.ActiveDisplayState(false,null));
    }
    private UserRepository.ActiveDisplayStateRow row(UserStatus status,LocalDateTime archived,LocalDateTime deleted) {
        return new UserRepository.ActiveDisplayStateRow() {
            public Long getId(){return 7L;}
            public String getDisplayName(){return "秘匿する旧表示名";}
            public UserStatus getStatus(){return status;}
            public LocalDateTime getArchivedAt(){return archived;}
            public LocalDateTime getDeletedAt(){return deleted;}
        };
    }
}
