package com.mannschaft.app.village.batch;

import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.VillagePilgrimageRecommendationEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.UserVillagePinRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillagePilgrimageRecommendationRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link VillagePilgrimageBatchService} 単体テスト（F17.1 Phase 3-β 巡礼バッチ）。
 *
 * <p>候補選定の絞り込み（削除/凍結/UNLISTED除外・カテゴリ一致・参加済/ピン済除外）は
 * {@link VillageRepository#findPilgrimageCandidateIds} の SQL 側 WHERE 句へ移管したため、
 * 本テストは「サービスが正しい引数（除外ID集合・カテゴリ絞り込みの有無）でリポジトリを呼ぶか」
 * 「候補の有無に応じて正しく作成/スキップするか」のみを検証する。SQL の絞り込み自体の正しさは
 * {@code VillagePilgrimageCandidateRepositoryIntegrationTest}（実 DB 結合テスト）で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillagePilgrimageBatchService 単体テスト")
class VillagePilgrimageBatchServiceTest {

    private static final Long USER_ID = 901L;

    @Mock
    private VillageRepository villageRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @Mock
    private UserVillagePinRepository pinRepository;
    @Mock
    private VillagePilgrimageRecommendationRepository pilgrimageRepository;

    @InjectMocks
    private VillagePilgrimageBatchService batch;

    @Test
    @DisplayName("カテゴリ一致の候補が返れば、その村で推薦行を1件生成しカテゴリ理由を記録する")
    void generate_categoryMatch() {
        LocalDate today = LocalDate.now();
        UUID joinedVillageId = UUID.randomUUID();
        UUID candidateVillageId = UUID.randomUUID();

        given(pilgrimageRepository.existsByUserIdAndRecommendedDate(USER_ID, today)).willReturn(false);
        given(membershipRepository.findActiveUserMemberships(USER_ID))
                .willReturn(List.of(membership(joinedVillageId)));
        given(villageRepository.findAllById(anyCollection()))
                .willReturn(List.of(village(joinedVillageId, "sports", VillageVisibility.PUBLIC, false, false)));
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID)).willReturn(List.of());
        given(villageRepository.findPilgrimageCandidateIds(
                eq(VillageVisibility.PUBLIC), anyCollection(), eq(false), anyCollection()))
                .willReturn(List.of(candidateVillageId));
        given(villageRepository.findById(candidateVillageId))
                .willReturn(java.util.Optional.of(
                        village(candidateVillageId, "sports", VillageVisibility.PUBLIC, false, false)));
        given(pilgrimageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        boolean created = batch.generateForUser(USER_ID, today);

        assertThat(created).isTrue();
        ArgumentCaptor<VillagePilgrimageRecommendationEntity> cap =
                ArgumentCaptor.forClass(VillagePilgrimageRecommendationEntity.class);
        verify(pilgrimageRepository).save(cap.capture());
        assertThat(cap.getValue().getRecommendedVillageId()).isEqualTo(candidateVillageId);
        assertThat(cap.getValue().getReason()).isEqualTo("CATEGORY_MATCH:sports");
        assertThat(cap.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(cap.getValue().getRecommendedDate()).isEqualTo(today);

        // 参加済み・ピン済みの村IDが除外集合として渡っていること
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> excludeCap = ArgumentCaptor.forClass(Collection.class);
        verify(villageRepository).findPilgrimageCandidateIds(
                eq(VillageVisibility.PUBLIC), excludeCap.capture(), eq(false), anyCollection());
        assertThat(excludeCap.getValue()).contains(joinedVillageId);
    }

    @Test
    @DisplayName("既に当日の推薦があればスキップして save しない（冪等）")
    void generate_skipIfExists() {
        LocalDate today = LocalDate.now();
        given(pilgrimageRepository.existsByUserIdAndRecommendedDate(USER_ID, today)).willReturn(true);

        boolean created = batch.generateForUser(USER_ID, today);

        assertThat(created).isFalse();
        verify(pilgrimageRepository, never()).save(any());
        verify(membershipRepository, never()).findActiveUserMemberships(any());
    }

    @Test
    @DisplayName("所属村が無ければスキップして save しない")
    void generate_noMembership() {
        LocalDate today = LocalDate.now();
        given(pilgrimageRepository.existsByUserIdAndRecommendedDate(USER_ID, today)).willReturn(false);
        given(membershipRepository.findActiveUserMemberships(USER_ID)).willReturn(List.of());

        boolean created = batch.generateForUser(USER_ID, today);

        assertThat(created).isFalse();
        verify(pilgrimageRepository, never()).save(any());
        verify(villageRepository, never()).findPilgrimageCandidateIds(
                any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("リポジトリが候補ゼロを返せば行を作らない")
    void generate_noCandidate() {
        LocalDate today = LocalDate.now();
        UUID joinedVillageId = UUID.randomUUID();

        given(pilgrimageRepository.existsByUserIdAndRecommendedDate(USER_ID, today)).willReturn(false);
        given(membershipRepository.findActiveUserMemberships(USER_ID))
                .willReturn(List.of(membership(joinedVillageId)));
        given(villageRepository.findAllById(anyCollection()))
                .willReturn(List.of(village(joinedVillageId, "sports", VillageVisibility.PUBLIC, false, false)));
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID)).willReturn(List.of());
        given(villageRepository.findPilgrimageCandidateIds(
                any(), anyCollection(), anyBoolean(), anyCollection()))
                .willReturn(List.of());

        boolean created = batch.generateForUser(USER_ID, today);

        assertThat(created).isFalse();
        verify(pilgrimageRepository, never()).save(any());
    }

    @Test
    @DisplayName("所属村にカテゴリが無い場合、カテゴリ絞り込み無しでリポジトリを呼び RANDOM 理由になる")
    void generate_noCategory_randomReason() {
        LocalDate today = LocalDate.now();
        UUID joinedVillageId = UUID.randomUUID();
        UUID candidateVillageId = UUID.randomUUID();

        given(pilgrimageRepository.existsByUserIdAndRecommendedDate(USER_ID, today)).willReturn(false);
        given(membershipRepository.findActiveUserMemberships(USER_ID))
                .willReturn(List.of(membership(joinedVillageId)));
        // 所属村がカテゴリ未設定（null）
        given(villageRepository.findAllById(anyCollection()))
                .willReturn(List.of(village(joinedVillageId, null, VillageVisibility.PUBLIC, false, false)));
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID)).willReturn(List.of());
        given(villageRepository.findPilgrimageCandidateIds(
                eq(VillageVisibility.PUBLIC), anyCollection(), eq(true), anyCollection()))
                .willReturn(List.of(candidateVillageId));
        given(villageRepository.findById(candidateVillageId))
                .willReturn(java.util.Optional.of(
                        village(candidateVillageId, "music", VillageVisibility.PUBLIC, false, false)));
        given(pilgrimageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        boolean created = batch.generateForUser(USER_ID, today);

        assertThat(created).isTrue();
        ArgumentCaptor<VillagePilgrimageRecommendationEntity> cap =
                ArgumentCaptor.forClass(VillagePilgrimageRecommendationEntity.class);
        verify(pilgrimageRepository).save(cap.capture());
        assertThat(cap.getValue().getReason()).isEqualTo("RANDOM");

        // categoriesEmpty=true でリポジトリが呼ばれていること
        verify(villageRepository).findPilgrimageCandidateIds(
                eq(VillageVisibility.PUBLIC), anyCollection(), eq(true), anyCollection());
    }

    // ====================================================================
    // ヘルパ
    // ====================================================================

    private VillageMembershipEntity membership(UUID villageId) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectId(USER_ID)
                .build();
        return m;
    }

    private VillageEntity village(UUID id, String category, VillageVisibility visibility,
                                  boolean deleted, boolean archived) {
        VillageEntity v = VillageEntity.builder()
                .slug("v-" + id.toString().substring(0, 8))
                .name("村-" + id.toString().substring(0, 4))
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(visibility)
                .category(category)
                .memberCountCache(1L)
                .build();
        v.setId(id);
        if (deleted) {
            v.setDeletedAt(java.time.LocalDateTime.now());
        }
        if (archived) {
            v.setArchivedAt(java.time.LocalDateTime.now());
        }
        return v;
    }

}
