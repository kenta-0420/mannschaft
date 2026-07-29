package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageSerendipityRankingResponse;
import com.mannschaft.app.village.dto.VillageSerendipityScoreResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.VillageSerendipityScoreEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.repository.VillageSerendipityScoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageSerendipityService} 単体テスト（F17.1 Phase 3-β）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>getMyScore: 正常系（rank 1 + 正規化スコア） / 未集計時 404</li>
 *   <li>getMyScore: 削除済村 → 404</li>
 *   <li>getRanking: limit クリップ / total 取得</li>
 *   <li>updateUserScore: 新規作成 / 加算 / 増分 0 で no-op / マイナス引数で IllegalArgumentException</li>
 *   <li>スコア 100 以上の正規化頭打ち</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageSerendipityService 単体テスト")
class VillageSerendipityServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000a01");
    private static final Long USER_A = 101L;
    private static final Long USER_B = 102L;
    private static final Long USER_C = 103L;

    @Mock
    private VillageSerendipityScoreRepository serendipityRepository;
    @Mock
    private VillageRepository villageRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;

    @InjectMocks
    private VillageSerendipityService service;

    private void givenActiveMember(Long userId) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .build();
        given(membershipRepository.findActiveByVillageIdAndSubject(VILLAGE_ID, VillageSubjectType.USER, userId))
                .willReturn(Optional.of(m));
    }

    // ========================================================================
    // getMyScore
    // ========================================================================

    @Test
    @DisplayName("getMyScore: 正常系。rank=1 で正規化スコアを返す")
    void getMyScore_returnsNormalizedScore() {
        givenActiveVillage();
        VillageSerendipityScoreEntity me = score(USER_A, 5L, 25L);
        given(serendipityRepository.findByVillageIdAndUserId(VILLAGE_ID, USER_A))
                .willReturn(Optional.of(me));
        // rank 計算用: 上位ページング呼び出し（自分が単独 1 位）
        given(serendipityRepository.findByVillageIdOrderByInteractionScoreDesc(eq(VILLAGE_ID), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(me)));

        VillageSerendipityScoreResponse response = service.getMyScore(VILLAGE_ID, USER_A);

        assertThat(response.villageId()).isEqualTo(VILLAGE_ID);
        assertThat(response.userId()).isEqualTo(USER_A);
        assertThat(response.encounterCount()).isEqualTo(5L);
        assertThat(response.score()).isEqualTo(25.0 / 100.0);
        assertThat(response.rank()).isEqualTo(1);
    }

    @Test
    @DisplayName("getMyScore: スコアが100以上なら正規化スコアは1.0で頭打ち")
    void getMyScore_scoreCappedAtOne() {
        givenActiveVillage();
        VillageSerendipityScoreEntity me = score(USER_A, 200L, 500L);
        given(serendipityRepository.findByVillageIdAndUserId(VILLAGE_ID, USER_A))
                .willReturn(Optional.of(me));
        given(serendipityRepository.findByVillageIdOrderByInteractionScoreDesc(eq(VILLAGE_ID), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(me)));

        VillageSerendipityScoreResponse response = service.getMyScore(VILLAGE_ID, USER_A);

        assertThat(response.score()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("getMyScore: 自分のレコードが未集計 → VILLAGE_076 SERENDIPITY_NOT_FOUND")
    void getMyScore_notFound() {
        givenActiveVillage();
        given(serendipityRepository.findByVillageIdAndUserId(VILLAGE_ID, USER_A))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyScore(VILLAGE_ID, USER_A))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.SERENDIPITY_NOT_FOUND);
    }

    @Test
    @DisplayName("getMyScore: 削除済み村 → VILLAGE_001 VILLAGE_NOT_FOUND")
    void getMyScore_villageDeleted() {
        VillageEntity v = new VillageEntity();
        v.setDeletedAt(LocalDateTime.now());
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(v));

        assertThatThrownBy(() -> service.getMyScore(VILLAGE_ID, USER_A))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
    }

    // ========================================================================
    // getRanking
    // ========================================================================

    @Test
    @DisplayName("getRanking: 上位3件をrank連番付きで返し、totalも返す（村人が閲覧）")
    void getRanking_returnsTopN() {
        givenActiveVillage();
        givenActiveMember(USER_A);
        VillageSerendipityScoreEntity a = score(USER_A, 10L, 50L);
        VillageSerendipityScoreEntity b = score(USER_B, 8L, 30L);
        VillageSerendipityScoreEntity c = score(USER_C, 5L, 10L);
        given(serendipityRepository.findByVillageIdOrderByInteractionScoreDesc(eq(VILLAGE_ID), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(a, b, c)));
        given(serendipityRepository.countByVillageId(VILLAGE_ID)).willReturn(3L);

        VillageSerendipityRankingResponse response = service.getRanking(VILLAGE_ID, 3, USER_A);

        assertThat(response.total()).isEqualTo(3L);
        assertThat(response.items()).hasSize(3);
        assertThat(response.items().get(0).rank()).isEqualTo(1);
        assertThat(response.items().get(0).userId()).isEqualTo(USER_A);
        assertThat(response.items().get(1).rank()).isEqualTo(2);
        assertThat(response.items().get(2).rank()).isEqualTo(3);
    }

    @Test
    @DisplayName("getRanking: limit=null ならデフォルト10で問い合わせ。limit>100なら100にクリップ")
    void getRanking_limitClipping() {
        givenActiveVillage();
        givenActiveMember(USER_A);
        given(serendipityRepository.findByVillageIdOrderByInteractionScoreDesc(eq(VILLAGE_ID), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));
        given(serendipityRepository.countByVillageId(VILLAGE_ID)).willReturn(0L);

        service.getRanking(VILLAGE_ID, null, USER_A);
        service.getRanking(VILLAGE_ID, 500, USER_A);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(serendipityRepository, org.mockito.Mockito.times(2))
                .findByVillageIdOrderByInteractionScoreDesc(eq(VILLAGE_ID), captor.capture());
        List<Pageable> captured = captor.getAllValues();
        assertThat(captured.get(0).getPageSize()).isEqualTo(10);
        assertThat(captured.get(1).getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("getRanking: 非村人は VILLAGE_007（NOT_MEMBER）で拒否")
    void getRanking_byNonMember_forbidden() {
        givenActiveVillage();
        Long nonMemberUserId = 999L;
        given(membershipRepository.findActiveByVillageIdAndSubject(VILLAGE_ID, VillageSubjectType.USER, nonMemberUserId))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRanking(VILLAGE_ID, 3, nonMemberUserId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.NOT_MEMBER);

        verify(serendipityRepository, never()).findByVillageIdOrderByInteractionScoreDesc(any(), any());
    }

    // ========================================================================
    // updateUserScore
    // ========================================================================

    @Test
    @DisplayName("updateUserScore: 未集計ユーザーは新規 INSERT 相当で save される")
    void updateUserScore_createsNewWhenAbsent() {
        given(serendipityRepository.findByVillageIdAndUserId(VILLAGE_ID, USER_A))
                .willReturn(Optional.empty());

        service.updateUserScore(VILLAGE_ID, USER_A, 2L, 5L);

        ArgumentCaptor<VillageSerendipityScoreEntity> captor =
                ArgumentCaptor.forClass(VillageSerendipityScoreEntity.class);
        verify(serendipityRepository).save(captor.capture());
        VillageSerendipityScoreEntity saved = captor.getValue();
        assertThat(saved.getVillageId()).isEqualTo(VILLAGE_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_A);
        assertThat(saved.getEncounterCount()).isEqualTo(2L);
        assertThat(saved.getInteractionScore()).isEqualTo(5L);
    }

    @Test
    @DisplayName("updateUserScore: 既存レコードに加算的に積み上げる")
    void updateUserScore_addsToExisting() {
        VillageSerendipityScoreEntity existing = score(USER_A, 3L, 7L);
        given(serendipityRepository.findByVillageIdAndUserId(VILLAGE_ID, USER_A))
                .willReturn(Optional.of(existing));

        service.updateUserScore(VILLAGE_ID, USER_A, 2L, 4L);

        verify(serendipityRepository).save(existing);
        assertThat(existing.getEncounterCount()).isEqualTo(5L);
        assertThat(existing.getInteractionScore()).isEqualTo(11L);
    }

    @Test
    @DisplayName("updateUserScore: 増分が両方 0 なら save しない（no-op）")
    void updateUserScore_noOpOnZero() {
        service.updateUserScore(VILLAGE_ID, USER_A, 0L, 0L);

        verify(serendipityRepository, never()).save(any());
        verify(serendipityRepository, never()).findByVillageIdAndUserId(any(), any());
    }

    @Test
    @DisplayName("updateUserScore: マイナス引数は IllegalArgumentException")
    void updateUserScore_rejectsNegative() {
        assertThatThrownBy(() -> service.updateUserScore(VILLAGE_ID, USER_A, -1L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateUserScore(VILLAGE_ID, USER_A, 1L, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ========================================================================
    // helpers
    // ========================================================================

    private void givenActiveVillage() {
        VillageEntity v = new VillageEntity();
        // deletedAt=null, archivedAt は本 Service では参照しないため設定不要
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(v));
    }

    private static VillageSerendipityScoreEntity score(Long userId, long encounter, long interaction) {
        VillageSerendipityScoreEntity e = VillageSerendipityScoreEntity.builder()
                .villageId(VILLAGE_ID)
                .userId(userId)
                .encounterCount(encounter)
                .interactionScore(interaction)
                .lastUpdatedAt(LocalDateTime.now())
                .build();
        return e;
    }
}
