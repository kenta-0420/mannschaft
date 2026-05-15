package com.mannschaft.app.village.batch;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageHeadmanSuccessionBatchService} 単体テスト（F17.1 Phase 1 B11）。
 *
 * <p>カバー観点（設計書 §5.5）:</p>
 * <ul>
 *   <li>HEADMAN が現役なら何もしない</li>
 *   <li>HEADMAN が退会済 → ELDER が居れば最古参 ELDER を昇格</li>
 *   <li>HEADMAN が退会済 + ELDER 不在 → 最古参 VILLAGER を昇格</li>
 *   <li>HEADMAN/ELDER/VILLAGER すべて不在 → 村を archive</li>
 *   <li>HEADMAN が TEAM/ORGANIZATION 名義の場合は退会判定対象外</li>
 *   <li>HEADMAN が存在しない（村作成時の異常状態）→ 直接 promoteOrArchive</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageHeadmanSuccessionBatchService 単体テスト")
class VillageHeadmanSuccessionBatchServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000301");
    private static final UUID HEADMAN_MS_ID = UUID.fromString("01956c00-0000-7000-8000-0000000000A1");
    private static final UUID ELDER_MS_ID = UUID.fromString("01956c00-0000-7000-8000-0000000000A2");
    private static final UUID VILLAGER_MS_ID = UUID.fromString("01956c00-0000-7000-8000-0000000000A3");
    private static final Long HEADMAN_USER_ID = 1001L;
    private static final Long ELDER_USER_ID = 1002L;
    private static final Long VILLAGER_USER_ID = 1003L;

    @Mock
    private VillageRepository villageRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private VillageHeadmanSuccessionBatchService batch;

    // ─── ヘルパ ─────────────────────────────────────────

    private VillageEntity activeVillage() {
        VillageEntity v = VillageEntity.builder()
                .slug("village-succession")
                .name("引き継ぎ村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(3L)
                .build();
        v.setId(VILLAGE_ID);
        return v;
    }

    private VillageMembershipEntity membership(UUID id, Long userId, VillageRole role) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(role)
                .joinedAt(LocalDateTime.now().minusDays(role == VillageRole.HEADMAN ? 100 : 50))
                .build();
        m.setId(id);
        return m;
    }

    private UserEntity activeUser(Long id) {
        UserEntity u = UserEntity.builder().build();
        setBaseField(u, "id", id);
        return u;
    }

    private UserEntity deletedUser(Long id) {
        UserEntity u = activeUser(id);
        u.requestDeletion();
        return u;
    }

    /**
     * {@link com.mannschaft.app.common.BaseEntity} のプライベートフィールドを
     * リフレクションでセットする（テスト用）。
     */
    private static void setBaseField(Object entity, String fieldName, Object value) {
        try {
            var f = com.mannschaft.app.common.BaseEntity.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ─── テスト本体 ─────────────────────────────────────

    @Test
    @DisplayName("HEADMAN が現役なら何もしない")
    void headmanActive_noOp() {
        VillageMembershipEntity headman = membership(HEADMAN_MS_ID, HEADMAN_USER_ID, VillageRole.HEADMAN);
        given(membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(VILLAGE_ID, VillageRole.HEADMAN))
                .willReturn(Optional.of(headman));
        given(userRepository.findById(HEADMAN_USER_ID)).willReturn(Optional.of(activeUser(HEADMAN_USER_ID)));

        VillageHeadmanSuccessionBatchService.SuccessionResult result = batch.processVillage(VILLAGE_ID);

        assertThat(result).isEqualTo(VillageHeadmanSuccessionBatchService.SuccessionResult.NOT_NEEDED);
        verify(membershipRepository, never()).save(any());
        verify(villageRepository, never()).save(any());
    }

    @Test
    @DisplayName("HEADMAN 退会 + ELDER 居 → 最古参 ELDER を HEADMAN に昇格")
    void headmanDeleted_promoteElder() {
        VillageMembershipEntity headman = membership(HEADMAN_MS_ID, HEADMAN_USER_ID, VillageRole.HEADMAN);
        VillageMembershipEntity elder = membership(ELDER_MS_ID, ELDER_USER_ID, VillageRole.ELDER);

        given(membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(VILLAGE_ID, VillageRole.HEADMAN))
                .willReturn(Optional.of(headman));
        given(userRepository.findById(HEADMAN_USER_ID)).willReturn(Optional.of(deletedUser(HEADMAN_USER_ID)));
        given(membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(VILLAGE_ID, VillageRole.ELDER))
                .willReturn(Optional.of(elder));

        VillageHeadmanSuccessionBatchService.SuccessionResult result = batch.processVillage(VILLAGE_ID);

        assertThat(result).isEqualTo(VillageHeadmanSuccessionBatchService.SuccessionResult.PROMOTED);
        // 旧 HEADMAN は leftAt がセットされて save される
        ArgumentCaptor<VillageMembershipEntity> cap = ArgumentCaptor.forClass(VillageMembershipEntity.class);
        verify(membershipRepository, times(2)).save(cap.capture());
        VillageMembershipEntity savedHeadman = cap.getAllValues().get(0);
        assertThat(savedHeadman.getLeftAt()).isNotNull();
        VillageMembershipEntity savedElder = cap.getAllValues().get(1);
        assertThat(savedElder.getRole()).isEqualTo(VillageRole.HEADMAN);
        // 監査ログ: VILLAGE_ROLE_GRANTED
        verify(auditLogService).record(
                eq(AuditEventType.VILLAGE_ROLE_GRANTED.name()),
                any(), eq(ELDER_USER_ID), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("HEADMAN 退会 + ELDER 不在 + VILLAGER 居 → VILLAGER を HEADMAN に昇格")
    void headmanDeleted_noElder_promoteVillager() {
        VillageMembershipEntity headman = membership(HEADMAN_MS_ID, HEADMAN_USER_ID, VillageRole.HEADMAN);
        VillageMembershipEntity villager = membership(VILLAGER_MS_ID, VILLAGER_USER_ID, VillageRole.VILLAGER);

        given(membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(VILLAGE_ID, VillageRole.HEADMAN))
                .willReturn(Optional.of(headman));
        given(userRepository.findById(HEADMAN_USER_ID)).willReturn(Optional.of(deletedUser(HEADMAN_USER_ID)));
        given(membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(VILLAGE_ID, VillageRole.ELDER))
                .willReturn(Optional.empty());
        given(membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(VILLAGE_ID, VillageRole.VILLAGER))
                .willReturn(Optional.of(villager));

        VillageHeadmanSuccessionBatchService.SuccessionResult result = batch.processVillage(VILLAGE_ID);

        assertThat(result).isEqualTo(VillageHeadmanSuccessionBatchService.SuccessionResult.PROMOTED);
        verify(auditLogService).record(
                eq(AuditEventType.VILLAGE_ROLE_GRANTED.name()),
                any(), eq(VILLAGER_USER_ID), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("HEADMAN 退会 + 全員不在 → 村を archive")
    void headmanDeleted_nobody_archiveVillage() {
        VillageMembershipEntity headman = membership(HEADMAN_MS_ID, HEADMAN_USER_ID, VillageRole.HEADMAN);
        given(membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(VILLAGE_ID, VillageRole.HEADMAN))
                .willReturn(Optional.of(headman));
        given(userRepository.findById(HEADMAN_USER_ID)).willReturn(Optional.of(deletedUser(HEADMAN_USER_ID)));
        given(membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(VILLAGE_ID, VillageRole.ELDER))
                .willReturn(Optional.empty());
        given(membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(VILLAGE_ID, VillageRole.VILLAGER))
                .willReturn(Optional.empty());
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));

        VillageHeadmanSuccessionBatchService.SuccessionResult result = batch.processVillage(VILLAGE_ID);

        assertThat(result).isEqualTo(VillageHeadmanSuccessionBatchService.SuccessionResult.ARCHIVED);
        ArgumentCaptor<VillageEntity> vcap = ArgumentCaptor.forClass(VillageEntity.class);
        verify(villageRepository).save(vcap.capture());
        assertThat(vcap.getValue().getArchivedAt()).isNotNull();
        // 監査ログ: VILLAGE_ARCHIVED
        verify(auditLogService).record(
                eq(AuditEventType.VILLAGE_ARCHIVED.name()),
                any(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("HEADMAN が TEAM 名義 → 退会判定対象外")
    void headmanAsTeam_noOp() {
        VillageMembershipEntity headman = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.TEAM)
                .subjectId(500L)
                .role(VillageRole.HEADMAN)
                .joinedAt(LocalDateTime.now().minusDays(100))
                .build();
        headman.setId(HEADMAN_MS_ID);

        given(membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(VILLAGE_ID, VillageRole.HEADMAN))
                .willReturn(Optional.of(headman));

        VillageHeadmanSuccessionBatchService.SuccessionResult result = batch.processVillage(VILLAGE_ID);

        assertThat(result).isEqualTo(VillageHeadmanSuccessionBatchService.SuccessionResult.NOT_NEEDED);
        verify(userRepository, never()).findById(any());
        verify(membershipRepository, never()).save(any());
    }

    @Test
    @DisplayName("HEADMAN 不在（異常状態）でも ELDER が居れば昇格する")
    void noHeadman_promoteElderDirectly() {
        VillageMembershipEntity elder = membership(ELDER_MS_ID, ELDER_USER_ID, VillageRole.ELDER);
        given(membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(VILLAGE_ID, VillageRole.HEADMAN))
                .willReturn(Optional.empty());
        given(membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(VILLAGE_ID, VillageRole.ELDER))
                .willReturn(Optional.of(elder));

        VillageHeadmanSuccessionBatchService.SuccessionResult result = batch.processVillage(VILLAGE_ID);

        assertThat(result).isEqualTo(VillageHeadmanSuccessionBatchService.SuccessionResult.PROMOTED);
        verify(membershipRepository).save(elder);
        assertThat(elder.getRole()).isEqualTo(VillageRole.HEADMAN);
    }
}
