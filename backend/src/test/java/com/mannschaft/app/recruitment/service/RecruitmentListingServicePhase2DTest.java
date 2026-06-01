package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingRegionEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRegionRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F22.1 市 Phase2 D: {@link RecruitmentListingService#create} の複数地域募集（N:N）と後方互換を
 * 検証する単体テスト（test-first）。
 *
 * <h2>仕様</h2>
 * <ul>
 *   <li>request.regions 指定 → {@code validateAndNormalizeAll} に渡り、中間表へ replace される</li>
 *   <li>request.regions 未指定 + 単一 prefecture/city 指定 → 1 件として扱う（後方互換）</li>
 *   <li>代表（先頭）を旧単一列に同期（entity.prefectureCode/cityCode）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentListingService Phase2-D 複数地域募集（N:N）")
class RecruitmentListingServicePhase2DTest {

    @Mock
    private RecruitmentListingRepository listingRepository;
    @Mock
    private RecruitmentCategoryRepository categoryRepository;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private NotificationHelper notificationHelper;
    @Mock
    private RecruitmentMapper mapper;
    @Mock
    private MarketRegionValidator marketRegionValidator;
    @Mock
    private MarketFriendTargetService marketFriendTargetService;
    @Mock
    private MarketResponseEnricher marketResponseEnricher;
    @Mock
    private com.mannschaft.app.recruitment.repository.RecruitmentFriendTargetRepository friendTargetRepository;
    @Mock
    private TeamService teamService;
    @Mock
    private RecruitmentListingRegionRepository listingRegionRepository;

    @InjectMocks
    private RecruitmentListingService service;

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final Long CATEGORY_ID = 100L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.now();

    @Test
    @DisplayName("regions 指定 → validateAndNormalizeAll に全ペアが渡り、中間表へ replace される")
    void create_withRegions_validatesAllAndReplaces() {
        given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);
        given(marketRegionValidator.validateAndNormalizeAll(anyList()))
                .willReturn(List.of(
                        new MarketRegionValidator.ResolvedRegion("13", null),
                        new MarketRegionValidator.ResolvedRegion("14", "14100")));
        given(listingRepository.save(any(RecruitmentListingEntity.class)))
                .willAnswer(inv -> withId(inv.getArgument(0), 999L));

        CreateRecruitmentListingRequest request = requestWithRegions(List.of(
                new CreateRecruitmentListingRequest.RegionInput("13", null),
                new CreateRecruitmentListingRequest.RegionInput("14", "14100")));
        service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request);

        // 単一フィールド validator は呼ばれない（regions 優先）。
        verify(marketRegionValidator, never()).validateAndNormalize(any(), any());

        // ペアが全件 validateAndNormalizeAll に渡ること。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketRegionValidator.RegionPair>> pairsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(marketRegionValidator).validateAndNormalizeAll(pairsCaptor.capture());
        assertThat(pairsCaptor.getValue()).hasSize(2);
        assertThat(pairsCaptor.getValue().get(0).prefectureCode()).isEqualTo("13");
        assertThat(pairsCaptor.getValue().get(1).cityCode()).isEqualTo("14100");

        // 中間表は全削除→各地域 save。
        verify(listingRegionRepository).deleteByListingId(any());
        ArgumentCaptor<RecruitmentListingRegionEntity> regionCaptor =
                ArgumentCaptor.forClass(RecruitmentListingRegionEntity.class);
        verify(listingRegionRepository, atLeastOnce()).save(regionCaptor.capture());
        assertThat(regionCaptor.getAllValues())
                .extracting(RecruitmentListingRegionEntity::getPrefectureCode)
                .contains("13", "14");

        // 代表（先頭）= 13 が旧単一列に同期されること。
        ArgumentCaptor<RecruitmentListingEntity> entityCaptor =
                ArgumentCaptor.forClass(RecruitmentListingEntity.class);
        verify(listingRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getPrefectureCode()).isEqualTo("13");
    }

    @Test
    @DisplayName("regions 未指定 + 単一 prefecture/city 指定 → 1 件として後方互換で扱う")
    void create_noRegions_singleFieldBackwardCompat() {
        given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);
        given(marketRegionValidator.validateAndNormalize(eq("27"), eq("27100")))
                .willReturn(new MarketRegionValidator.ResolvedRegion("27", "27100"));
        given(listingRepository.save(any(RecruitmentListingEntity.class)))
                .willAnswer(inv -> withId(inv.getArgument(0), 999L));

        CreateRecruitmentListingRequest request = requestSingle("27", "27100", null);
        service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request);

        // 後方互換経路: 単一 validateAndNormalize が使われ、collection 版は使わない。
        verify(marketRegionValidator).validateAndNormalize("27", "27100");
        verify(marketRegionValidator, never()).validateAndNormalizeAll(anyList());
        // team 既定補完は単一指定があるため発生しない。
        verify(teamService, never()).findRegionCodes(anyLong());

        // 中間表へ 1 件 save。
        verify(listingRegionRepository).deleteByListingId(any());
        ArgumentCaptor<RecruitmentListingRegionEntity> regionCaptor =
                ArgumentCaptor.forClass(RecruitmentListingRegionEntity.class);
        verify(listingRegionRepository).save(regionCaptor.capture());
        assertThat(regionCaptor.getValue().getPrefectureCode()).isEqualTo("27");
        assertThat(regionCaptor.getValue().getCityCode()).isEqualTo("27100");
    }

    @Test
    @DisplayName("地域指定なし（regions/単一とも未指定・team も NULL）→ 中間表は全削除のみ・save なし")
    void create_noRegionAtAll_clearsOnly() {
        given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);
        given(marketRegionValidator.validateAndNormalize(any(), any()))
                .willReturn(new MarketRegionValidator.ResolvedRegion(null, null));
        given(listingRepository.save(any(RecruitmentListingEntity.class)))
                .willAnswer(inv -> withId(inv.getArgument(0), 999L));

        CreateRecruitmentListingRequest request = requestSingle(null, null, null);
        service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request);

        verify(listingRegionRepository).deleteByListingId(any());
        verify(listingRegionRepository, never()).save(any());
    }

    // ========================================
    // ヘルパー
    // ========================================

    private CreateRecruitmentListingRequest requestWithRegions(
            List<CreateRecruitmentListingRequest.RegionInput> regions) {
        return buildRequest(null, null, regions);
    }

    private CreateRecruitmentListingRequest requestSingle(
            String prefectureCode, String cityCode,
            List<CreateRecruitmentListingRequest.RegionInput> regions) {
        return buildRequest(prefectureCode, cityCode, regions);
    }

    private CreateRecruitmentListingRequest buildRequest(
            String prefectureCode, String cityCode,
            List<CreateRecruitmentListingRequest.RegionInput> regions) {
        return new CreateRecruitmentListingRequest(
                CATEGORY_ID, null, "test title", "desc",
                RecruitmentParticipationType.INDIVIDUAL,
                BASE_TIME.plusDays(2),
                BASE_TIME.plusDays(2).plusHours(2),
                BASE_TIME.plusDays(1),
                BASE_TIME.plusDays(1),
                10, 1,
                false, null,
                RecruitmentVisibility.SCOPE_ONLY,
                null, null, null, null,
                prefectureCode, cityCode, null, null, regions);
    }

    /** BaseEntity.id は private 採番のため、テストでは reflection で設定する（DB 採番の代替）。 */
    private static RecruitmentListingEntity withId(RecruitmentListingEntity entity, Long id) {
        try {
            java.lang.reflect.Field f =
                    com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return entity;
    }
}
