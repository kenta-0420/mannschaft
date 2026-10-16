package com.mannschaft.app.membership;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserService;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        when(users.findActiveDisplayStateRows(any())).thenReturn(List.of(row(true)));
        assertThat(service.getActiveDisplayStates(Arrays.asList(7L, null, 7L, 99L))).containsOnlyKeys(7L);
        var ids=ArgumentCaptor.forClass(Collection.class);
        verify(users).findActiveDisplayStateRows(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(7L,99L);
        verifyNoMoreInteractions(users);
    }
    @Test
    void active本人の表示名だけを返す() {
        when(users.findActiveDisplayStateRows(any())).thenReturn(List.of(row(true)));
        assertThat(service.getActiveDisplayStates(List.of(7L)).get(7L))
                .isEqualTo(new UserService.ActiveDisplayState(true,"秘匿する旧表示名"));
    }
    @Test
    void inactive投影は表示名を返さない() {
        when(users.findActiveDisplayStateRows(any())).thenReturn(List.of(row(false)));
        assertThat(service.getActiveDisplayStates(List.of(7L)).get(7L))
                .isEqualTo(new UserService.ActiveDisplayState(false,null));
    }
    @Test
    void active不明はfailclosedで表示名を返さない() {
        when(users.findActiveDisplayStateRows(any())).thenReturn(List.of(row(null)));
        assertThat(service.getActiveDisplayStates(List.of(7L)).get(7L))
                .isEqualTo(new UserService.ActiveDisplayState(false,null));
    }
    private UserRepository.ActiveDisplayStateRow row(Boolean active) {
        return new UserRepository.ActiveDisplayStateRow() {
            public Long getId(){return 7L;}
            public String getDisplayName(){return "秘匿する旧表示名";}
            public Boolean getActive(){return active;}
        };
    }
}
