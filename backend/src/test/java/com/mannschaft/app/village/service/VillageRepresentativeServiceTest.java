package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.RepresentativeGrantRequest;
import com.mannschaft.app.village.dto.RepresentativeResponse;
import com.mannschaft.app.village.dto.RepresentativeRevokeRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.VillageRepresentativeEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.repository.VillageRepresentativeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
 * F17 Phase 2 U3 — VillageRepresentativeService 単体テスト。
 *
 * <p>カバレッジ（12 ケース）:</p>
 * <ol>
 *   <li>grant 成功（HEADMAN が TEAM メンバーシップ × チームメンバーへ委任）</li>
 *   <li>grant 成功（ELDER が ORGANIZATION メンバーシップへ委任）</li>
 *   <li>grant — 実行者が VILLAGER → VILLAGE_024</li>
 *   <li>grant — 実行者が村人でない → VILLAGE_024</li>
 *   <li>grant — USER メンバーシップへの委任 → VILLAGE_054</li>
 *   <li>grant — 委任先がチーム/組織非メンバー → VILLAGE_055</li>
 *   <li>grant — 既に現役の委任が存在 → VILLAGE_053</li>
 *   <li>grant — 削除済み村 → VILLAGE_001</li>
 *   <li>revoke 成功</li>
 *   <li>revoke — 対象なし → VILLAGE_052</li>
 *   <li>revoke — 既に取消し済み → VILLAGE_052</li>
 *   <li>listRepresentatives — 現役のみ取得</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F17 VillageRepresentativeService 単体テスト")
class VillageRepresentativeServiceTest {

    @Mock
    private VillageRepresentativeRepository representativeRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @Mock
    private VillageRepository villageRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private VillageRepresentativeService service;

    private static final UUID VILLAGE_ID = UUID.randomUUID();
    private static final Long HEADMAN_USER_ID = 100L;
    private static final Long ELDER_USER_ID = 101L;
    private static final Long VILLAGER_USER_ID = 102L;
    private static final Long REPRESENTATIVE_USER_ID = 200L;
    private static final Long TEAM_ID = 500L;
    private static final Long ORG_ID = 700L;

    // ========================================================================
    // 共通ファクトリ
    // ========================================================================

    private VillageEntity activeVillage() {
        return VillageEntity.builder()
                .slug("dojo")
                .name("道場村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(1L)
                .createdByUserId(HEADMAN_USER_ID)
                .build();
    }

    private VillageEntity deletedVillage() {
        VillageEntity v = activeVillage();
        v.setDeletedAt(LocalDateTime.now());
        return v;
    }

    private VillageMembershipEntity membership(VillageSubjectType subjectType, Long subjectId, VillageRole role) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(subjectType)
                .subjectId(subjectId)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        return m;
    }

    private VillageRepresentativeEntity representative(UUID membershipId, Long representativeUserId) {
        VillageRepresentativeEntity e = VillageRepresentativeEntity.builder()
                .villageId(VILLAGE_ID)
                .membershipId(membershipId)
                .representativeUserId(representativeUserId)
                .grantedByUserId(HEADMAN_USER_ID)
                .grantedAt(LocalDateTime.now())
                .build();
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        return e;
    }

    private UserEntity user(Long id, String displayName) {
        UserEntity u = UserEntity.builder()
                .email("user" + id + "@example.com")
                .displayName(displayName)
                .build();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    // ========================================================================
    // 1. grant 成功（TEAM）
    // ========================================================================
    @Test
    @DisplayName("01. grant 成功 — HEADMAN が TEAM メンバーシップ × チームメンバーへ委任")
    void grant_success_team() {
        VillageMembershipEntity headmanMembership = membership(VillageSubjectType.USER, HEADMAN_USER_ID, VillageRole.HEADMAN);
        VillageMembershipEntity teamMembership = membership(VillageSubjectType.TEAM, TEAM_ID, VillageRole.VILLAGER);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(HEADMAN_USER_ID)))
                .willReturn(Optional.of(headmanMembership));
        given(membershipRepository.findById(teamMembership.getId())).willReturn(Optional.of(teamMembership));
        given(userRoleRepository.existsByUserIdAndTeamId(REPRESENTATIVE_USER_ID, TEAM_ID)).willReturn(true);
        // CMP-050 AC-16【陽性対照】: 委任先が ACTIVE なら従来どおり成功する
        given(userRoleRepository.isActiveUser(REPRESENTATIVE_USER_ID)).willReturn(true);
        given(representativeRepository.existsByMembershipIdAndRepresentativeUserIdAndRevokedAtIsNull(
                teamMembership.getId(), REPRESENTATIVE_USER_ID)).willReturn(false);
        given(representativeRepository.save(any(VillageRepresentativeEntity.class))).willAnswer(inv -> {
            VillageRepresentativeEntity e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            return e;
        });
        given(userRepository.findAllById(any(Iterable.class))).willReturn(List.of(
                user(REPRESENTATIVE_USER_ID, "代表 太郎"),
                user(HEADMAN_USER_ID, "村長 次郎")
        ));

        RepresentativeGrantRequest req = new RepresentativeGrantRequest(
                teamMembership.getId(), REPRESENTATIVE_USER_ID, "副代表として任命");

        RepresentativeResponse res = service.grantRepresentative(VILLAGE_ID, req, HEADMAN_USER_ID);

        assertThat(res.villageId()).isEqualTo(VILLAGE_ID);
        assertThat(res.membershipId()).isEqualTo(teamMembership.getId());
        assertThat(res.representativeUserId()).isEqualTo(REPRESENTATIVE_USER_ID);
        assertThat(res.representativeDisplayName()).isEqualTo("代表 太郎");
        assertThat(res.grantedByUserId()).isEqualTo(HEADMAN_USER_ID);
        assertThat(res.grantedByDisplayName()).isEqualTo("村長 次郎");
        assertThat(res.note()).isEqualTo("副代表として任命");
        assertThat(res.revokedAt()).isNull();

        ArgumentCaptor<VillageRepresentativeEntity> captor =
                ArgumentCaptor.forClass(VillageRepresentativeEntity.class);
        verify(representativeRepository).save(captor.capture());
        assertThat(captor.getValue().getNote()).isEqualTo("副代表として任命");
    }

    // ========================================================================
    // 2. grant 成功（ORGANIZATION × ELDER 実行者）
    // ========================================================================
    @Test
    @DisplayName("02. grant 成功 — ELDER が ORGANIZATION メンバーシップへ委任")
    void grant_success_organization_byElder() {
        VillageMembershipEntity elderMembership = membership(VillageSubjectType.USER, ELDER_USER_ID, VillageRole.ELDER);
        VillageMembershipEntity orgMembership = membership(VillageSubjectType.ORGANIZATION, ORG_ID, VillageRole.VILLAGER);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(ELDER_USER_ID)))
                .willReturn(Optional.of(elderMembership));
        given(membershipRepository.findById(orgMembership.getId())).willReturn(Optional.of(orgMembership));
        given(userRoleRepository.existsByUserIdAndOrganizationId(REPRESENTATIVE_USER_ID, ORG_ID)).willReturn(true);
        // CMP-050 AC-16【陽性対照】: ORGANIZATION 側も委任先が ACTIVE なら従来どおり成功する
        given(userRoleRepository.isActiveUser(REPRESENTATIVE_USER_ID)).willReturn(true);
        given(representativeRepository.existsByMembershipIdAndRepresentativeUserIdAndRevokedAtIsNull(
                orgMembership.getId(), REPRESENTATIVE_USER_ID)).willReturn(false);
        given(representativeRepository.save(any(VillageRepresentativeEntity.class))).willAnswer(inv -> {
            VillageRepresentativeEntity e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            return e;
        });
        given(userRepository.findAllById(any(Iterable.class))).willReturn(List.of());

        RepresentativeGrantRequest req = new RepresentativeGrantRequest(
                orgMembership.getId(), REPRESENTATIVE_USER_ID, null);

        RepresentativeResponse res = service.grantRepresentative(VILLAGE_ID, req, ELDER_USER_ID);

        assertThat(res.representativeUserId()).isEqualTo(REPRESENTATIVE_USER_ID);
        // displayName 解決失敗時は null
        assertThat(res.representativeDisplayName()).isNull();
        verify(representativeRepository).save(any());
    }

    // ========================================================================
    // 3. grant — 実行者が VILLAGER
    // ========================================================================
    @Test
    @DisplayName("03. grant 失敗 — 実行者が VILLAGER なら VILLAGE_024")
    void grant_byVillager_forbidden() {
        VillageMembershipEntity villager = membership(VillageSubjectType.USER, VILLAGER_USER_ID, VillageRole.VILLAGER);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(VILLAGER_USER_ID)))
                .willReturn(Optional.of(villager));

        RepresentativeGrantRequest req = new RepresentativeGrantRequest(
                UUID.randomUUID(), REPRESENTATIVE_USER_ID, null);

        assertThatThrownBy(() -> service.grantRepresentative(VILLAGE_ID, req, VILLAGER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);

        verify(representativeRepository, never()).save(any());
    }

    // ========================================================================
    // 4. grant — 実行者が村人でない
    // ========================================================================
    @Test
    @DisplayName("04. grant 失敗 — 実行者が村人でない → VILLAGE_024")
    void grant_notMember_forbidden() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(HEADMAN_USER_ID)))
                .willReturn(Optional.empty());

        RepresentativeGrantRequest req = new RepresentativeGrantRequest(
                UUID.randomUUID(), REPRESENTATIVE_USER_ID, null);

        assertThatThrownBy(() -> service.grantRepresentative(VILLAGE_ID, req, HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ========================================================================
    // 5. grant — USER メンバーシップへの委任拒否
    // ========================================================================
    @Test
    @DisplayName("05. grant 失敗 — USER メンバーシップへの委任 → VILLAGE_054")
    void grant_userMembership_rejected() {
        VillageMembershipEntity headmanMembership = membership(VillageSubjectType.USER, HEADMAN_USER_ID, VillageRole.HEADMAN);
        VillageMembershipEntity userMembership = membership(VillageSubjectType.USER, REPRESENTATIVE_USER_ID, VillageRole.VILLAGER);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(HEADMAN_USER_ID)))
                .willReturn(Optional.of(headmanMembership));
        given(membershipRepository.findById(userMembership.getId())).willReturn(Optional.of(userMembership));

        RepresentativeGrantRequest req = new RepresentativeGrantRequest(
                userMembership.getId(), REPRESENTATIVE_USER_ID, null);

        assertThatThrownBy(() -> service.grantRepresentative(VILLAGE_ID, req, HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.REPRESENTATIVE_NOT_TEAM_OR_ORG_MEMBERSHIP);

        verify(representativeRepository, never()).save(any());
    }

    // ========================================================================
    // 6. grant — 委任先がチーム/組織非メンバー
    // ========================================================================
    @Test
    @DisplayName("06. grant 失敗 — 委任先がチーム非メンバー → VILLAGE_055")
    void grant_userNotInTeam_rejected() {
        VillageMembershipEntity headmanMembership = membership(VillageSubjectType.USER, HEADMAN_USER_ID, VillageRole.HEADMAN);
        VillageMembershipEntity teamMembership = membership(VillageSubjectType.TEAM, TEAM_ID, VillageRole.VILLAGER);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(HEADMAN_USER_ID)))
                .willReturn(Optional.of(headmanMembership));
        given(membershipRepository.findById(teamMembership.getId())).willReturn(Optional.of(teamMembership));
        given(userRoleRepository.existsByUserIdAndTeamId(REPRESENTATIVE_USER_ID, TEAM_ID)).willReturn(false);

        RepresentativeGrantRequest req = new RepresentativeGrantRequest(
                teamMembership.getId(), REPRESENTATIVE_USER_ID, null);

        assertThatThrownBy(() -> service.grantRepresentative(VILLAGE_ID, req, HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.REPRESENTATIVE_USER_NOT_IN_SUBJECT);

        verify(representativeRepository, never()).save(any());
    }

    // ========================================================================
    // 6-b. grant — 委任先が非 ACTIVE / 論理削除済み（CMP-050 AC-15）
    // ========================================================================

    /**
     * CMP-050 AC-15: 委任先が FROZEN または論理削除済みのとき VILLAGE_055 で拒否し、
     * {@code save} を呼ばないこと。
     *
     * <p>在籍行は残っているため在籍判定は true になるが、凍結・退会済みのユーザーへ
     * 村代表を委任すると、その村のチーム/組織を代表する者が実質不在になる。
     * ErrorCode は他人のアカウント状態を漏らさないよう、非メンバー時と同じ
     * {@code REPRESENTATIVE_USER_NOT_IN_SUBJECT}（VILLAGE_055）へ畳む。</p>
     *
     * <p>生存判定は {@code userRoleRepository.isActiveUser} に委ねている。village から
     * {@code UserEntity.UserStatus} を直接読むと ArchUnit D-1（cross-domain entity dependency）の
     * 新規違反になるためであり、TEAM 枝の代表例としてここで締める。</p>
     */
    @Test
    @DisplayName("06-b. grant 失敗 — 委任先が FROZEN → VILLAGE_055（CMP-050 AC-15）")
    void cmp050_ac15_grant_frozenUser_rejected() {
        VillageMembershipEntity headmanMembership = membership(VillageSubjectType.USER, HEADMAN_USER_ID, VillageRole.HEADMAN);
        VillageMembershipEntity teamMembership = membership(VillageSubjectType.TEAM, TEAM_ID, VillageRole.VILLAGER);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(HEADMAN_USER_ID)))
                .willReturn(Optional.of(headmanMembership));
        given(membershipRepository.findById(teamMembership.getId())).willReturn(Optional.of(teamMembership));
        // 在籍はしている
        given(userRoleRepository.existsByUserIdAndTeamId(REPRESENTATIVE_USER_ID, TEAM_ID)).willReturn(true);
        given(userRoleRepository.isActiveUser(REPRESENTATIVE_USER_ID)).willReturn(false);

        RepresentativeGrantRequest req = new RepresentativeGrantRequest(
                teamMembership.getId(), REPRESENTATIVE_USER_ID, null);

        assertThatThrownBy(() -> service.grantRepresentative(VILLAGE_ID, req, HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.REPRESENTATIVE_USER_NOT_IN_SUBJECT);

        verify(representativeRepository, never()).save(any());
    }

    /**
     * CMP-050 AC-15: 委任先が論理削除済みでも VILLAGE_055 で拒否すること（ORGANIZATION 枝）。
     *
     * <p>{@code isActiveUser} は SQL 側で {@code deleted_at IS NULL} と
     * {@code status = 'ACTIVE'} の双方を見るため、凍結と論理削除は同じ false に落ちる。
     * サービスは両者を区別できず、また区別すべきでない（状態漏洩の防止）。</p>
     */
    @Test
    @DisplayName("06-c. grant 失敗 — 委任先が論理削除済み → VILLAGE_055（CMP-050 AC-15・ORGANIZATION枝）")
    void cmp050_ac15_grant_softDeletedUser_rejected() {
        VillageMembershipEntity headmanMembership = membership(VillageSubjectType.USER, HEADMAN_USER_ID, VillageRole.HEADMAN);
        VillageMembershipEntity orgMembership = membership(VillageSubjectType.ORGANIZATION, ORG_ID, VillageRole.VILLAGER);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(HEADMAN_USER_ID)))
                .willReturn(Optional.of(headmanMembership));
        given(membershipRepository.findById(orgMembership.getId())).willReturn(Optional.of(orgMembership));
        given(userRoleRepository.existsByUserIdAndOrganizationId(REPRESENTATIVE_USER_ID, ORG_ID)).willReturn(true);
        given(userRoleRepository.isActiveUser(REPRESENTATIVE_USER_ID)).willReturn(false);

        RepresentativeGrantRequest req = new RepresentativeGrantRequest(
                orgMembership.getId(), REPRESENTATIVE_USER_ID, null);

        assertThatThrownBy(() -> service.grantRepresentative(VILLAGE_ID, req, HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.REPRESENTATIVE_USER_NOT_IN_SUBJECT);

        verify(representativeRepository, never()).save(any());
    }

    // ========================================================================
    // 7. grant — 既に現役の委任が存在
    // ========================================================================
    @Test
    @DisplayName("07. grant 失敗 — 既に現役の委任が存在 → VILLAGE_053")
    void grant_alreadyGranted() {
        VillageMembershipEntity headmanMembership = membership(VillageSubjectType.USER, HEADMAN_USER_ID, VillageRole.HEADMAN);
        VillageMembershipEntity teamMembership = membership(VillageSubjectType.TEAM, TEAM_ID, VillageRole.VILLAGER);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(HEADMAN_USER_ID)))
                .willReturn(Optional.of(headmanMembership));
        given(membershipRepository.findById(teamMembership.getId())).willReturn(Optional.of(teamMembership));
        given(userRoleRepository.existsByUserIdAndTeamId(REPRESENTATIVE_USER_ID, TEAM_ID)).willReturn(true);
        // CMP-050: 生存確認は在籍確認の直後に走るため、重複 grant の判定へ到達するには ACTIVE が必要
        given(userRoleRepository.isActiveUser(REPRESENTATIVE_USER_ID)).willReturn(true);
        given(representativeRepository.existsByMembershipIdAndRepresentativeUserIdAndRevokedAtIsNull(
                teamMembership.getId(), REPRESENTATIVE_USER_ID)).willReturn(true);

        RepresentativeGrantRequest req = new RepresentativeGrantRequest(
                teamMembership.getId(), REPRESENTATIVE_USER_ID, null);

        assertThatThrownBy(() -> service.grantRepresentative(VILLAGE_ID, req, HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.REPRESENTATIVE_ALREADY_GRANTED);

        verify(representativeRepository, never()).save(any());
    }

    // ========================================================================
    // 8. grant — 削除済み村
    // ========================================================================
    @Test
    @DisplayName("08. grant 失敗 — 削除済み村 → VILLAGE_001")
    void grant_deletedVillage() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(deletedVillage()));

        RepresentativeGrantRequest req = new RepresentativeGrantRequest(
                UUID.randomUUID(), REPRESENTATIVE_USER_ID, null);

        assertThatThrownBy(() -> service.grantRepresentative(VILLAGE_ID, req, HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
    }

    // ========================================================================
    // 9. revoke 成功
    // ========================================================================
    @Test
    @DisplayName("09. revoke 成功 — HEADMAN が revoke")
    void revoke_success() {
        VillageMembershipEntity headmanMembership = membership(VillageSubjectType.USER, HEADMAN_USER_ID, VillageRole.HEADMAN);
        UUID membershipId = UUID.randomUUID();
        VillageRepresentativeEntity rep = representative(membershipId, REPRESENTATIVE_USER_ID);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(HEADMAN_USER_ID)))
                .willReturn(Optional.of(headmanMembership));
        given(representativeRepository.findById(rep.getId())).willReturn(Optional.of(rep));
        given(representativeRepository.save(any(VillageRepresentativeEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(userRepository.findAllById(any(Iterable.class))).willReturn(List.of());

        RepresentativeRevokeRequest req = new RepresentativeRevokeRequest("不適切な投稿が散見されたため");

        RepresentativeResponse res = service.revokeRepresentative(VILLAGE_ID, rep.getId(), req, HEADMAN_USER_ID);

        assertThat(res.revokedAt()).isNotNull();
        assertThat(res.id()).isEqualTo(rep.getId());

        ArgumentCaptor<VillageRepresentativeEntity> captor =
                ArgumentCaptor.forClass(VillageRepresentativeEntity.class);
        verify(representativeRepository).save(captor.capture());
        assertThat(captor.getValue().getRevokedByUserId()).isEqualTo(HEADMAN_USER_ID);
        assertThat(captor.getValue().getRevokedAt()).isNotNull();
        assertThat(captor.getValue().getNote()).isEqualTo("不適切な投稿が散見されたため");
    }

    // ========================================================================
    // 10. revoke — 対象なし
    // ========================================================================
    @Test
    @DisplayName("10. revoke 失敗 — 対象 ID なし → VILLAGE_052")
    void revoke_notFound() {
        VillageMembershipEntity headmanMembership = membership(VillageSubjectType.USER, HEADMAN_USER_ID, VillageRole.HEADMAN);
        UUID repId = UUID.randomUUID();

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(HEADMAN_USER_ID)))
                .willReturn(Optional.of(headmanMembership));
        given(representativeRepository.findById(repId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokeRepresentative(
                VILLAGE_ID, repId, new RepresentativeRevokeRequest(null), HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.REPRESENTATIVE_NOT_FOUND);
    }

    // ========================================================================
    // 11. revoke — 既に取消し済み
    // ========================================================================
    @Test
    @DisplayName("11. revoke 失敗 — 既に取消し済 → VILLAGE_052（IDOR 対策で 404 統一）")
    void revoke_alreadyRevoked() {
        VillageMembershipEntity headmanMembership = membership(VillageSubjectType.USER, HEADMAN_USER_ID, VillageRole.HEADMAN);
        VillageRepresentativeEntity rep = representative(UUID.randomUUID(), REPRESENTATIVE_USER_ID);
        rep.setRevokedAt(LocalDateTime.now().minusDays(1));

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(HEADMAN_USER_ID)))
                .willReturn(Optional.of(headmanMembership));
        given(representativeRepository.findById(rep.getId())).willReturn(Optional.of(rep));

        assertThatThrownBy(() -> service.revokeRepresentative(
                VILLAGE_ID, rep.getId(), null, HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.REPRESENTATIVE_NOT_FOUND);

        verify(representativeRepository, never()).save(any());
    }

    // ========================================================================
    // 12. listRepresentatives — 現役のみ取得
    // ========================================================================
    @Test
    @DisplayName("12. listRepresentatives — includeRevoked=false で現役のみ取得（村人なら閲覧可）")
    void list_activeOnly() {
        UUID membershipId = UUID.randomUUID();
        VillageRepresentativeEntity active = representative(membershipId, REPRESENTATIVE_USER_ID);
        VillageMembershipEntity villager = membership(VillageSubjectType.USER, VILLAGER_USER_ID, VillageRole.VILLAGER);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(VILLAGER_USER_ID)))
                .willReturn(Optional.of(villager));
        given(representativeRepository.findByVillageIdAndRevokedAtIsNull(VILLAGE_ID))
                .willReturn(List.of(active));
        given(userRepository.findAllById(any(Iterable.class))).willReturn(List.of(
                user(REPRESENTATIVE_USER_ID, "代表 太郎"),
                user(HEADMAN_USER_ID, "村長 次郎")
        ));

        List<RepresentativeResponse> result = service.listRepresentatives(VILLAGE_ID, false, VILLAGER_USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).representativeDisplayName()).isEqualTo("代表 太郎");
        assertThat(result.get(0).grantedByDisplayName()).isEqualTo("村長 次郎");
        assertThat(result.get(0).revokedAt()).isNull();
    }

    @Test
    @DisplayName("14. listRepresentatives — 非村人は VILLAGE_007（NOT_MEMBER）で拒否")
    void list_byNonMember_forbidden() {
        Long nonMemberUserId = 999L;
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(nonMemberUserId)))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.listRepresentatives(VILLAGE_ID, false, nonMemberUserId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.NOT_MEMBER);

        verify(representativeRepository, never()).findByVillageIdAndRevokedAtIsNull(any());
    }

    // ========================================================================
    // ボーナス: isUserActiveRepresentative 検証ヘルパ
    // ========================================================================
    @Test
    @DisplayName("13. isUserActiveRepresentative — repo の真偽をそのまま返す")
    void isUserActiveRepresentative_delegatesToRepo() {
        UUID membershipId = UUID.randomUUID();
        given(representativeRepository.existsByMembershipIdAndRepresentativeUserIdAndRevokedAtIsNull(
                membershipId, REPRESENTATIVE_USER_ID)).willReturn(true);

        assertThat(service.isUserActiveRepresentative(membershipId, REPRESENTATIVE_USER_ID)).isTrue();
        assertThat(service.isUserActiveRepresentative(null, REPRESENTATIVE_USER_ID)).isFalse();
        assertThat(service.isUserActiveRepresentative(membershipId, null)).isFalse();
    }
}
