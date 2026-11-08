package com.mannschaft.app.membership;

import com.mannschaft.app.membership.repository.CheckinLocationRepository;
import com.mannschaft.app.membership.service.CheckinLocationService;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 受付batchの業務補完UT。実SQLの絞込と定数照会は実MySQL ITで別途検査する。 */
class TeamLocationStateContractTest {
    private CheckinLocationRepository locations;
    private CheckinLocationService service;
    @BeforeEach
    void 準備() {
        locations = mock(CheckinLocationRepository.class);
        service = new CheckinLocationService(locations, null, null, null, null);
    }
    @Test
    void nullと空入力は照会せず空を返す() {
        assertThat(service.getTeamLocationStates(null)).isEmpty();
        assertThat(service.getTeamLocationStates(List.of())).isEmpty();
        verifyNoInteractions(locations);
    }
    @Test
    void 重複とnullを除いて一回だけ集合照会しmissingは返さない() {
        when(locations.findTeamLocationStateRows(any())).thenReturn(List.of(row(11L, true, "受付名")));
        assertThat(service.getTeamLocationStates(Arrays.asList(11L, 11L, null, 99L)))
                .containsOnlyKeys(11L);
        var ids = ArgumentCaptor.forClass(Collection.class);
        verify(locations).findTeamLocationStateRows(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(11L, 99L);
        verifyNoMoreInteractions(locations);
    }
    @Test
    void 有効受付はteamと名称を返す() {
        when(locations.findTeamLocationStateRows(any())).thenReturn(List.of(row(11L, true, "受付名")));
        assertThat(service.getTeamLocationStates(List.of(11L)).get(11L))
                .isEqualTo(new CheckinLocationService.TeamLocationState(true, 13L, "受付名"));
    }
    @Test
    void 無効受付は名称を返さずinactiveとする() {
        when(locations.findTeamLocationStateRows(any())).thenReturn(List.of(row(11L, false, "非公開の旧名称")));
        assertThat(service.getTeamLocationStates(List.of(11L)).get(11L))
                .isEqualTo(new CheckinLocationService.TeamLocationState(false, 13L, null));
    }
    private CheckinLocationRepository.TeamLocationStateRow row(Long id, boolean active, String name) {
        return new CheckinLocationRepository.TeamLocationStateRow() {
            public Long getId() { return id; }
            public Long getTeamId() { return 13L; }
            public Boolean getActive() { return active; }
            public String getName() { return name; }
        };
    }
}
