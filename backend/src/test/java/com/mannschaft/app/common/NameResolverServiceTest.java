package com.mannschaft.app.common;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link NameResolverService} の単体テスト。
 * ユーザー表示名・チーム名・組織名のバッチ解決およびスコープ名解決を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NameResolverService 単体テスト")
class NameResolverServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private NameResolverService nameResolverService;

    // ========================================
    // テスト用ヘルパー
    // ========================================

    private UserEntity createUser(Long id, String displayName) {
        UserEntity user = UserEntity.builder()
                .email("user" + id + "@example.com")
                .passwordHash("hash")
                .lastName("姓")
                .firstName("名")
                .displayName(displayName)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private TeamEntity createTeam(Long id, String name) {
        TeamEntity team = TeamEntity.builder()
                .name(name)
                .build();
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    private OrganizationEntity createOrganization(Long id, String name) {
        OrganizationEntity org = OrganizationEntity.builder()
                .name(name)
                .build();
        ReflectionTestUtils.setField(org, "id", id);
        return org;
    }

    // ========================================
    // resolveUserDisplayNames
    // ========================================

    @Nested
    @DisplayName("resolveUserDisplayNames")
    class ResolveUserDisplayNames {

        @Test
        @DisplayName("正常系: 複数ユーザーの表示名マップが返る")
        void resolveUserDisplayNames_複数ユーザー_マップが返る() {
            // Given
            Set<Long> userIds = Set.of(1L, 2L);
            UserEntity user1 = createUser(1L, "yamada");
            UserEntity user2 = createUser(2L, "tanaka");
            given(userRepository.findAllById(userIds)).willReturn(List.of(user1, user2));

            // When
            Map<Long, String> result = nameResolverService.resolveUserDisplayNames(userIds);

            // Then
            assertThat(result).hasSize(2);
            assertThat(result.get(1L)).isEqualTo("yamada");
            assertThat(result.get(2L)).isEqualTo("tanaka");
        }

        @Test
        @DisplayName("境界値: null入力で空マップ")
        void resolveUserDisplayNames_null入力_空マップ() {
            // When
            Map<Long, String> result = nameResolverService.resolveUserDisplayNames(null);

            // Then
            assertThat(result).isEmpty();
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("境界値: 空コレクション入力で空マップ")
        void resolveUserDisplayNames_空コレクション_空マップ() {
            // When
            Map<Long, String> result = nameResolverService.resolveUserDisplayNames(Collections.emptySet());

            // Then
            assertThat(result).isEmpty();
            verifyNoInteractions(userRepository);
        }
    }

    // ========================================
    // resolveUserDisplayName
    // ========================================

    @Nested
    @DisplayName("resolveUserDisplayName")
    class ResolveUserDisplayName {

        @Test
        @DisplayName("正常系: ユーザーが見つかった場合は表示名を返す")
        void resolveUserDisplayName_ユーザー存在_表示名を返す() {
            // Given
            UserEntity user = createUser(1L, "yamada");
            given(userRepository.findById(1L)).willReturn(Optional.of(user));

            // When
            String result = nameResolverService.resolveUserDisplayName(1L);

            // Then
            assertThat(result).isEqualTo("yamada");
        }

        @Test
        @DisplayName("正常系: ユーザーが見つからない場合は不明なユーザー")
        void resolveUserDisplayName_ユーザー不在_不明なユーザー() {
            // Given
            given(userRepository.findById(999L)).willReturn(Optional.empty());

            // When
            String result = nameResolverService.resolveUserDisplayName(999L);

            // Then
            assertThat(result).isEqualTo("不明なユーザー");
        }

        @Test
        @DisplayName("境界値: null入力で不明なユーザー")
        void resolveUserDisplayName_null入力_不明なユーザー() {
            // When
            String result = nameResolverService.resolveUserDisplayName(null);

            // Then
            assertThat(result).isEqualTo("不明なユーザー");
            verifyNoInteractions(userRepository);
        }
    }

    // ========================================
    // resolveTeamNames
    // ========================================

    @Nested
    @DisplayName("resolveTeamNames")
    class ResolveTeamNames {

        @Test
        @DisplayName("正常系: 複数チームの名前マップが返る")
        void resolveTeamNames_複数チーム_マップが返る() {
            // Given
            Set<Long> teamIds = Set.of(1L, 2L);
            TeamEntity team1 = createTeam(1L, "チームA");
            TeamEntity team2 = createTeam(2L, "チームB");
            given(teamRepository.findAllById(teamIds)).willReturn(List.of(team1, team2));

            // When
            Map<Long, String> result = nameResolverService.resolveTeamNames(teamIds);

            // Then
            assertThat(result).hasSize(2);
            assertThat(result.get(1L)).isEqualTo("チームA");
            assertThat(result.get(2L)).isEqualTo("チームB");
        }

        @Test
        @DisplayName("境界値: null入力で空マップ")
        void resolveTeamNames_null入力_空マップ() {
            // When
            Map<Long, String> result = nameResolverService.resolveTeamNames(null);

            // Then
            assertThat(result).isEmpty();
            verifyNoInteractions(teamRepository);
        }

        @Test
        @DisplayName("境界値: 空コレクション入力で空マップ")
        void resolveTeamNames_空コレクション_空マップ() {
            // When
            Map<Long, String> result = nameResolverService.resolveTeamNames(Collections.emptySet());

            // Then
            assertThat(result).isEmpty();
            verifyNoInteractions(teamRepository);
        }
    }

    // ========================================
    // resolveOrganizationNames
    // ========================================

    @Nested
    @DisplayName("resolveOrganizationNames")
    class ResolveOrganizationNames {

        @Test
        @DisplayName("正常系: 複数組織の名前マップが返る")
        void resolveOrganizationNames_複数組織_マップが返る() {
            // Given
            Set<Long> orgIds = Set.of(1L, 2L);
            OrganizationEntity org1 = createOrganization(1L, "組織A");
            OrganizationEntity org2 = createOrganization(2L, "組織B");
            given(organizationRepository.findAllById(orgIds)).willReturn(List.of(org1, org2));

            // When
            Map<Long, String> result = nameResolverService.resolveOrganizationNames(orgIds);

            // Then
            assertThat(result).hasSize(2);
            assertThat(result.get(1L)).isEqualTo("組織A");
            assertThat(result.get(2L)).isEqualTo("組織B");
        }

        @Test
        @DisplayName("境界値: null入力で空マップ")
        void resolveOrganizationNames_null入力_空マップ() {
            // When
            Map<Long, String> result = nameResolverService.resolveOrganizationNames(null);

            // Then
            assertThat(result).isEmpty();
            verifyNoInteractions(organizationRepository);
        }

        @Test
        @DisplayName("境界値: 空コレクション入力で空マップ")
        void resolveOrganizationNames_空コレクション_空マップ() {
            // When
            Map<Long, String> result = nameResolverService.resolveOrganizationNames(Collections.emptySet());

            // Then
            assertThat(result).isEmpty();
            verifyNoInteractions(organizationRepository);
        }
    }

    // ========================================
    // resolveScopeName
    // ========================================

    @Nested
    @DisplayName("resolveScopeName")
    class ResolveScopeName {

        @Test
        @DisplayName("正常系: TEAMスコープでチーム名を返す")
        void resolveScopeName_TEAMスコープ_チーム名を返す() {
            // Given
            TeamEntity team = createTeam(1L, "チームA");
            given(teamRepository.findById(1L)).willReturn(Optional.of(team));

            // When
            String result = nameResolverService.resolveScopeName("TEAM", 1L);

            // Then
            assertThat(result).isEqualTo("チームA");
        }

        @Test
        @DisplayName("正常系: ORGANIZATIONスコープで組織名を返す")
        void resolveScopeName_ORGANIZATIONスコープ_組織名を返す() {
            // Given
            OrganizationEntity org = createOrganization(1L, "組織A");
            given(organizationRepository.findById(1L)).willReturn(Optional.of(org));

            // When
            String result = nameResolverService.resolveScopeName("ORGANIZATION", 1L);

            // Then
            assertThat(result).isEqualTo("組織A");
        }

        @Test
        @DisplayName("正常系: PERSONALスコープで個人を返す")
        void resolveScopeName_PERSONALスコープ_個人を返す() {
            // When
            String result = nameResolverService.resolveScopeName("PERSONAL", 1L);

            // Then
            assertThat(result).isEqualTo("個人");
        }

        @Test
        @DisplayName("正常系: 小文字のscopeTypeでも正しく判定される")
        void resolveScopeName_小文字scopeType_正しく判定() {
            // Given
            TeamEntity team = createTeam(1L, "チームA");
            given(teamRepository.findById(1L)).willReturn(Optional.of(team));

            // When
            String result = nameResolverService.resolveScopeName("team", 1L);

            // Then
            assertThat(result).isEqualTo("チームA");
        }

        @Test
        @DisplayName("正常系: TEAMが見つからない場合は不明なチーム")
        void resolveScopeName_チーム不在_不明なチーム() {
            // Given
            given(teamRepository.findById(999L)).willReturn(Optional.empty());

            // When
            String result = nameResolverService.resolveScopeName("TEAM", 999L);

            // Then
            assertThat(result).isEqualTo("不明なチーム");
        }

        @Test
        @DisplayName("正常系: ORGANIZATIONが見つからない場合は不明な組織")
        void resolveScopeName_組織不在_不明な組織() {
            // Given
            given(organizationRepository.findById(999L)).willReturn(Optional.empty());

            // When
            String result = nameResolverService.resolveScopeName("ORGANIZATION", 999L);

            // Then
            assertThat(result).isEqualTo("不明な組織");
        }

        @Test
        @DisplayName("境界値: 未知のscopeTypeで不明なスコープ")
        void resolveScopeName_未知のscopeType_不明なスコープ() {
            // When
            String result = nameResolverService.resolveScopeName("UNKNOWN", 1L);

            // Then
            assertThat(result).isEqualTo("不明なスコープ");
        }

        @Test
        @DisplayName("境界値: scopeTypeがnullで不明なスコープ")
        void resolveScopeName_scopeTypeがnull_不明なスコープ() {
            // When
            String result = nameResolverService.resolveScopeName(null, 1L);

            // Then
            assertThat(result).isEqualTo("不明なスコープ");
        }

        @Test
        @DisplayName("境界値: scopeIdがnullで不明なスコープ")
        void resolveScopeName_scopeIdがnull_不明なスコープ() {
            // When
            String result = nameResolverService.resolveScopeName("TEAM", null);

            // Then
            assertThat(result).isEqualTo("不明なスコープ");
        }
    }
}
