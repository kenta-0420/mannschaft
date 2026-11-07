package com.mannschaft.app.team.service;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamBlockRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** {@link TeamInviteQueryService} の単体テスト（CMP-260826-1457）。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamInviteQueryService 単体テスト")
class TeamInviteQueryServiceTest {

    @Mock
    private TeamBlockRepository teamBlockRepository;

    @Mock
    private TeamRepository teamRepository;

    @InjectMocks
    private TeamInviteQueryService service;

    @Test
    @DisplayName("isBlocked: ブロックの有無を boolean で返し、例外にしない")
    void isBlocked() {
        given(teamBlockRepository.existsByTeamIdAndUserId(10L, 1L)).willReturn(true);

        assertThat(service.isBlocked(10L, 1L)).isTrue();
        assertThat(service.isBlocked(10L, 2L)).isFalse();
    }

    @Test
    @DisplayName("findName: チーム名を String で返し、無ければ null（Entity は外に出さない）")
    void findName() {
        given(teamRepository.findById(10L)).willReturn(Optional.of(TeamEntity.builder().name("テストチーム").build()));

        assertThat(service.findName(10L)).isEqualTo("テストチーム");
        assertThat(service.findName(11L)).isNull();
    }
}
