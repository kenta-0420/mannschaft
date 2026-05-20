package com.mannschaft.app.publicview.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@link PostAuthorSnapshotService} の単体テスト。
 *
 * <p>F19.1 §4.7 非対称切替ルール対応の投稿時スナップショット取得ロジックを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostAuthorSnapshotService 単体テスト")
class PostAuthorSnapshotServiceTest {

    @Mock
    private TeamRepository teamRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private PostAuthorSnapshotService service;

    private static final Long TEAM_ID = 1L;
    private static final Long ORG_ID = 2L;
    private static final Long USER_ID = 100L;

    // ========================================
    // resolveForTeamPost
    // ========================================

    @Nested
    @DisplayName("resolveForTeamPost")
    class ResolveForTeamPost {

        @Test
        @DisplayName("正常系: REAL_NAME モードの場合、本名スナップショットを返す")
        void チーム_REAL_NAME_本名返却() {
            // Given
            TeamEntity team = mock(TeamEntity.class);
            given(team.getSupporterNameDisclosure()).willReturn(NameDisclosureMode.REAL_NAME);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            UserEntity user = mock(UserEntity.class);
            given(user.getLastName()).willReturn("山田");
            given(user.getFirstName()).willReturn("太郎");
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            // When
            String result = service.resolveForTeamPost(TEAM_ID, USER_ID);

            // Then
            assertThat(result).isEqualTo("山田太郎");
        }

        @Test
        @DisplayName("正常系: DISPLAY_NAME モードの場合、null を返す（スナップショット不要）")
        void チーム_DISPLAY_NAME_null返却() {
            // Given
            TeamEntity team = mock(TeamEntity.class);
            given(team.getSupporterNameDisclosure()).willReturn(NameDisclosureMode.DISPLAY_NAME);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            // When
            String result = service.resolveForTeamPost(TEAM_ID, USER_ID);

            // Then
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("正常系: チームの disclosure が null の場合は DISPLAY_NAME 扱いで null を返す")
        void チーム_disclosure_null_DISPLAY_NAME扱い() {
            // Given
            TeamEntity team = mock(TeamEntity.class);
            given(team.getSupporterNameDisclosure()).willReturn(null);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            // When
            String result = service.resolveForTeamPost(TEAM_ID, USER_ID);

            // Then
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("正常系: REAL_NAME モードでユーザーの lastName のみの場合、lastName を返す")
        void チーム_REAL_NAME_lastName_のみ() {
            // Given
            TeamEntity team = mock(TeamEntity.class);
            given(team.getSupporterNameDisclosure()).willReturn(NameDisclosureMode.REAL_NAME);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));

            UserEntity user = mock(UserEntity.class);
            given(user.getLastName()).willReturn("山田");
            given(user.getFirstName()).willReturn(null);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            // When
            String result = service.resolveForTeamPost(TEAM_ID, USER_ID);

            // Then
            assertThat(result).isEqualTo("山田");
        }

        @Test
        @DisplayName("異常系: チームが存在しない場合、null を返す（安全動作）")
        void チーム不在_null返却() {
            // Given
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.empty());

            // When
            String result = service.resolveForTeamPost(TEAM_ID, USER_ID);

            // Then
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("異常系: REAL_NAME モードでユーザーが存在しない場合、null を返す")
        void ユーザー不在_null返却() {
            // Given
            TeamEntity team = mock(TeamEntity.class);
            given(team.getSupporterNameDisclosure()).willReturn(NameDisclosureMode.REAL_NAME);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

            // When
            String result = service.resolveForTeamPost(TEAM_ID, USER_ID);

            // Then
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("異常系: teamId が null の場合、null を返す")
        void teamId_null_null返却() {
            // When
            String result = service.resolveForTeamPost(null, USER_ID);

            // Then
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("異常系: authorUserId が null の場合、null を返す")
        void userId_null_null返却() {
            // When
            String result = service.resolveForTeamPost(TEAM_ID, null);

            // Then
            assertThat(result).isNull();
        }
    }

    // ========================================
    // resolveForOrganizationPost
    // ========================================

    @Nested
    @DisplayName("resolveForOrganizationPost")
    class ResolveForOrganizationPost {

        @Test
        @DisplayName("正常系: REAL_NAME モードの場合、本名スナップショットを返す")
        void 組織_REAL_NAME_本名返却() {
            // Given
            OrganizationEntity org = mock(OrganizationEntity.class);
            given(org.getSupporterNameDisclosure()).willReturn(NameDisclosureMode.REAL_NAME);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            UserEntity user = mock(UserEntity.class);
            given(user.getLastName()).willReturn("鈴木");
            given(user.getFirstName()).willReturn("花子");
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            // When
            String result = service.resolveForOrganizationPost(ORG_ID, USER_ID);

            // Then
            assertThat(result).isEqualTo("鈴木花子");
        }

        @Test
        @DisplayName("正常系: DISPLAY_NAME モードの場合、null を返す")
        void 組織_DISPLAY_NAME_null返却() {
            // Given
            OrganizationEntity org = mock(OrganizationEntity.class);
            given(org.getSupporterNameDisclosure()).willReturn(NameDisclosureMode.DISPLAY_NAME);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            // When
            String result = service.resolveForOrganizationPost(ORG_ID, USER_ID);

            // Then
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("異常系: 組織が存在しない場合、null を返す")
        void 組織不在_null返却() {
            // Given
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.empty());

            // When
            String result = service.resolveForOrganizationPost(ORG_ID, USER_ID);

            // Then
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("異常系: organizationId が null の場合、null を返す")
        void orgId_null_null返却() {
            // When
            String result = service.resolveForOrganizationPost(null, USER_ID);

            // Then
            assertThat(result).isNull();
        }
    }

    // ========================================
    // resolveForTimelinePost / resolveForEventPost（Phase 3 予定）
    // ========================================

    @Nested
    @DisplayName("resolveForTimelinePost（Phase 3 予定）")
    class ResolveForTimelinePost {

        @Test
        @DisplayName("teamId 指定: チーム経由で snapshot を解決する")
        void タイムライン_teamId_チーム経由() {
            // Given
            TeamEntity team = mock(TeamEntity.class);
            given(team.getSupporterNameDisclosure()).willReturn(NameDisclosureMode.REAL_NAME);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            UserEntity user = mock(UserEntity.class);
            given(user.getLastName()).willReturn("田中");
            given(user.getFirstName()).willReturn("一郎");
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            // When
            String result = service.resolveForTimelinePost(TEAM_ID, null, USER_ID);

            // Then
            assertThat(result).isEqualTo("田中一郎");
        }

        @Test
        @DisplayName("teamId/orgId ともに null の場合、null を返す（個人投稿）")
        void タイムライン_個人投稿_null返却() {
            // When
            String result = service.resolveForTimelinePost(null, null, USER_ID);

            // Then
            assertThat(result).isNull();
        }
    }
}
