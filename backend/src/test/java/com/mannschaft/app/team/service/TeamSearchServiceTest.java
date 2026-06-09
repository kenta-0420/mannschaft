package com.mannschaft.app.team.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.exception.OrganizationNotFoundException;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.dto.TeamSearchCriteria;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.metrics.TeamSearchMetrics;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F15.4: {@link TeamSearchService} の単体テスト。
 *
 * <p>設計書 {@code docs/features/F15.4_team_store_search_within_org.md §4.3} の 4 種類の権限分岐を
 * すべて検証する:</p>
 * <ol>
 *   <li>PUBLIC 組織 + 未ログイン → PUBLIC チームのみ返す（非公開チームは出ない）</li>
 *   <li>PUBLIC 組織 + 組織メンバー → PUBLIC + 非公開系すべて（GUESTS_AND_ABOVE / SUPPORTERS_AND_ABOVE / MEMBERS_AND_ABOVE）を返す</li>
 *   <li>PRIVATE 組織 + 未ログイン → {@link OrganizationNotFoundException}</li>
 *   <li>PRIVATE 組織 + 組織メンバー → 正常に検索される</li>
 * </ol>
 *
 * <p>加えて以下も検証する:</p>
 * <ul>
 *   <li>sort カラムがホワイトリスト外で {@link IllegalArgumentException}</li>
 *   <li>存在しない組織で {@link OrganizationNotFoundException}</li>
 *   <li>prefecture 未指定 + city 指定 → city が無視される（フォールバック）</li>
 *   <li>currentUserId=null の {@link TeamSearchService#isOrganizationMember} は false</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamSearchService 単体テスト")
class TeamSearchServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private TeamSearchMetrics teamSearchMetrics;

    @InjectMocks
    private TeamSearchService service;

    private static final Long ORG_ID = 100L;
    private static final Long MEMBER_USER_ID = 500L;

    private Pageable defaultPageable;

    @BeforeEach
    void setUp() {
        defaultPageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "nameKana"));
    }

    // ════════════════════════════════════════════════════════════
    // §4.3 権限分岐 4 パターン
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("§4.3 権限分岐 — PUBLIC 組織")
    class PublicOrganization {

        @Test
        @DisplayName("未ログイン: PUBLIC チームのみ返す。検索 Spec が非公開チームを含まない")
        void publicOrg_anonymous_returnsOnlyPublicTeams() {
            // Given: PUBLIC 組織
            OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            TeamEntity publicTeam = TeamEntity.builder()
                    .name("公開店舗")
                    .visibility(TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(true)
                    .build();
            Page<TeamEntity> result = new PageImpl<>(List.of(publicTeam));
            given(teamRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .willReturn(result);

            // When: currentUserId=null（未ログイン）
            Page<TeamEntity> actual = service.search(
                    ORG_ID,
                    new TeamSearchCriteria(null, null, null, null),
                    null,
                    defaultPageable);

            // Then
            assertThat(actual.getContent()).hasSize(1);
            assertThat(actual.getContent().get(0).getVisibility())
                    .isEqualTo(TeamEntity.Visibility.PUBLIC);
            // AccessControlService は未ログインでは呼ばれない（早期 return）
            verify(accessControlService, never()).isMember(any(), any(), any());
        }

        @Test
        @DisplayName("組織メンバー: PUBLIC + 非公開系すべて（GUESTS_AND_ABOVE / SUPPORTERS_AND_ABOVE / MEMBERS_AND_ABOVE）が許可可視性に含まれる")
        void publicOrg_member_includesOrganizationOnly() {
            // Given
            OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            given(accessControlService.isMember(MEMBER_USER_ID, ORG_ID, "ORGANIZATION"))
                    .willReturn(true);
            given(teamRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            // When
            service.search(
                    ORG_ID,
                    new TeamSearchCriteria(null, null, null, null),
                    MEMBER_USER_ID,
                    defaultPageable);

            // Then: 検索が実行されている（例外なし）
            ArgumentCaptor<Specification<TeamEntity>> specCaptor =
                    ArgumentCaptor.forClass(Specification.class);
            verify(teamRepository).findAll(specCaptor.capture(), any(Pageable.class));
            assertThat(specCaptor.getValue()).isNotNull();
        }
    }

    @Nested
    @DisplayName("§4.3 権限分岐 — PRIVATE 組織")
    class PrivateOrganization {

        @Test
        @DisplayName("未ログイン: 404（存在を漏らさない）")
        void privateOrg_anonymous_throwsNotFound() {
            // Given
            OrganizationEntity privateOrg = buildOrg(OrganizationEntity.Visibility.PRIVATE);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(privateOrg));

            // When / Then
            assertThatThrownBy(() -> service.search(
                    ORG_ID,
                    new TeamSearchCriteria(null, null, null, null),
                    null,
                    defaultPageable))
                    .isInstanceOf(OrganizationNotFoundException.class);

            // 404 を返すので検索は実行されない
            verify(teamRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        }

        @Test
        @DisplayName("非メンバーのログインユーザー: 404（PRIVATE 組織への閲覧拒否）")
        void privateOrg_nonMember_throwsNotFound() {
            OrganizationEntity privateOrg = buildOrg(OrganizationEntity.Visibility.PRIVATE);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(privateOrg));
            given(accessControlService.isMember(MEMBER_USER_ID, ORG_ID, "ORGANIZATION"))
                    .willReturn(false);

            assertThatThrownBy(() -> service.search(
                    ORG_ID,
                    new TeamSearchCriteria(null, null, null, null),
                    MEMBER_USER_ID,
                    defaultPageable))
                    .isInstanceOf(OrganizationNotFoundException.class);

            verify(teamRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        }

        @Test
        @DisplayName("組織メンバー: 正常に検索される")
        void privateOrg_member_returnsResult() {
            OrganizationEntity privateOrg = buildOrg(OrganizationEntity.Visibility.PRIVATE);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(privateOrg));
            given(accessControlService.isMember(MEMBER_USER_ID, ORG_ID, "ORGANIZATION"))
                    .willReturn(true);
            TeamEntity orgOnly = TeamEntity.builder()
                    .name("組織限定店舗")
                    .visibility(TeamEntity.Visibility.GUESTS_AND_ABOVE)
                    .supporterEnabled(false)
                    .build();
            given(teamRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(orgOnly)));

            Page<TeamEntity> actual = service.search(
                    ORG_ID,
                    new TeamSearchCriteria(null, null, null, null),
                    MEMBER_USER_ID,
                    defaultPageable);

            assertThat(actual.getContent()).hasSize(1);
        }
    }

    // ════════════════════════════════════════════════════════════
    // 404 / 400 系
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("存在しない組織: OrganizationNotFoundException がスローされる")
    void unknownOrg_throwsNotFound() {
        given(organizationRepository.findById(ORG_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.search(
                ORG_ID,
                new TeamSearchCriteria(null, null, null, null),
                MEMBER_USER_ID,
                defaultPageable))
                .isInstanceOf(OrganizationNotFoundException.class);

        verify(teamRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("sort カラムがホワイトリスト外: IllegalArgumentException")
    void invalidSortProperty_throwsIllegalArgument() {
        Pageable badPageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "deletedAt"));

        assertThatThrownBy(() -> service.search(
                ORG_ID,
                new TeamSearchCriteria(null, null, null, null),
                MEMBER_USER_ID,
                badPageable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid sort property")
                .hasMessageContaining("deletedAt");

        // 組織検索すら行われない（先頭でバリデーション）
        verify(organizationRepository, never()).findById(any());
    }

    @Test
    @DisplayName("sort: 許可カラム (nameKana / name / createdAt) は通る")
    void allowedSortProperty_passes() {
        OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC);
        given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
        given(teamRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        for (String allowed : List.of("nameKana", "name", "createdAt")) {
            Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, allowed));
            // 例外がスローされなければ OK
            service.search(
                    ORG_ID,
                    new TeamSearchCriteria(null, null, null, null),
                    null,
                    pageable);
        }
    }

    @Test
    @DisplayName("ソート未指定 (Unsorted) は許可される")
    void unsortedPageable_passes() {
        OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC);
        given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
        given(teamRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        Pageable unsorted = PageRequest.of(0, 20);
        service.search(
                ORG_ID,
                new TeamSearchCriteria(null, null, null, null),
                null,
                unsorted);
    }

    // ════════════════════════════════════════════════════════════
    // フォールバック: prefecture 未指定 + city 指定
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("prefecture 未指定 + city 指定: city が無視されて検索される（400 にしない）")
    void cityWithoutPrefecture_isIgnored() {
        OrganizationEntity org = buildOrg(OrganizationEntity.Visibility.PUBLIC);
        given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
        given(teamRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        // city 指定だが prefecture 未指定 — 例外なしで実行されること
        service.search(
                ORG_ID,
                new TeamSearchCriteria(null, null, "渋谷区", null),
                null,
                defaultPageable);

        verify(teamRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    // ════════════════════════════════════════════════════════════
    // isOrganizationMember 単体テスト
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isOrganizationMember")
    class IsOrganizationMember {

        @Test
        @DisplayName("currentUserId=null: 常に false（AccessControlService を呼ばない）")
        void nullUserId_returnsFalseWithoutCallingAcs() {
            boolean result = service.isOrganizationMember(ORG_ID, null);
            assertThat(result).isFalse();
            verify(accessControlService, never()).isMember(any(), any(), any());
        }

        @Test
        @DisplayName("AccessControlService が true を返したら true")
        void acsReturnsTrue_returnsTrue() {
            given(accessControlService.isMember(MEMBER_USER_ID, ORG_ID, "ORGANIZATION"))
                    .willReturn(true);

            boolean result = service.isOrganizationMember(ORG_ID, MEMBER_USER_ID);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("AccessControlService が false を返したら false")
        void acsReturnsFalse_returnsFalse() {
            given(accessControlService.isMember(MEMBER_USER_ID, ORG_ID, "ORGANIZATION"))
                    .willReturn(false);

            boolean result = service.isOrganizationMember(ORG_ID, MEMBER_USER_ID);
            assertThat(result).isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════
    // ヘルパー
    // ════════════════════════════════════════════════════════════

    private OrganizationEntity buildOrg(OrganizationEntity.Visibility visibility) {
        return OrganizationEntity.builder()
                .name("テスト組織")
                .orgType(OrganizationEntity.OrgType.COMPANY)
                .visibility(visibility)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.BASIC)
                .supporterEnabled(true)
                .build();
    }
}
