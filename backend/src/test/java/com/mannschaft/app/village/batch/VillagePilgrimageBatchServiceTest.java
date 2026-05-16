package com.mannschaft.app.village.batch;

import com.mannschaft.app.village.entity.UserVillagePinEntity;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link VillagePilgrimageBatchService} 単体テスト（F17.1 Phase 3-β 巡礼バッチ）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>generateForUser: カテゴリ一致 + 未参加 + 未ピン の候補から推薦行を 1 件生成</li>
 *   <li>generateForUser: 当日既に推薦行があればスキップ（冪等）</li>
 *   <li>generateForUser: 候補がゼロなら行を作らない</li>
 *   <li>generateForUser: 削除済 / 凍結 / UNLISTED の村は候補から除外</li>
 *   <li>generateForUser: ピン済 / 参加済 の村は候補から除外</li>
 * </ul>
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
    @DisplayName("カテゴリ一致 + 未参加 + 未ピン の村が候補となり、1 件生成される")
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
        given(villageRepository.findAll()).willReturn(List.of(
                village(joinedVillageId, "sports", VillageVisibility.PUBLIC, false, false),     // 参加済→除外
                village(candidateVillageId, "sports", VillageVisibility.PUBLIC, false, false)   // 候補
        ));
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
    @DisplayName("候補がゼロ（カテゴリ一致なし）なら行を作らない")
    void generate_noCandidate() {
        LocalDate today = LocalDate.now();
        UUID joinedVillageId = UUID.randomUUID();
        UUID otherCategoryVillageId = UUID.randomUUID();

        given(pilgrimageRepository.existsByUserIdAndRecommendedDate(USER_ID, today)).willReturn(false);
        given(membershipRepository.findActiveUserMemberships(USER_ID))
                .willReturn(List.of(membership(joinedVillageId)));
        given(villageRepository.findAllById(anyCollection()))
                .willReturn(List.of(village(joinedVillageId, "sports", VillageVisibility.PUBLIC, false, false)));
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID)).willReturn(List.of());
        given(villageRepository.findAll()).willReturn(List.of(
                village(joinedVillageId, "sports", VillageVisibility.PUBLIC, false, false),     // 参加済
                village(otherCategoryVillageId, "music", VillageVisibility.PUBLIC, false, false) // カテゴリ不一致
        ));

        boolean created = batch.generateForUser(USER_ID, today);

        assertThat(created).isFalse();
        verify(pilgrimageRepository, never()).save(any());
    }

    @Test
    @DisplayName("削除済・凍結・UNLISTED の村は候補から除外される")
    void generate_excludesDeletedArchivedUnlisted() {
        LocalDate today = LocalDate.now();
        UUID joinedVillageId = UUID.randomUUID();
        UUID deletedId = UUID.randomUUID();
        UUID archivedId = UUID.randomUUID();
        UUID unlistedId = UUID.randomUUID();

        given(pilgrimageRepository.existsByUserIdAndRecommendedDate(USER_ID, today)).willReturn(false);
        given(membershipRepository.findActiveUserMemberships(USER_ID))
                .willReturn(List.of(membership(joinedVillageId)));
        given(villageRepository.findAllById(anyCollection()))
                .willReturn(List.of(village(joinedVillageId, "sports", VillageVisibility.PUBLIC, false, false)));
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID)).willReturn(List.of());
        given(villageRepository.findAll()).willReturn(List.of(
                village(joinedVillageId, "sports", VillageVisibility.PUBLIC, false, false),
                village(deletedId, "sports", VillageVisibility.PUBLIC, true, false),   // 削除済
                village(archivedId, "sports", VillageVisibility.PUBLIC, false, true),  // 凍結
                village(unlistedId, "sports", VillageVisibility.UNLISTED, false, false) // UNLISTED
        ));

        boolean created = batch.generateForUser(USER_ID, today);

        assertThat(created).isFalse();
        verify(pilgrimageRepository, never()).save(any());
    }

    @Test
    @DisplayName("ピン済みの村は候補から除外される")
    void generate_excludesPinned() {
        LocalDate today = LocalDate.now();
        UUID joinedVillageId = UUID.randomUUID();
        UUID pinnedId = UUID.randomUUID();

        given(pilgrimageRepository.existsByUserIdAndRecommendedDate(USER_ID, today)).willReturn(false);
        given(membershipRepository.findActiveUserMemberships(USER_ID))
                .willReturn(List.of(membership(joinedVillageId)));
        given(villageRepository.findAllById(anyCollection()))
                .willReturn(List.of(village(joinedVillageId, "sports", VillageVisibility.PUBLIC, false, false)));
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID))
                .willReturn(List.of(pin(pinnedId)));
        given(villageRepository.findAll()).willReturn(List.of(
                village(joinedVillageId, "sports", VillageVisibility.PUBLIC, false, false),
                village(pinnedId, "sports", VillageVisibility.PUBLIC, false, false)
        ));

        boolean created = batch.generateForUser(USER_ID, today);

        assertThat(created).isFalse();
        verify(pilgrimageRepository, never()).save(any());
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

    private UserVillagePinEntity pin(UUID villageId) {
        UserVillagePinEntity p = UserVillagePinEntity.builder()
                .userId(USER_ID)
                .villageId(villageId)
                .sortOrder(0L)
                .build();
        return p;
    }
}
