package com.mannschaft.app.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.dto.SlugResolveResponse;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.entity.OrganizationSlugHistoryEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.repository.OrganizationSlugHistoryRepository;
import com.mannschaft.app.organization.service.OrganizationHierarchyService;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
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
 * F01.2 §5.9.5 組織 slug リネーム / 301 解決の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationService slug リネーム / 301 解決 単体テスト")
class OrganizationSlugRenameResolveServiceTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationSlugHistoryRepository organizationSlugHistoryRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private InviteTokenRepository inviteTokenRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private OrganizationMembershipService organizationMembershipService;
    @Mock private OrganizationHierarchyService organizationHierarchyService;
    @Mock private MembershipService membershipService;
    @Mock private MediaUrlResolver mediaUrlResolver;
    @InjectMocks private OrganizationService service;

    private static final Long ORG_ID = 20L;

    private OrganizationEntity orgWithSlug(String slug) {
        return OrganizationEntity.builder()
                .name("テスト組織")
                .slug(slug)
                .orgType(OrganizationEntity.OrgType.SCHOOL)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .version(0L)
                .build();
    }

    private void givenCounts() {
        lenient().when(userRoleRepository.countByOrganizationId(any())).thenReturn(1L);
    }

    @Nested
    @DisplayName("renameSlug")
    class Rename {

        @Test
        @DisplayName("正常系: 履歴INSERT＋slug更新")
        void 正常リネーム() {
            givenCounts();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(orgWithSlug("old-org")));
            given(organizationRepository.existsBySlugAndDeletedAtIsNull("new-org")).willReturn(false);
            given(organizationSlugHistoryRepository.existsByOldSlugAndOrganizationIdNot("new-org", ORG_ID))
                    .willReturn(false);

            var result = service.renameSlug(ORG_ID, "new-org");

            assertThat(result.getData().getSlug()).isEqualTo("new-org");
            verify(organizationSlugHistoryRepository).save(any(OrganizationSlugHistoryEntity.class));
            verify(organizationRepository).save(any(OrganizationEntity.class));
        }

        @Test
        @DisplayName("no-op: 現slugと同一なら履歴を書かず200")
        void noop() {
            givenCounts();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(orgWithSlug("same-org")));

            var result = service.renameSlug(ORG_ID, "same-org");

            assertThat(result.getData().getSlug()).isEqualTo("same-org");
            verify(organizationSlugHistoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("形式不正は ORG_060")
        void 形式不正() {
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(orgWithSlug("old-org")));

            assertThatThrownBy(() -> service.renameSlug(ORG_ID, "Bad_Org"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_060"));
        }

        @Test
        @DisplayName("予約語は ORG_061")
        void 予約語() {
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(orgWithSlug("old-org")));

            assertThatThrownBy(() -> service.renameSlug(ORG_ID, "settings"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_061"));
        }

        @Test
        @DisplayName("既存slug重複は ORG_062")
        void 重複() {
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(orgWithSlug("old-org")));
            given(organizationRepository.existsBySlugAndDeletedAtIsNull("taken-org")).willReturn(true);

            assertThatThrownBy(() -> service.renameSlug(ORG_ID, "taken-org"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_062"));
        }

        @Test
        @DisplayName("他組織の履歴予約済みは ORG_063（SLUG_RETIRED）")
        void 履歴予約() {
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(orgWithSlug("old-org")));
            given(organizationRepository.existsBySlugAndDeletedAtIsNull("retired-org")).willReturn(false);
            given(organizationSlugHistoryRepository.existsByOldSlugAndOrganizationIdNot("retired-org", ORG_ID))
                    .willReturn(true);

            assertThatThrownBy(() -> service.renameSlug(ORG_ID, "retired-org"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_063"));
        }

        @Test
        @DisplayName("自組織の過去slugへの戻しは許可")
        void 自組織戻し許可() {
            givenCounts();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(orgWithSlug("current-org")));
            given(organizationRepository.existsBySlugAndDeletedAtIsNull("former-own-org")).willReturn(false);
            given(organizationSlugHistoryRepository.existsByOldSlugAndOrganizationIdNot("former-own-org", ORG_ID))
                    .willReturn(false);

            var result = service.renameSlug(ORG_ID, "former-own-org");

            assertThat(result.getData().getSlug()).isEqualTo("former-own-org");
            verify(organizationSlugHistoryRepository).save(any(OrganizationSlugHistoryEntity.class));
        }
    }

    @Nested
    @DisplayName("resolveSlug")
    class Resolve {

        @Test
        @DisplayName("現slugで存在すれば CURRENT")
        void current() {
            given(organizationRepository.existsBySlugAndDeletedAtIsNull("alive-org")).willReturn(true);

            var res = service.resolveSlug("alive-org");

            assertThat(res.status()).isEqualTo(SlugResolveResponse.Status.CURRENT);
        }

        @Test
        @DisplayName("旧slugは MOVED→現slugを canonicalSlug で返す")
        void moved() {
            given(organizationRepository.existsBySlugAndDeletedAtIsNull("old-org-name")).willReturn(false);
            OrganizationSlugHistoryEntity history = OrganizationSlugHistoryEntity.builder()
                    .organizationId(ORG_ID).oldSlug("old-org-name").build();
            given(organizationSlugHistoryRepository.findByOldSlug("old-org-name")).willReturn(Optional.of(history));
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(orgWithSlug("current-org-name")));

            var res = service.resolveSlug("old-org-name");

            assertThat(res.status()).isEqualTo(SlugResolveResponse.Status.MOVED);
            assertThat(res.canonicalSlug()).isEqualTo("current-org-name");
        }

        @Test
        @DisplayName("現slugにも履歴にも無ければ NOT_FOUND")
        void notFound() {
            given(organizationRepository.existsBySlugAndDeletedAtIsNull("ghost-org")).willReturn(false);
            given(organizationSlugHistoryRepository.findByOldSlug("ghost-org")).willReturn(Optional.empty());

            var res = service.resolveSlug("ghost-org");

            assertThat(res.status()).isEqualTo(SlugResolveResponse.Status.NOT_FOUND);
        }

        @Test
        @DisplayName("多段リネーム後も最新canonicalへ（履歴の各旧slugが現slugへ解決）")
        void 多段リネーム() {
            // a -> b -> c とリネームした後、最古の "a" でアクセスしても現行 "c" に解決される。
            // （履歴に a, b の両方が org_id=ORG_ID で残り、現 slug は c）
            given(organizationRepository.existsBySlugAndDeletedAtIsNull("a")).willReturn(false);
            OrganizationSlugHistoryEntity historyA = OrganizationSlugHistoryEntity.builder()
                    .organizationId(ORG_ID).oldSlug("a").build();
            given(organizationSlugHistoryRepository.findByOldSlug("a")).willReturn(Optional.of(historyA));
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(orgWithSlug("c")));

            var res = service.resolveSlug("a");

            assertThat(res.status()).isEqualTo(SlugResolveResponse.Status.MOVED);
            assertThat(res.canonicalSlug()).isEqualTo("c");
        }
    }
}
