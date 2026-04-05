package com.mannschaft.app.team;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.dto.CreateTeamRequest;
import com.mannschaft.app.team.dto.TeamResponse;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamBlockRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamService;
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
@DisplayName("TeamService 単体テスト")
class TeamServiceTest {

    @Mock private TeamRepository teamRepository;
    @Mock private TeamBlockRepository teamBlockRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private TeamService service;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;

    @Nested
    @DisplayName("createTeam")
    class CreateTeam {

        @Test
        @DisplayName("正常系: チームが作成され作成者がADMINになる")
        void 作成_正常_保存() {
            // Given
            CreateTeamRequest req = new CreateTeamRequest("テストチーム", "sports", "東京都", "渋谷区", null);
            RoleEntity adminRole = RoleEntity.builder().name("ADMIN").build();
            try {
                var field = adminRole.getClass().getSuperclass().getDeclaredField("id");
                field.setAccessible(true);
                field.set(adminRole, 1L);
            } catch (Exception ignored) {}
            given(roleRepository.findByName("ADMIN")).willReturn(Optional.of(adminRole));
            given(userRoleRepository.save(any(UserRoleEntity.class))).willAnswer(inv -> inv.getArgument(0));

            // When
            ApiResponse<TeamResponse> result = service.createTeam(USER_ID, req);

            // Then
            assertThat(result.getData().getName()).isEqualTo("テストチーム");
            verify(teamRepository).save(any(TeamEntity.class));
            verify(userRoleRepository).save(any(UserRoleEntity.class));
        }
    }

    @Nested
    @DisplayName("getTeam")
    class GetTeam {

        @Test
        @DisplayName("異常系: チーム不在でTEAM_001例外")
        void 取得_不在_例外() {
            // Given
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getTeam(TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_001"));
        }
    }

    @Nested
    @DisplayName("archiveTeam")
    class ArchiveTeam {

        @Test
        @DisplayName("異常系: 既にアーカイブ済みでTEAM_002例外")
        void アーカイブ_既済_例外() {
            // Given
            TeamEntity team = TeamEntity.builder().name("テスト").template("sports")
                    .visibility(TeamEntity.Visibility.PRIVATE).build();
            team.archive(); // archivedAtをセット
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            // When / Then
            assertThatThrownBy(() -> service.archiveTeam(TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_002"));
        }
    }

    @Nested
    @DisplayName("followTeam")
    class FollowTeam {

        @Test
        @DisplayName("異常系: ブロックされている場合TEAM_004例外")
        void フォロー_ブロック_例外() {
            // Given
            TeamEntity team = TeamEntity.builder().name("テスト").template("sports")
                    .visibility(TeamEntity.Visibility.PRIVATE).build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(teamBlockRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.followTeam(USER_ID, TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_004"));
        }

        @Test
        @DisplayName("異常系: 既に所属している場合TEAM_003例外")
        void フォロー_既所属_例外() {
            // Given
            TeamEntity team = TeamEntity.builder().name("テスト").template("sports")
                    .visibility(TeamEntity.Visibility.PRIVATE).build();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(teamBlockRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).willReturn(false);
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, TEAM_ID)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.followTeam(USER_ID, TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_003"));
        }
    }
}
