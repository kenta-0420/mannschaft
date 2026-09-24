package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.MembershipBanRequest;
import com.mannschaft.app.village.dto.MembershipJoinRequest;
import com.mannschaft.app.village.dto.MembershipListResponse;
import com.mannschaft.app.village.dto.MembershipResponse;
import com.mannschaft.app.village.dto.RoleChangeRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
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
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageMembershipService} 単体テスト（F17.1 Phase 1 B3）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>FREE 村への即時参加 / APPROVAL 村の拒否 (VILLAGE_019)</li>
 *   <li>再参加（退村中レコードがあっても新規行で参加できる）</li>
 *   <li>ALREADY_MEMBER (VILLAGE_006)</li>
 *   <li>BAN 中の再参加拒否 (VILLAGE_031)</li>
 *   <li>参加上限 100 ハードリミット (VILLAGE_012)</li>
 *   <li>30 村超過ソフト警告</li>
 *   <li>IDOR: USER 主体で他人 ID 指定 → 403 (VILLAGE_015)</li>
 *   <li>TEAM/ORG 主体で ADMIN でない → 403 (VILLAGE_015)</li>
 *   <li>退出（HEADMAN 引き継ぎ: ELDER 最古参へ昇格）</li>
 *   <li>退出（HEADMAN 引き継ぎ: ELDER 居ない → VILLAGER 最古参）</li>
 *   <li>退出（後継者なし）</li>
 *   <li>ロール変更（HEADMAN のみ可、自身の最後の HEADMAN 降格不可 VILLAGE_017）</li>
 *   <li>BAN: HEADMAN 以外不可 (VILLAGE_024)</li>
 *   <li>一覧: 非村人は拒否 (VILLAGE_007)</li>
 *   <li>削除/凍結村: VILLAGE_001 / VILLAGE_027</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageMembershipService 単体テスト")
class VillageMembershipServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000001");
    private static final Long ACTOR_USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final Long TEAM_ID = 567L;
    private static final Long ORG_ID = 89L;

    @Mock
    private VillageRepository villageRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @Mock
    private UserRoleRepository userRoleRepository;

    /** 村の存在秘匿ゲート。実物へ委譲させるため {@link VillageAccessGateTestSupport} で結線する。 */
    @Mock
    private VillageAccessGate accessGate;

    @InjectMocks
    private VillageMembershipService service;

    /**
     * 村サービスの村存在確認は {@link VillageAccessGate} へ移った。
     * モックのゲートに実物のゲート（同じモックのリポジトリを注入）を委譲させることで、
     * 本テストが積み上げてきた {@code villageRepository.findById} の stub をそのまま生かしつつ、
     * 可視性判定は実物のロジックで走らせる。
     */
    @BeforeEach
    void wireVillageAccessGate() {
        VillageAccessGateTestSupport.delegateToRealGate(accessGate, villageRepository, membershipRepository);
    }

    private VillageEntity freeVillage;
    private VillageEntity approvalVillage;

    @BeforeEach
    void setUp() {
        freeVillage = VillageEntity.builder()
                .slug("test-village")
                .name("テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(0L)
                .build();
        freeVillage.setId(VILLAGE_ID);

        approvalVillage = VillageEntity.builder()
                .slug("approval-village")
                .name("承認制の村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.APPROVAL)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(0L)
                .build();
        approvalVillage.setId(VILLAGE_ID);
    }

    // ========================================================================
    // 参加
    // ========================================================================

    @Test
    @DisplayName("FREE 村 USER 参加: 即時参加し membership が作成される")
    void join_freeVillage_user_success() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID)).willReturn(Optional.empty());
        given(membershipRepository.findBySubjectTypeAndSubjectIdAndLeftAtIsNull(
                VillageSubjectType.USER, ACTOR_USER_ID)).willReturn(List.of());
        given(membershipRepository.save(any(VillageMembershipEntity.class)))
                .willAnswer(inv -> {
                    VillageMembershipEntity e = inv.getArgument(0);
                    e.setId(UUID.randomUUID());
                    return e;
                });

        MembershipResponse res = service.join(
                VILLAGE_ID, ACTOR_USER_ID,
                new MembershipJoinRequest(VillageSubjectType.USER, ACTOR_USER_ID));

        assertThat(res.role()).isEqualTo(VillageRole.VILLAGER);
        assertThat(res.subjectType()).isEqualTo(VillageSubjectType.USER);
        assertThat(res.participationWarn()).isFalse();
    }

    @Test
    @DisplayName("APPROVAL 村への直接参加は VILLAGE_019")
    void join_approvalVillage_denied() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(approvalVillage));

        assertThatThrownBy(() -> service.join(
                VILLAGE_ID, ACTOR_USER_ID,
                new MembershipJoinRequest(VillageSubjectType.USER, ACTOR_USER_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_JOIN_REQUIRES_APPROVAL);
    }

    @Test
    @DisplayName("USER 主体で他人 ID を指定 → VILLAGE_015 (IDOR)")
    void join_idor_user_subjectId_mismatch() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));

        assertThatThrownBy(() -> service.join(
                VILLAGE_ID, ACTOR_USER_ID,
                new MembershipJoinRequest(VillageSubjectType.USER, OTHER_USER_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.REPRESENT_FORBIDDEN);
    }

    @Test
    @DisplayName("TEAM 主体: actor が ADMIN でない → VILLAGE_015")
    void join_team_notAdmin() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(userRoleRepository.countTeamAdminByUserIdAndTeamId(ACTOR_USER_ID, TEAM_ID))
                .willReturn(0L);

        assertThatThrownBy(() -> service.join(
                VILLAGE_ID, ACTOR_USER_ID,
                new MembershipJoinRequest(VillageSubjectType.TEAM, TEAM_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.REPRESENT_FORBIDDEN);
    }

    @Test
    @DisplayName("ORG 主体: actor が ADMIN → 参加成功")
    void join_organization_asAdmin() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(userRoleRepository.findAdminUserIdsByOrganizationId(ORG_ID))
                .willReturn(List.of(ACTOR_USER_ID, 999L));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.ORGANIZATION, ORG_ID)).willReturn(Optional.empty());
        given(membershipRepository.save(any(VillageMembershipEntity.class)))
                .willAnswer(inv -> {
                    VillageMembershipEntity e = inv.getArgument(0);
                    e.setId(UUID.randomUUID());
                    return e;
                });

        MembershipResponse res = service.join(
                VILLAGE_ID, ACTOR_USER_ID,
                new MembershipJoinRequest(VillageSubjectType.ORGANIZATION, ORG_ID));

        assertThat(res.subjectType()).isEqualTo(VillageSubjectType.ORGANIZATION);
        assertThat(res.role()).isEqualTo(VillageRole.VILLAGER);
    }

    @Test
    @DisplayName("既に村人 → VILLAGE_006 ALREADY_MEMBER")
    void join_alreadyMember() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        VillageMembershipEntity existing = activeMembership(VillageSubjectType.USER, ACTOR_USER_ID, VillageRole.VILLAGER);
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID)).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.join(
                VILLAGE_ID, ACTOR_USER_ID,
                new MembershipJoinRequest(VillageSubjectType.USER, ACTOR_USER_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.ALREADY_MEMBER);
    }

    @Test
    @DisplayName("BAN 中メンバーの再参加は VILLAGE_031")
    void join_banned() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        VillageMembershipEntity banned = activeMembership(VillageSubjectType.USER, ACTOR_USER_ID, VillageRole.VILLAGER);
        banned.setBannedAt(LocalDateTime.now());
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID)).willReturn(Optional.of(banned));

        assertThatThrownBy(() -> service.join(
                VILLAGE_ID, ACTOR_USER_ID,
                new MembershipJoinRequest(VillageSubjectType.USER, ACTOR_USER_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.MEMBER_BANNED);
    }

    @Test
    @DisplayName("参加上限 100 村到達 → VILLAGE_012")
    void join_participationLimit_exceeded() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID)).willReturn(Optional.empty());
        List<VillageMembershipEntity> hundredVillages = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            hundredVillages.add(activeMembership(VillageSubjectType.USER, ACTOR_USER_ID, VillageRole.VILLAGER));
        }
        given(membershipRepository.findBySubjectTypeAndSubjectIdAndLeftAtIsNull(
                VillageSubjectType.USER, ACTOR_USER_ID)).willReturn(hundredVillages);

        assertThatThrownBy(() -> service.join(
                VILLAGE_ID, ACTOR_USER_ID,
                new MembershipJoinRequest(VillageSubjectType.USER, ACTOR_USER_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.PARTICIPATION_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("30 村超過のソフト警告が participationWarn=true で返る")
    void join_softWarn_over30() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID)).willReturn(Optional.empty());

        // 1 回目: 上限チェック用（30 件）、2 回目: warn 判定用（31 件）
        List<VillageMembershipEntity> thirty = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            thirty.add(activeMembership(VillageSubjectType.USER, ACTOR_USER_ID, VillageRole.VILLAGER));
        }
        List<VillageMembershipEntity> thirtyOne = new ArrayList<>(thirty);
        thirtyOne.add(activeMembership(VillageSubjectType.USER, ACTOR_USER_ID, VillageRole.VILLAGER));
        given(membershipRepository.findBySubjectTypeAndSubjectIdAndLeftAtIsNull(
                VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(thirty, thirtyOne);
        given(membershipRepository.save(any(VillageMembershipEntity.class)))
                .willAnswer(inv -> {
                    VillageMembershipEntity e = inv.getArgument(0);
                    e.setId(UUID.randomUUID());
                    return e;
                });

        MembershipResponse res = service.join(
                VILLAGE_ID, ACTOR_USER_ID,
                new MembershipJoinRequest(VillageSubjectType.USER, ACTOR_USER_ID));

        assertThat(res.participationWarn()).isTrue();
    }

    @Test
    @DisplayName("凍結済み村への参加は VILLAGE_027")
    void join_archivedVillage() {
        VillageEntity archived = VillageEntity.builder()
                .slug("a")
                .name("a")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(0L)
                .archivedAt(LocalDateTime.now())
                .build();
        archived.setId(VILLAGE_ID);
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(archived));

        assertThatThrownBy(() -> service.join(
                VILLAGE_ID, ACTOR_USER_ID,
                new MembershipJoinRequest(VillageSubjectType.USER, ACTOR_USER_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);
    }

    // ========================================================================
    // 退出 + HEADMAN 引き継ぎ
    // ========================================================================

    @Test
    @DisplayName("HEADMAN 退出: ELDER 最古参が自動昇格")
    void leave_headman_promotesElder() {
        UUID membershipId = UUID.randomUUID();
        VillageMembershipEntity headman = activeMembership(VillageSubjectType.USER, ACTOR_USER_ID, VillageRole.HEADMAN);
        headman.setId(membershipId);
        VillageMembershipEntity elder = activeMembership(VillageSubjectType.USER, 300L, VillageRole.ELDER);
        elder.setId(UUID.randomUUID());

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findById(membershipId)).willReturn(Optional.of(headman));
        given(membershipRepository.findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(
                VILLAGE_ID, VillageRole.ELDER)).willReturn(Optional.of(elder));
        given(membershipRepository.save(any(VillageMembershipEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        service.leave(VILLAGE_ID, membershipId, ACTOR_USER_ID);

        assertThat(elder.getRole()).isEqualTo(VillageRole.HEADMAN);
        assertThat(headman.getLeftAt()).isNotNull();
    }

    @Test
    @DisplayName("HEADMAN 退出: ELDER が居ない場合 VILLAGER 最古参が自動昇格")
    void leave_headman_promotesVillager_whenNoElder() {
        UUID membershipId = UUID.randomUUID();
        VillageMembershipEntity headman = activeMembership(VillageSubjectType.USER, ACTOR_USER_ID, VillageRole.HEADMAN);
        headman.setId(membershipId);
        VillageMembershipEntity villager = activeMembership(VillageSubjectType.USER, 400L, VillageRole.VILLAGER);
        villager.setId(UUID.randomUUID());

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findById(membershipId)).willReturn(Optional.of(headman));
        given(membershipRepository.findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(
                VILLAGE_ID, VillageRole.ELDER)).willReturn(Optional.empty());
        given(membershipRepository.findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(
                VILLAGE_ID, VillageRole.VILLAGER)).willReturn(Optional.of(villager));
        given(membershipRepository.save(any(VillageMembershipEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        service.leave(VILLAGE_ID, membershipId, ACTOR_USER_ID);

        assertThat(villager.getRole()).isEqualTo(VillageRole.HEADMAN);
    }

    @Test
    @DisplayName("HEADMAN 退出: 後継者がいなければ村は memberships 退出のみで終了（B11 バッチ任せ）")
    void leave_headman_noSuccessor_abandons() {
        UUID membershipId = UUID.randomUUID();
        VillageMembershipEntity headman = activeMembership(VillageSubjectType.USER, ACTOR_USER_ID, VillageRole.HEADMAN);
        headman.setId(membershipId);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findById(membershipId)).willReturn(Optional.of(headman));
        given(membershipRepository.findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(
                VILLAGE_ID, VillageRole.ELDER)).willReturn(Optional.empty());
        given(membershipRepository.findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(
                VILLAGE_ID, VillageRole.VILLAGER)).willReturn(Optional.empty());
        given(membershipRepository.save(any(VillageMembershipEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        service.leave(VILLAGE_ID, membershipId, ACTOR_USER_ID);

        assertThat(headman.getLeftAt()).isNotNull();
        // ELDER/VILLAGER への save は呼ばれず、自身の save のみ
        verify(membershipRepository, times(1)).save(any(VillageMembershipEntity.class));
    }

    @Test
    @DisplayName("退出: 既に退村済みの membership は VILLAGE_007")
    void leave_alreadyLeft() {
        UUID membershipId = UUID.randomUUID();
        VillageMembershipEntity left = activeMembership(VillageSubjectType.USER, ACTOR_USER_ID, VillageRole.VILLAGER);
        left.setId(membershipId);
        left.setLeftAt(LocalDateTime.now());

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findById(membershipId)).willReturn(Optional.of(left));

        assertThatThrownBy(() -> service.leave(VILLAGE_ID, membershipId, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.NOT_MEMBER);
    }

    // ========================================================================
    // 一覧
    // ========================================================================

    @Test
    @DisplayName("一覧: 村人以外は VILLAGE_007 (IDOR)")
    void list_notMember_denied() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.listMembers(VILLAGE_ID, ACTOR_USER_ID, 0, 50))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.NOT_MEMBER);
    }

    @Test
    @DisplayName("一覧: 村人 → ページネーションで返却")
    void list_member_returnsPage() {
        VillageMembershipEntity actor = activeMembership(VillageSubjectType.USER, ACTOR_USER_ID, VillageRole.VILLAGER);
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID)).willReturn(Optional.of(actor));

        VillageMembershipEntity m1 = activeMembership(VillageSubjectType.USER, 100L, VillageRole.HEADMAN);
        VillageMembershipEntity m2 = activeMembership(VillageSubjectType.TEAM, 567L, VillageRole.VILLAGER);
        Page<VillageMembershipEntity> page = new PageImpl<>(List.of(m1, m2), Pageable.ofSize(50), 2);
        given(membershipRepository.findByVillageIdAndLeftAtIsNullOrderByJoinedAtAsc(eq(VILLAGE_ID), any()))
                .willReturn(page);

        MembershipListResponse res = service.listMembers(VILLAGE_ID, ACTOR_USER_ID, 0, 50);

        assertThat(res.content()).hasSize(2);
        assertThat(res.totalElements()).isEqualTo(2);
        // TEAM はプレースホルダ表示名
        assertThat(res.content().get(1).displayName()).isEqualTo("TEAM:#567");
    }

    // ========================================================================
    // ロール変更
    // ========================================================================

    @Test
    @DisplayName("ロール変更: HEADMAN でない実行者は VILLAGE_024")
    void changeRole_notHeadman_denied() {
        UUID targetMembershipId = UUID.randomUUID();
        VillageMembershipEntity actor = activeMembership(VillageSubjectType.USER, ACTOR_USER_ID, VillageRole.VILLAGER);
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID)).willReturn(Optional.of(actor));

        assertThatThrownBy(() -> service.changeRole(VILLAGE_ID, targetMembershipId, ACTOR_USER_ID,
                new RoleChangeRequest(VillageRole.ELDER)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    @Test
    @DisplayName("ロール変更: HEADMAN が他人を ELDER に昇格")
    void changeRole_promoteElder_success() {
        UUID targetId = UUID.randomUUID();
        VillageMembershipEntity actor = activeMembership(VillageSubjectType.USER, ACTOR_USER_ID, VillageRole.HEADMAN);
        VillageMembershipEntity target = activeMembership(VillageSubjectType.USER, OTHER_USER_ID, VillageRole.VILLAGER);
        target.setId(targetId);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID)).willReturn(Optional.of(actor));
        given(membershipRepository.findById(targetId)).willReturn(Optional.of(target));
        given(membershipRepository.save(any(VillageMembershipEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        MembershipResponse res = service.changeRole(VILLAGE_ID, targetId, ACTOR_USER_ID,
                new RoleChangeRequest(VillageRole.ELDER));

        assertThat(res.role()).isEqualTo(VillageRole.ELDER);
    }

    @Test
    @DisplayName("ロール変更: 自身を最後の HEADMAN から VILLAGER へ降格しようとすると VILLAGE_017")
    void changeRole_selfDemote_lastHeadman_denied() {
        UUID actorMembershipId = UUID.randomUUID();
        VillageMembershipEntity actor = activeMembership(VillageSubjectType.USER, ACTOR_USER_ID, VillageRole.HEADMAN);
        actor.setId(actorMembershipId);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID)).willReturn(Optional.of(actor));
        given(membershipRepository.findById(actorMembershipId)).willReturn(Optional.of(actor));
        given(membershipRepository.countByVillageIdAndRoleAndLeftAtIsNull(VILLAGE_ID, VillageRole.HEADMAN))
                .willReturn(1L);
        given(membershipRepository.countByVillageIdAndRoleAndLeftAtIsNull(VILLAGE_ID, VillageRole.ELDER))
                .willReturn(0L);

        assertThatThrownBy(() -> service.changeRole(VILLAGE_ID, actorMembershipId, ACTOR_USER_ID,
                new RoleChangeRequest(VillageRole.VILLAGER)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.HEADMAN_CANNOT_LEAVE);
    }

    // ========================================================================
    // BAN
    // ========================================================================

    @Test
    @DisplayName("BAN: HEADMAN 以外は VILLAGE_024")
    void ban_notHeadman_denied() {
        UUID targetId = UUID.randomUUID();
        VillageMembershipEntity actor = activeMembership(VillageSubjectType.USER, ACTOR_USER_ID, VillageRole.ELDER);
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID)).willReturn(Optional.of(actor));

        assertThatThrownBy(() -> service.ban(VILLAGE_ID, targetId, ACTOR_USER_ID,
                new MembershipBanRequest("spam")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    @Test
    @DisplayName("BAN: HEADMAN が他メンバーを BAN")
    void ban_success() {
        UUID targetId = UUID.randomUUID();
        VillageMembershipEntity actor = activeMembership(VillageSubjectType.USER, ACTOR_USER_ID, VillageRole.HEADMAN);
        VillageMembershipEntity target = activeMembership(VillageSubjectType.USER, OTHER_USER_ID, VillageRole.VILLAGER);
        target.setId(targetId);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID)).willReturn(Optional.of(actor));
        given(membershipRepository.findById(targetId)).willReturn(Optional.of(target));
        given(membershipRepository.save(any(VillageMembershipEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        MembershipResponse res = service.ban(VILLAGE_ID, targetId, ACTOR_USER_ID,
                new MembershipBanRequest("ガイドライン違反"));

        assertThat(res.isBanned()).isTrue();
        assertThat(target.getBannedAt()).isNotNull();
        assertThat(target.getLeftAt()).isNotNull();
        assertThat(target.getBannedReason()).isEqualTo("ガイドライン違反");
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    private VillageMembershipEntity activeMembership(VillageSubjectType type, Long subjectId, VillageRole role) {
        VillageMembershipEntity e = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(type)
                .subjectId(subjectId)
                .role(role)
                .joinedAt(LocalDateTime.now().minusDays(10))
                .build();
        e.setId(UUID.randomUUID());
        return e;
    }
}
