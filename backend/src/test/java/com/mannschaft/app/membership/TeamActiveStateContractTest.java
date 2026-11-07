package com.mannschaft.app.membership;

import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamService;
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

/** team状態の業務補完UT。実SQLの定数性・絞込は実MySQL ITが保証する。 */
@ExtendWith(MockitoExtension.class)
class TeamActiveStateContractTest {
    @Mock private TeamRepository teams;
    @InjectMocks private TeamService service;
    @Test
    void nullと空入力は照会しない() {
        assertThat(service.getActiveStates(null)).isEmpty();
        assertThat(service.getActiveStates(List.of())).isEmpty();
        verifyNoInteractions(teams);
    }
    @Test
    void 重複nullを除いて一回照会しmissingは返さない() {
        when(teams.findActiveStateRows(any())).thenReturn(List.of(row(true)));
        assertThat(service.getActiveStates(Arrays.asList(13L,null,13L,99L))).containsOnlyKeys(13L);
        var ids=ArgumentCaptor.forClass(Collection.class);
        verify(teams).findActiveStateRows(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(13L,99L);
        verifyNoMoreInteractions(teams);
    }
    @Test
    void activeチームの名称を返す() {
        when(teams.findActiveStateRows(any())).thenReturn(List.of(row(true)));
        assertThat(service.getActiveStates(List.of(13L)).get(13L))
                .isEqualTo(new TeamService.ActiveState(true,"チーム表示名"));
    }
    @Test
    void inactive投影は表示名を返さない() {
        when(teams.findActiveStateRows(any())).thenReturn(List.of(row(false)));
        assertThat(service.getActiveStates(List.of(13L)).get(13L))
                .isEqualTo(new TeamService.ActiveState(false,null));
    }
    @Test
    void active不明はfailclosedで表示名を返さない() {
        when(teams.findActiveStateRows(any())).thenReturn(List.of(row(null)));
        assertThat(service.getActiveStates(List.of(13L)).get(13L))
                .isEqualTo(new TeamService.ActiveState(false,null));
    }
    private TeamRepository.ActiveStateRow row(Boolean active) {
        return new TeamRepository.ActiveStateRow() {
            public Long getId(){return 13L;}
            public String getName(){return "チーム表示名";}
            public Boolean getActive(){return active;}
        };
    }
}
