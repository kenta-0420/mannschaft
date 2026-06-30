package com.mannschaft.app.market.service;

import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.market.dto.MarketListingResponse;
import com.mannschaft.app.matching.repository.CityRepository;
import com.mannschaft.app.matching.repository.PrefectureRepository;
import com.mannschaft.app.matching.service.RegionTranslationService;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRegionRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

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
}
