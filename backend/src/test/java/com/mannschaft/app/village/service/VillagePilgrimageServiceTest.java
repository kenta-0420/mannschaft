package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.PilgrimageRecommendationResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillagePilgrimageRecommendationEntity;
import com.mannschaft.app.village.repository.VillagePilgrimageRecommendationRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link VillagePilgrimageService} 単体テスト（F17.1 Phase 3-β 巡礼）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>getTodaysRecommendation: 推薦あり / 無し</li>
 *   <li>recordVisit: 初回訪問 visited_at 記録 + 監査ログ</li>
 *   <li>recordVisit: 二回目以降は冪等（監査ログ追加無し・visited_at 不変）</li>
 *   <li>recordVisit: 本人以外の推薦 → 404 IDOR 防止</li>
 *   <li>recordVisit: 存在しない recommendationId → 404</li>
 *   <li>listMyHistory: ページング + Village メタデータ埋込</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillagePilgrimageService 単体テスト")
class VillagePilgrimageServiceTest {

    private static final Long USER_ID = 901L;
    private static final Long OTHER_USER_ID = 902L;
    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000801");
    private static final UUID RECOMMENDATION_ID = UUID.fromString("01956c00-0000-7000-8000-000000000901");

    @Mock
    private VillagePilgrimageRecommendationRepository pilgrimageRepository;
    @Mock
    private VillageRepository villageRepository;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private VillagePilgrimageService service;

    private VillageEntity village;

    @BeforeEach
    void setUp() {
        village = VillageEntity.builder().build();
        village.setId(VILLAGE_ID);
        village.setSlug("test-village");
        village.setName("テスト村");
        village.setCategory("sports");
        village.setIconR2Key("villages/icon.png");
    }

    @Test
    @DisplayName("today: 推薦が存在すれば Village メタ込みで返す")
    void today_present() {
        VillagePilgrimageRecommendationEntity rec = newRecommendation(USER_ID, VILLAGE_ID, LocalDate.now(), null);
        given(pilgrimageRepository.findByUserIdAndRecommendedDate(USER_ID, LocalDate.now()))
                .willReturn(Optional.of(rec));
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));

        Optional<PilgrimageRecommendationResponse> result = service.getTodaysRecommendation(USER_ID);

        assertThat(result).isPresent();
        assertThat(result.get().villageSlug()).isEqualTo("test-village");
        assertThat(result.get().villageCategory()).isEqualTo("sports");
        assertThat(result.get().visitedAt()).isNull();
    }

    @Test
    @DisplayName("today: 推薦が無ければ empty を返す")
    void today_empty() {
        given(pilgrimageRepository.findByUserIdAndRecommendedDate(USER_ID, LocalDate.now()))
                .willReturn(Optional.empty());

        Optional<PilgrimageRecommendationResponse> result = service.getTodaysRecommendation(USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("recordVisit: 初回訪問は visited_at をセットし監査ログを記録")
    void recordVisit_first() {
        VillagePilgrimageRecommendationEntity rec = newRecommendation(USER_ID, VILLAGE_ID, LocalDate.now(), null);
        given(pilgrimageRepository.findById(RECOMMENDATION_ID)).willReturn(Optional.of(rec));
        given(pilgrimageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));

        PilgrimageRecommendationResponse result = service.recordVisit(USER_ID, RECOMMENDATION_ID);

        assertThat(result.visitedAt()).isNotNull();
        assertThat(rec.getVisitedAt()).isNotNull();
        verify(pilgrimageRepository, times(1)).save(rec);
        verify(auditLogService, times(1)).record(
                eq(AuditEventType.VILLAGE_PILGRIMAGE_VISITED.name()),
                eq(USER_ID), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("recordVisit: 二回目以降は冪等（visited_at 不変・監査ログ追加なし）")
    void recordVisit_alreadyVisited_idempotent() {
        LocalDateTime first = LocalDateTime.now().minusHours(2);
        VillagePilgrimageRecommendationEntity rec = newRecommendation(USER_ID, VILLAGE_ID, LocalDate.now(), first);
        given(pilgrimageRepository.findById(RECOMMENDATION_ID)).willReturn(Optional.of(rec));
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));

        PilgrimageRecommendationResponse result = service.recordVisit(USER_ID, RECOMMENDATION_ID);

        assertThat(result.visitedAt()).isEqualTo(first);
        verify(pilgrimageRepository, never()).save(any());
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(),
                any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("recordVisit: 他ユーザーの推薦は 404 (IDOR 防止)")
    void recordVisit_otherUser_404() {
        VillagePilgrimageRecommendationEntity rec = newRecommendation(OTHER_USER_ID, VILLAGE_ID, LocalDate.now(), null);
        given(pilgrimageRepository.findById(RECOMMENDATION_ID)).willReturn(Optional.of(rec));

        assertThatThrownBy(() -> service.recordVisit(USER_ID, RECOMMENDATION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", VillageErrorCode.PILGRIMAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("recordVisit: 存在しない recommendationId は 404")
    void recordVisit_notFound() {
        given(pilgrimageRepository.findById(RECOMMENDATION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordVisit(USER_ID, RECOMMENDATION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", VillageErrorCode.PILGRIMAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("history: ページング結果に Village メタが埋め込まれる")
    void history_withVillageMeta() {
        VillagePilgrimageRecommendationEntity rec1 = newRecommendation(USER_ID, VILLAGE_ID, LocalDate.now(), null);
        VillagePilgrimageRecommendationEntity rec2 = newRecommendation(USER_ID, VILLAGE_ID,
                LocalDate.now().minusDays(1), LocalDateTime.now().minusDays(1));

        Page<VillagePilgrimageRecommendationEntity> page = new PageImpl<>(List.of(rec1, rec2));
        given(pilgrimageRepository.findByUserIdOrderByRecommendedDateDesc(eq(USER_ID), any(Pageable.class)))
                .willReturn(page);
        given(villageRepository.findAllById(List.of(VILLAGE_ID))).willReturn(List.of(village));

        List<PilgrimageRecommendationResponse> result = service.listMyHistory(USER_ID, PageRequest.of(0, 20));

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(r -> "テスト村".equals(r.villageName()));
        assertThat(result.get(1).visitedAt()).isNotNull();
    }

    // ====================================================================
    // ヘルパ
    // ====================================================================

    private VillagePilgrimageRecommendationEntity newRecommendation(
            Long userId, UUID villageId, LocalDate date, LocalDateTime visitedAt) {
        VillagePilgrimageRecommendationEntity entity = VillagePilgrimageRecommendationEntity.builder()
                .userId(userId)
                .recommendedVillageId(villageId)
                .recommendedDate(date)
                .reason("CATEGORY_MATCH:sports")
                .visitedAt(visitedAt)
                .build();
        entity.setId(RECOMMENDATION_ID);
        return entity;
    }
}
