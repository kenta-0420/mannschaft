package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F22.1 市 Phase 2 足場C 第二陣: {@link RecruitmentListingService#create} の
 * 「札立て地域の team 既定補完」を検証する単体テスト。
 *
 * <h2>仕様（dual-support / 既定補完）</h2>
 * <ul>
 *   <li>scope=TEAM かつ request の prefectureCode/cityCode 未指定 → team の地域コードで補完</li>
 *   <li>request 指定がある場合は team を参照せず request を優先（上書き可）</li>
 *   <li>team 側の地域コードが NULL → 補完されず従来どおり「地域なし札」</li>
 *   <li>scope=ORGANIZATION → 補完対象外（team を参照しない）</li>
 * </ul>
 *
 * <p>補完後の値が {@link MarketRegionValidator#validateAndNormalize(String, String)} に
 * 渡ることを {@link ArgumentCaptor} で検証する（symptom hiding を避けるため値で確認）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentListingService Phase2-C 札立て地域 既定補完")
class RecruitmentListingServicePhase2CTest {

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
    private com.mannschaft.app.recruitment.repository.RecruitmentListingRegionRepository listingRegionRepository;

    // Issue #2715 ロットA: 通知本文の i18n 化で RecruitmentListingService に追加した依存。
    // このテストクラスでは confirmApplication / publish を呼ばないため未使用だが、
    // @InjectMocks が null を注入するのを防ぐため（将来ここでその経路を検証する際の NPE 罠回避）宣言しておく。
    @Mock
    private com.mannschaft.app.common.i18n.UserLocaleCache userLocaleCache;
    @Mock
    private org.springframework.context.MessageSource messageSource;

    @InjectMocks
    private RecruitmentListingService service;

    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;
    private static final Long USER_ID = 1L;
    private static final Long CATEGORY_ID = 100L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.now();

    @Test
    @DisplayName("scope=TEAM・request 地域未指定 → team の地域コードで補完して validator に渡る")
    void create_teamScope_regionUnset_fillsFromTeam() {
        stubCommonCreatePath();
        // team は東京都(13) / 渋谷区(13113) を持つ
        given(teamService.findRegionCodes(TEAM_ID))
                .willReturn(Optional.of(new TeamService.TeamRegionCodes("13", "13113")));

        CreateRecruitmentListingRequest request = requestWithRegion(null, null);
        service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request);

        ArgumentCaptor<String> prefCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> cityCaptor = ArgumentCaptor.forClass(String.class);
        verify(marketRegionValidator).validateAndNormalize(prefCaptor.capture(), cityCaptor.capture());
        assertThat(prefCaptor.getValue()).isEqualTo("13");
        assertThat(cityCaptor.getValue()).isEqualTo("13113");
    }

    @Test
    @DisplayName("scope=TEAM・request 地域指定あり → team を参照せず request を優先（上書き）")
    void create_teamScope_requestRegionGiven_overridesTeam() {
        stubCommonCreatePath();

        CreateRecruitmentListingRequest request = requestWithRegion("27", "27100");
        service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request);

        // request 指定があるため team は参照しない
        verify(teamService, never()).findRegionCodes(anyLong());
        ArgumentCaptor<String> prefCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> cityCaptor = ArgumentCaptor.forClass(String.class);
        verify(marketRegionValidator).validateAndNormalize(prefCaptor.capture(), cityCaptor.capture());
        assertThat(prefCaptor.getValue()).isEqualTo("27");
        assertThat(cityCaptor.getValue()).isEqualTo("27100");
    }

    @Test
    @DisplayName("scope=TEAM・team 地域 NULL → 補完されず地域なし札（validator に null/null）")
    void create_teamScope_teamRegionNull_noFill() {
        stubCommonCreatePath();
        given(teamService.findRegionCodes(TEAM_ID))
                .willReturn(Optional.of(new TeamService.TeamRegionCodes(null, null)));

        CreateRecruitmentListingRequest request = requestWithRegion(null, null);
        service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request);

        ArgumentCaptor<String> prefCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> cityCaptor = ArgumentCaptor.forClass(String.class);
        verify(marketRegionValidator).validateAndNormalize(prefCaptor.capture(), cityCaptor.capture());
        assertThat(prefCaptor.getValue()).isNull();
        assertThat(cityCaptor.getValue()).isNull();
    }

    @Test
    @DisplayName("scope=ORGANIZATION → 補完対象外（team を参照しない・request の値がそのまま）")
    void create_orgScope_noFill() {
        stubCommonCreatePath();

        CreateRecruitmentListingRequest request = requestWithRegion(null, null);
        service.create(RecruitmentScopeType.ORGANIZATION, ORG_ID, USER_ID, request);

        verify(teamService, never()).findRegionCodes(anyLong());
        verify(marketRegionValidator).validateAndNormalize(null, null);
    }

    // ========================================
    // ヘルパー
    // ========================================

    /** create() の正常系経路（カテゴリ存在・validator/enricher/mapper）を共通スタブする。 */
    private void stubCommonCreatePath() {
        given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);
        given(marketRegionValidator.validateAndNormalize(any(), any()))
                .willReturn(new MarketRegionValidator.ResolvedRegion(null, null));
        // save は DB 採番済み（id 設定済み）エンティティを返す（中間表 replace が id を要求するため）。
        given(listingRepository.save(any(RecruitmentListingEntity.class)))
                .willAnswer(inv -> withId(inv.getArgument(0), 999L));
        given(mapper.toListingResponse(any())).willReturn(null);
        given(marketResponseEnricher.enrich(any(), any())).willReturn(null);
    }

    /** BaseEntity.id は private 採番のため、テストでは reflection で設定する。 */
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

    private CreateRecruitmentListingRequest requestWithRegion(String prefectureCode, String cityCode) {
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
                prefectureCode, cityCode, null, null, null,
                null, null); // F22.1 謝礼決済: payeeKind, payeeUserId
    }
}
