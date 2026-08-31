package com.mannschaft.app.market.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.market.dto.MarketListingResponse;
import com.mannschaft.app.matching.repository.CityRepository;
import com.mannschaft.app.matching.repository.PrefectureRepository;
import com.mannschaft.app.matching.service.RegionTranslationService;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.visibility.RecruitmentListingVisibilityResolver;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRegionRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link MarketQueryService} 単体テスト（画像 404 根治 Phase3）。
 *
 * <p>公開札の主催（owner）アイコンが生 R2 キーでなく、{@link MediaUrlResolver} 解決後の
 * 署名付き表示 URL として返ることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarketQueryService 単体テスト")
class MarketQueryServiceTest {

    @Mock
    private RecruitmentListingRepository listingRepository;
    @Mock
    private RecruitmentListingVisibilityResolver listingVisibilityResolver;
    @Mock
    private RecruitmentListingRegionRepository listingRegionRepository;
    @Mock
    private RecruitmentCategoryRepository categoryRepository;
    @Mock
    private PrefectureRepository prefectureRepository;
    @Mock
    private CityRepository cityRepository;
    @Mock
    private TeamRepository teamRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private RegionTranslationService regionTranslationService;
    @Mock
    private MediaUrlResolver mediaUrlResolver;
    @Mock
    private UserService userService;
    @Mock
    private RoleService roleService;

    @InjectMocks
    private MarketQueryService service;

    @Test
    @DisplayName("getListing: owner.iconUrl は生 R2 キーでなく署名付き表示 URL を返す")
    void getListing_resolvesOwnerIconUrl() {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(1L)
                .title("11/3 練習試合の相手募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(LocalDateTime.now().plusDays(7))
                .applicationDeadline(LocalDateTime.now().plusDays(5))
                .capacity(10)
                .status(RecruitmentListingStatus.OPEN)
                .build();
        ReflectionTestUtils.setField(listing, "id", 100L);

        TeamEntity team = TeamEntity.builder()
                .name("別府FC")
                .iconUrl("team/1/icon/raw.png")
                .build();
        ReflectionTestUtils.setField(team, "id", 1L);

        given(listingRepository.findPublicMarketListingById(100L))
                .willReturn(Optional.of(listing));
        given(teamRepository.findAllById(any())).willReturn(List.of(team));
        given(mediaUrlResolver.resolve("team/1/icon/raw.png"))
                .willReturn("https://cdn.example/signed/team-1");

        MarketListingResponse res = service.getListing(100L);

        assertThat(res.getOwner().getScopeType()).isEqualTo("TEAM");
        assertThat(res.getOwner().getDisplayName()).isEqualTo("別府FC");
        assertThat(res.getOwner().getIconUrl()).isEqualTo("https://cdn.example/signed/team-1");
        assertThat(res.getOwner().getIconUrl()).isNotEqualTo("team/1/icon/raw.png");
    }

    @Test
    @DisplayName("PERSONAL owner: 所属外には公開表示名だけを返し、内部IDをJSONへ出さない")
    void getListing_personalOwnerExternalViewerReturnsDisplayNameWithoutInternalId() throws Exception {
        RecruitmentListingEntity listing = personalListing();
        given(listingRepository.findPublicMarketListingById(100L)).willReturn(Optional.of(listing));
        given(userService.getActiveMarketOwnerIdentities(Set.of(7L))).willReturn(Map.of(
                7L, new UserService.MarketOwnerIdentity(
                        7L, "市場ニックネーム", "秘匿 姓名", "https://cdn.example/avatar", false, true)));
        given(roleService.findUserIdsSharingAffiliation(null, Set.of(7L))).willReturn(Set.of());

        MarketListingResponse response = service.getListing(100L, null, null);

        assertThat(response.getOwner().getDisplayName()).isEqualTo("市場ニックネーム");
        String ownerJson = new ObjectMapper().writeValueAsString(response.getOwner());
        var ownerNode = new ObjectMapper().readTree(ownerJson);
        assertThat(ownerNode.size()).isEqualTo(3);
        assertThat(ownerNode.has("scopeType")).isTrue();
        assertThat(ownerNode.has("displayName")).isTrue();
        assertThat(ownerNode.has("iconUrl")).isTrue();
        assertThat(ownerJson).doesNotContain("scopeId", "userId", "秘匿 姓名");
    }

    @Test
    @DisplayName("PERSONAL owner: activeな共通所属がある成人には実名を返す")
    void getListing_personalOwnerSharedAffiliationReturnsRealName() {
        RecruitmentListingEntity listing = personalListing();
        given(listingVisibilityResolver.findAccessibleSelectedListingIds(9L)).willReturn(List.of(100L));
        given(listingRepository.findAccessibleMarketListingById(100L, Set.of(100L)))
                .willReturn(Optional.of(listing));
        given(userService.getActiveMarketOwnerIdentities(Set.of(7L))).willReturn(Map.of(
                7L, new UserService.MarketOwnerIdentity(
                        7L, "市場ニックネーム", "共有 花子", null, false, true)));
        given(roleService.findUserIdsSharingAffiliation(9L, Set.of(7L))).willReturn(Set.of(7L));

        MarketListingResponse response = service.getListing(100L, null, 9L);

        assertThat(response.getOwner().getDisplayName()).isEqualTo("共有 花子");
        assertThat(response.getOwner().getScopeId()).isNull();
    }

    @Test
    @DisplayName("PERSONAL owner: 未成年は共通所属があっても実名を返さない")
    void getListing_personalMinorNeverReturnsRealName() {
        RecruitmentListingEntity listing = personalListing();
        given(listingVisibilityResolver.findAccessibleSelectedListingIds(9L)).willReturn(List.of(100L));
        given(listingRepository.findAccessibleMarketListingById(100L, Set.of(100L)))
                .willReturn(Optional.of(listing));
        given(userService.getActiveMarketOwnerIdentities(Set.of(7L))).willReturn(Map.of(
                7L, new UserService.MarketOwnerIdentity(
                        7L, "未成年ニックネーム", "秘匿 未成年", null, true, true)));
        given(roleService.findUserIdsSharingAffiliation(9L, Set.of(7L))).willReturn(Set.of(7L));

        MarketListingResponse response = service.getListing(100L, null, 9L);

        assertThat(response.getOwner().getDisplayName()).isEqualTo("未成年ニックネーム");
    }

    @Test
    @DisplayName("認証済み市フィードは現在の選択公開先に属するPERSONAL札を含める")
    void searchListings_authenticatedViewerIncludesAccessibleSelectedScopeListing() {
        RecruitmentListingEntity listing = personalListing();
        ReflectionTestUtils.setField(listing, "visibility", RecruitmentVisibility.SELECTED_SCOPES);
        PageRequest pageable = PageRequest.of(0, 20);
        given(listingVisibilityResolver.findAccessibleSelectedListingIds(9L)).willReturn(List.of(100L));
        given(listingRepository.searchAccessibleMarketListings(
                Set.of(100L), null, null, null, null, null, true, pageable))
                .willReturn(new PageImpl<>(List.of(listing), pageable, 1));
        given(userService.getActiveMarketOwnerIdentities(Set.of(7L))).willReturn(Map.of(
                7L, new UserService.MarketOwnerIdentity(
                        7L, "市場ニックネーム", "共有 花子", null, false, false)));
        given(roleService.findUserIdsSharingAffiliation(9L, Set.of(7L))).willReturn(Set.of(7L));

        var result = service.searchListings(null, null, null, null, true, pageable, null, 9L);

        assertThat(result.getContent()).singleElement()
                .satisfies(item -> assertThat(item.getOwner().getDisplayName()).isEqualTo("共有 花子"));
        verify(listingRepository).searchAccessibleMarketListings(
                Set.of(100L), null, null, null, null, null, true, pageable);
    }

    @Test
    @DisplayName("市の札主区分フィルターをリポジトリへ渡す")
    void searchListings_passesOwnerTypeFilter() {
        PageRequest pageable = PageRequest.of(0, 20);
        given(listingRepository.searchMarketListings(
                null, null, null, RecruitmentScopeType.TEAM, null, true, pageable))
                .willReturn(Page.empty(pageable));

        service.searchListings(
                null, null, null, RecruitmentScopeType.TEAM,
                null, true, pageable, null, null);

        verify(listingRepository).searchMarketListings(
                null, null, null, RecruitmentScopeType.TEAM, null, true, pageable);
    }

    @Test
    @DisplayName("認証済みの市検索でも札主区分フィルターをリポジトリへ渡す")
    void searchListings_authenticatedPassesOwnerTypeFilter() {
        PageRequest pageable = PageRequest.of(0, 20);
        given(listingVisibilityResolver.findAccessibleSelectedListingIds(9L)).willReturn(List.of());
        given(listingRepository.searchAccessibleMarketListings(
                Set.of(-1L), null, null, null, RecruitmentScopeType.ORGANIZATION,
                null, true, pageable))
                .willReturn(Page.empty(pageable));

        service.searchListings(
                null, null, null, RecruitmentScopeType.ORGANIZATION,
                null, true, pageable, null, 9L);

        verify(listingRepository).searchAccessibleMarketListings(
                Set.of(-1L), null, null, null, RecruitmentScopeType.ORGANIZATION,
                null, true, pageable);
    }

    private RecruitmentListingEntity personalListing() {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.PERSONAL)
                .scopeId(7L)
                .title("個人札")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(LocalDateTime.now().plusDays(7))
                .applicationDeadline(LocalDateTime.now().plusDays(5))
                .capacity(1)
                .visibility(RecruitmentVisibility.PUBLIC)
                .status(RecruitmentListingStatus.OPEN)
                .build();
        ReflectionTestUtils.setField(listing, "id", 100L);
        return listing;
    }
}
