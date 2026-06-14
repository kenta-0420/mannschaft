package com.mannschaft.app.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.team.dto.CreateTeamRequest;
import com.mannschaft.app.team.dto.TeamResponse;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamBlockRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.team.service.TeamShiftSettingsService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * チーム作成時のユーザー任意 slug（村方式統一）に関する単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamService ユーザー任意 slug 単体テスト")
class TeamSlugServiceTest {

    @Mock private TeamRepository teamRepository;
    @Mock private TeamBlockRepository teamBlockRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserRepository userRepository;
    @Mock private TeamFriendRepository teamFriendRepository;
    @Mock private TeamShiftSettingsService teamShiftSettingsService;
    @Mock private MembershipService membershipService;
    @Mock private MembershipRepository membershipRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private TeamService service;

    private static final Long USER_ID = 1L;

    private void givenCreateScaffold() {
        RoleEntity adminRole = RoleEntity.builder().name("ADMIN").build();
        try {
            var field = adminRole.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(adminRole, 1L);
        } catch (Exception ignored) {
            // テスト用リフレクション。失敗時はそのまま
        }
        lenient().when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(adminRole));
        lenient().when(userRoleRepository.save(any(UserRoleEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(teamFriendRepository.countFriendsByTeamId(any())).thenReturn(0L);
        lenient().when(membershipRepository.countActiveByScopeAndRoleKind(any(), any(), any())).thenReturn(0L);
    }

    @Nested
    @DisplayName("createTeam: slug 指定あり")
    class WithUserSlug {

        @Test
        @DisplayName("有効な slug を指定すると採用される（自動生成しない）")
        void 有効slug採用() {
            givenCreateScaffold();
            given(teamRepository.existsBySlugAndDeletedAtIsNull("my-team")).willReturn(false);

            CreateTeamRequest req = new CreateTeamRequest("テストチーム", null, null, null, null, null, null, "my-team");

            ApiResponse<TeamResponse> result = service.createTeam(USER_ID, req);

            assertThat(result.getData().getSlug()).isEqualTo("my-team");
        }

        @Test
        @DisplayName("形式不正 slug は TEAM_060")
        void 形式不正() {
            CreateTeamRequest req = new CreateTeamRequest("テストチーム", null, null, null, null, null, null, "Bad_Slug");

            assertThatThrownBy(() -> service.createTeam(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_060"));
        }

        @Test
        @DisplayName("予約語 slug は TEAM_061")
        void 予約語() {
            CreateTeamRequest req = new CreateTeamRequest("テストチーム", null, null, null, null, null, null, "admin");

            assertThatThrownBy(() -> service.createTeam(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_061"));
        }

        @Test
        @DisplayName("重複 slug は TEAM_062")
        void 重複() {
            given(teamRepository.existsBySlugAndDeletedAtIsNull("taken-slug")).willReturn(true);

            CreateTeamRequest req = new CreateTeamRequest("テストチーム", null, null, null, null, null, null, "taken-slug");

            assertThatThrownBy(() -> service.createTeam(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_062"));
        }
    }

    @Nested
    @DisplayName("createTeam: slug 未指定（後方互換）")
    class WithoutUserSlug {

        @Test
        @DisplayName("slug 未指定なら名前から自動生成される")
        void 自動生成フォールバック() {
            givenCreateScaffold();
            // 自動生成は createUniqueSlug 経由で existsBySlugAndDeletedAtIsNull を引く
            given(teamRepository.existsBySlugAndDeletedAtIsNull(eq("my-club"))).willReturn(false);

            CreateTeamRequest req = new CreateTeamRequest("My Club", null, null, null, null, null, null, null);

            ApiResponse<TeamResponse> result = service.createTeam(USER_ID, req);

            assertThat(result.getData().getSlug()).isEqualTo("my-club");
        }

        @Test
        @DisplayName("空文字 slug も未指定扱いで自動生成")
        void 空文字も自動生成() {
            givenCreateScaffold();
            given(teamRepository.existsBySlugAndDeletedAtIsNull(eq("my-club"))).willReturn(false);

            CreateTeamRequest req = new CreateTeamRequest("My Club", null, null, null, null, null, null, "  ");

            ApiResponse<TeamResponse> result = service.createTeam(USER_ID, req);

            assertThat(result.getData().getSlug()).isEqualTo("my-club");
        }
    }

    @Nested
    @DisplayName("checkSlugAvailability")
    class Availability {

        @Test
        @DisplayName("有効・未使用 slug は available=true")
        void 利用可能() {
            given(teamRepository.existsBySlugAndDeletedAtIsNull("free-slug")).willReturn(false);

            var res = service.checkSlugAvailability("free-slug");

            assertThat(res.available()).isTrue();
            assertThat(res.reason()).isNull();
        }

        @Test
        @DisplayName("重複 slug は available=false / SLUG_ALREADY_TAKEN")
        void 重複は利用不可() {
            given(teamRepository.existsBySlugAndDeletedAtIsNull("taken")).willReturn(true);

            var res = service.checkSlugAvailability("taken");

            assertThat(res.available()).isFalse();
            assertThat(res.reason()).isEqualTo("SLUG_ALREADY_TAKEN");
        }

        @Test
        @DisplayName("形式不正は SLUG_INVALID_FORMAT")
        void 形式不正は利用不可() {
            var res = service.checkSlugAvailability("Bad_Slug");

            assertThat(res.available()).isFalse();
            assertThat(res.reason()).isEqualTo("SLUG_INVALID_FORMAT");
        }

        @Test
        @DisplayName("予約語は SLUG_RESERVED")
        void 予約語は利用不可() {
            var res = service.checkSlugAvailability("admin");

            assertThat(res.available()).isFalse();
            assertThat(res.reason()).isEqualTo("SLUG_RESERVED");
        }

        @Test
        @DisplayName("空は SLUG_REQUIRED")
        void 空は利用不可() {
            var res = service.checkSlugAvailability("");

            assertThat(res.available()).isFalse();
            assertThat(res.reason()).isEqualTo("SLUG_REQUIRED");
        }
    }
}
