package com.mannschaft.app.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.dto.SlugResolveResponse;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamSlugHistoryEntity;
import com.mannschaft.app.team.repository.TeamBlockRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.repository.TeamSlugHistoryRepository;
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
 * F01.2 §5.9.5 チーム slug リネーム / 301 解決の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamService slug リネーム / 301 解決 単体テスト")
class TeamSlugRenameResolveServiceTest {

    @Mock private TeamRepository teamRepository;
    @Mock private TeamSlugHistoryRepository teamSlugHistoryRepository;
    @Mock private TeamBlockRepository teamBlockRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserRepository userRepository;
    @Mock private TeamFriendRepository teamFriendRepository;
    @Mock private TeamShiftSettingsService teamShiftSettingsService;
    @Mock private MembershipService membershipService;
    @Mock private MembershipRepository membershipRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private MediaUrlResolver mediaUrlResolver;
    @InjectMocks private TeamService service;

    private static final Long TEAM_ID = 10L;

    private TeamEntity teamWithSlug(String slug) {
        return TeamEntity.builder()
                .name("テストチーム")
                .slug(slug)
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(false)
                .build();
    }

    private void givenCounts() {
        lenient().when(userRoleRepository.countByTeamId(any())).thenReturn(1L);
        lenient().when(teamFriendRepository.countFriendsByTeamId(any())).thenReturn(0L);
        lenient().when(membershipRepository.countActiveByScopeAndRoleKind(any(), any(), any())).thenReturn(0L);
    }

    @Nested
    @DisplayName("renameSlug")
    class Rename {

        @Test
        @DisplayName("正常系: 履歴INSERT＋slug更新")
        void 正常リネーム() {
            givenCounts();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(teamWithSlug("old-slug")));
            given(teamRepository.existsBySlugAndDeletedAtIsNull("new-slug")).willReturn(false);
            given(teamSlugHistoryRepository.existsByOldSlugAndTeamIdNot("new-slug", TEAM_ID)).willReturn(false);

            var result = service.renameSlug(TEAM_ID, "new-slug");

            assertThat(result.getData().getSlug()).isEqualTo("new-slug");
            verify(teamSlugHistoryRepository).save(any(TeamSlugHistoryEntity.class));
            verify(teamRepository).save(any(TeamEntity.class));
        }

        @Test
        @DisplayName("no-op: 現slugと同一なら履歴を書かず200")
        void noop() {
            givenCounts();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(teamWithSlug("same-slug")));

            var result = service.renameSlug(TEAM_ID, "same-slug");

            assertThat(result.getData().getSlug()).isEqualTo("same-slug");
            verify(teamSlugHistoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("形式不正は TEAM_060")
        void 形式不正() {
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(teamWithSlug("old-slug")));

            assertThatThrownBy(() -> service.renameSlug(TEAM_ID, "Bad_Slug"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_060"));
            verify(teamSlugHistoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("予約語は TEAM_061")
        void 予約語() {
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(teamWithSlug("old-slug")));

            assertThatThrownBy(() -> service.renameSlug(TEAM_ID, "admin"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_061"));
        }

        @Test
        @DisplayName("既存slug重複は TEAM_062")
        void 重複() {
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(teamWithSlug("old-slug")));
            given(teamRepository.existsBySlugAndDeletedAtIsNull("taken")).willReturn(true);

            assertThatThrownBy(() -> service.renameSlug(TEAM_ID, "taken"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_062"));
        }

        @Test
        @DisplayName("他チームの履歴予約済みは TEAM_063（SLUG_RETIRED）")
        void 履歴予約() {
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(teamWithSlug("old-slug")));
            given(teamRepository.existsBySlugAndDeletedAtIsNull("retired")).willReturn(false);
            given(teamSlugHistoryRepository.existsByOldSlugAndTeamIdNot("retired", TEAM_ID)).willReturn(true);

            assertThatThrownBy(() -> service.renameSlug(TEAM_ID, "retired"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_063"));
        }

        @Test
        @DisplayName("自チームの過去slugへの戻しは許可（teamId除外で弾かれない）")
        void 自チーム戻し許可() {
            givenCounts();
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(teamWithSlug("current-slug")));
            given(teamRepository.existsBySlugAndDeletedAtIsNull("former-own")).willReturn(false);
            // 自チーム除外の判定では false（戻し許可）
            given(teamSlugHistoryRepository.existsByOldSlugAndTeamIdNot("former-own", TEAM_ID)).willReturn(false);

            var result = service.renameSlug(TEAM_ID, "former-own");

            assertThat(result.getData().getSlug()).isEqualTo("former-own");
            verify(teamSlugHistoryRepository).save(any(TeamSlugHistoryEntity.class));
        }
    }

    @Nested
    @DisplayName("resolveSlug")
    class Resolve {

        @Test
        @DisplayName("現slugで存在すれば CURRENT")
        void current() {
            given(teamRepository.existsBySlugAndDeletedAtIsNull("alive")).willReturn(true);

            var res = service.resolveSlug("alive");

            assertThat(res.status()).isEqualTo(SlugResolveResponse.Status.CURRENT);
            assertThat(res.canonicalSlug()).isNull();
        }

        @Test
        @DisplayName("旧slugは MOVED→現slugを canonicalSlug で返す")
        void moved() {
            given(teamRepository.existsBySlugAndDeletedAtIsNull("old-name")).willReturn(false);
            TeamSlugHistoryEntity history = TeamSlugHistoryEntity.builder()
                    .teamId(TEAM_ID).oldSlug("old-name").build();
            given(teamSlugHistoryRepository.findByOldSlug("old-name")).willReturn(Optional.of(history));
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(teamWithSlug("current-name")));

            var res = service.resolveSlug("old-name");

            assertThat(res.status()).isEqualTo(SlugResolveResponse.Status.MOVED);
            assertThat(res.canonicalSlug()).isEqualTo("current-name");
        }

        @Test
        @DisplayName("現slugにも履歴にも無ければ NOT_FOUND")
        void notFound() {
            given(teamRepository.existsBySlugAndDeletedAtIsNull("ghost")).willReturn(false);
            given(teamSlugHistoryRepository.findByOldSlug("ghost")).willReturn(Optional.empty());

            var res = service.resolveSlug("ghost");

            assertThat(res.status()).isEqualTo(SlugResolveResponse.Status.NOT_FOUND);
        }

        @Test
        @DisplayName("履歴は引けたが対象チームが現存しなければ NOT_FOUND")
        void 履歴対象消失() {
            given(teamRepository.existsBySlugAndDeletedAtIsNull("orphan")).willReturn(false);
            TeamSlugHistoryEntity history = TeamSlugHistoryEntity.builder()
                    .teamId(TEAM_ID).oldSlug("orphan").build();
            given(teamSlugHistoryRepository.findByOldSlug("orphan")).willReturn(Optional.of(history));
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.empty());

            var res = service.resolveSlug("orphan");

            assertThat(res.status()).isEqualTo(SlugResolveResponse.Status.NOT_FOUND);
        }
    }
}
