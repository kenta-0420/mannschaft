package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.JoinRequestCreateRequest;
import com.mannschaft.app.village.dto.JoinRequestResponse;
import com.mannschaft.app.village.dto.JoinRequestReviewRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageJoinRequestEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageJoinRequestRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * F17.1 Phase 1 B6 — VillageJoinRequestService 単体テスト。
 *
 * <p>カバレッジ（12 ケース）:</p>
 * <ol>
 *   <li>申請作成成功（USER 主体）</li>
 *   <li>FREE 村への申請は VILLAGE_041 で弾かれる</li>
 *   <li>既にメンバーなら VILLAGE_006</li>
 *   <li>BAN 中なら VILLAGE_031</li>
 *   <li>同主体の PENDING 重複なら VILLAGE_039</li>
 *   <li>代表権限なし（USER で他人申請）は VILLAGE_015</li>
 *   <li>承認成功 — APPROVED + membership 作成</li>
 *   <li>承認 — 既にメンバーなら VILLAGE_006</li>
 *   <li>非審査者（VILLAGER）の承認は VILLAGE_024</li>
 *   <li>拒否 — reviewComment 空欄なら COMMON_001</li>
 *   <li>取下げ成功（申請者本人）</li>
 *   <li>取下げ — 第三者なら COMMON_002 (403)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F17.1 VillageJoinRequestService 単体テスト")
class VillageJoinRequestServiceTest {

    @Mock
    private VillageJoinRequestRepository joinRequestRepository;
    @Mock
    private VillageRepository villageRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @Mock
    private VillageMembershipService membershipService;

    @InjectMocks
    private VillageJoinRequestService service;

    private static final UUID VILLAGE_ID = UUID.randomUUID();
    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;

    private VillageEntity approvalVillage() {
        return VillageEntity.builder()
                .slug("dojo")
                .name("道場")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.APPROVAL)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(1L)
                .createdByUserId(50L)
                .build();
    }

    private VillageEntity freeVillage() {
        return VillageEntity.builder()
                .slug("plaza")
                .name("広場")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(1L)
                .createdByUserId(50L)
                .build();
    }

    private VillageMembershipEntity membership(VillageRole role, Long userId) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        return m;
    }

    private JoinRequestCreateRequest validCreateRequest() {
        return new JoinRequestCreateRequest(VillageSubjectType.USER, USER_ID, "よろしくお願いします");
    }

    // ----------------------------------------------------------------
    // 1. 申請作成成功
    // ----------------------------------------------------------------
    @Test
    @DisplayName("01. 申請作成成功 — PENDING で保存される")
    void create_success() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(approvalVillage()));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(USER_ID)))
                .willReturn(Optional.empty());
        given(joinRequestRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndStatus(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(USER_ID), eq(VillageRequestStatus.PENDING)))
                .willReturn(Optional.empty());
        given(joinRequestRepository.save(any())).willAnswer(inv -> {
            VillageJoinRequestEntity e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            e.setCreatedAt(LocalDateTime.now());
            return e;
        });

        JoinRequestResponse res = service.createRequest(VILLAGE_ID, USER_ID, validCreateRequest());

        assertThat(res.status()).isEqualTo(VillageRequestStatus.PENDING);
        assertThat(res.subjectId()).isEqualTo(USER_ID);
        assertThat(res.message()).isEqualTo("よろしくお願いします");

        verify(membershipService).validateSubjectAuthorization(USER_ID, VillageSubjectType.USER, USER_ID);
        ArgumentCaptor<VillageJoinRequestEntity> captor =
                ArgumentCaptor.forClass(VillageJoinRequestEntity.class);
        verify(joinRequestRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(VillageRequestStatus.PENDING);
        assertThat(captor.getValue().getRequesterUserId()).isEqualTo(USER_ID);
    }

    // ----------------------------------------------------------------
    // 2. FREE 村への申請は弾かれる
    // ----------------------------------------------------------------
    @Test
    @DisplayName("02. FREE 村への申請 — VILLAGE_041 で拒否")
    void create_freeVillage_rejected() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(freeVillage()));

        assertThatThrownBy(() -> service.createRequest(VILLAGE_ID, USER_ID, validCreateRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.VILLAGE_FREE_VILLAGE_DIRECT_JOIN);

        verify(joinRequestRepository, never()).save(any());
    }

    // ----------------------------------------------------------------
    // 3. 既メンバー
    // ----------------------------------------------------------------
    @Test
    @DisplayName("03. 既にメンバー — VILLAGE_006")
    void create_alreadyMember() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(approvalVillage()));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(USER_ID)))
                .willReturn(Optional.of(membership(VillageRole.VILLAGER, USER_ID)));

        assertThatThrownBy(() -> service.createRequest(VILLAGE_ID, USER_ID, validCreateRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.ALREADY_MEMBER);

        verify(joinRequestRepository, never()).save(any());
    }

    // ----------------------------------------------------------------
    // 4. BAN 中
    // ----------------------------------------------------------------
    @Test
    @DisplayName("04. BAN 中の主体 — VILLAGE_031")
    void create_banned() {
        VillageMembershipEntity banned = membership(VillageRole.VILLAGER, USER_ID);
        banned.setBannedAt(LocalDateTime.now());
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(approvalVillage()));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(USER_ID)))
                .willReturn(Optional.of(banned));

        assertThatThrownBy(() -> service.createRequest(VILLAGE_ID, USER_ID, validCreateRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.MEMBER_BANNED);
    }

    // ----------------------------------------------------------------
    // 5. PENDING 重複
    // ----------------------------------------------------------------
    @Test
    @DisplayName("05. 同主体の PENDING 重複 — VILLAGE_039")
    void create_pendingDuplicate() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(approvalVillage()));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(USER_ID)))
                .willReturn(Optional.empty());
        VillageJoinRequestEntity existing = VillageJoinRequestEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(USER_ID)
                .requesterUserId(USER_ID)
                .status(VillageRequestStatus.PENDING)
                .build();
        given(joinRequestRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndStatus(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(USER_ID), eq(VillageRequestStatus.PENDING)))
                .willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createRequest(VILLAGE_ID, USER_ID, validCreateRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.VILLAGE_JOIN_REQUEST_PENDING_DUPLICATE);

        verify(joinRequestRepository, never()).save(any());
    }

    // ----------------------------------------------------------------
    // 6. 代表権限なし（USER 主体で他人 ID を指定）
    // ----------------------------------------------------------------
    @Test
    @DisplayName("06. 代表権限なし — VillageMembershipService が VILLAGE_015 を投げる")
    void create_representForbidden() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(approvalVillage()));
        willThrow(new BusinessException(VillageErrorCode.REPRESENT_FORBIDDEN))
                .given(membershipService)
                .validateSubjectAuthorization(eq(USER_ID), eq(VillageSubjectType.USER), eq(OTHER_USER_ID));

        JoinRequestCreateRequest req = new JoinRequestCreateRequest(
                VillageSubjectType.USER, OTHER_USER_ID, null);
        assertThatThrownBy(() -> service.createRequest(VILLAGE_ID, USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.REPRESENT_FORBIDDEN);

        verify(joinRequestRepository, never()).save(any());
    }

    // ----------------------------------------------------------------
    // 7. 承認成功
    // ----------------------------------------------------------------
    @Test
    @DisplayName("07. 承認 — APPROVED に更新 + membership 作成")
    void approve_success() {
        UUID requestId = UUID.randomUUID();
        VillageJoinRequestEntity pending = VillageJoinRequestEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(OTHER_USER_ID)
                .requesterUserId(OTHER_USER_ID)
                .status(VillageRequestStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(pending, "id", requestId);

        VillageMembershipEntity reviewer = membership(VillageRole.HEADMAN, USER_ID);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(approvalVillage()));
        // ensureReviewer
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(USER_ID)))
                .willReturn(Optional.of(reviewer));
        // 申請ロード
        given(joinRequestRepository.findById(requestId)).willReturn(Optional.of(pending));
        // 重複ガード（申請主体が既にメンバーでないこと）
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(OTHER_USER_ID)))
                .willReturn(Optional.empty());
        given(membershipRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(joinRequestRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        JoinRequestResponse res = service.approve(
                VILLAGE_ID, requestId, USER_ID, new JoinRequestReviewRequest("歓迎"));

        assertThat(res.status()).isEqualTo(VillageRequestStatus.APPROVED);
        assertThat(res.reviewedBy()).isEqualTo(reviewer.getId());
        assertThat(res.reviewComment()).isEqualTo("歓迎");

        ArgumentCaptor<VillageMembershipEntity> mCap = ArgumentCaptor.forClass(VillageMembershipEntity.class);
        verify(membershipRepository).save(mCap.capture());
        assertThat(mCap.getValue().getSubjectId()).isEqualTo(OTHER_USER_ID);
        assertThat(mCap.getValue().getRole()).isEqualTo(VillageRole.VILLAGER);
        assertThat(mCap.getValue().getInvitedByMembershipId()).isEqualTo(reviewer.getId());
    }

    // ----------------------------------------------------------------
    // 8. 承認 — 既にメンバー
    // ----------------------------------------------------------------
    @Test
    @DisplayName("08. 承認 — 既にメンバーなら VILLAGE_006")
    void approve_alreadyMember() {
        UUID requestId = UUID.randomUUID();
        VillageJoinRequestEntity pending = VillageJoinRequestEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(OTHER_USER_ID)
                .requesterUserId(OTHER_USER_ID)
                .status(VillageRequestStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(pending, "id", requestId);

        VillageMembershipEntity reviewer = membership(VillageRole.ELDER, USER_ID);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(approvalVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(USER_ID)))
                .willReturn(Optional.of(reviewer));
        given(joinRequestRepository.findById(requestId)).willReturn(Optional.of(pending));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(OTHER_USER_ID)))
                .willReturn(Optional.of(membership(VillageRole.VILLAGER, OTHER_USER_ID)));

        assertThatThrownBy(() -> service.approve(
                VILLAGE_ID, requestId, USER_ID, new JoinRequestReviewRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.ALREADY_MEMBER);

        verify(joinRequestRepository, never()).save(any());
    }

    // ----------------------------------------------------------------
    // 9. 非審査者（VILLAGER）の承認
    // ----------------------------------------------------------------
    @Test
    @DisplayName("09. VILLAGER による承認 — VILLAGE_024")
    void approve_villagerForbidden() {
        UUID requestId = UUID.randomUUID();
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(approvalVillage()));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(USER_ID)))
                .willReturn(Optional.of(membership(VillageRole.VILLAGER, USER_ID)));

        assertThatThrownBy(() -> service.approve(
                VILLAGE_ID, requestId, USER_ID, new JoinRequestReviewRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ----------------------------------------------------------------
    // 10. 拒否 — reviewComment 空欄なら COMMON_001
    // ----------------------------------------------------------------
    @Test
    @DisplayName("10. 拒否 — reviewComment 空欄なら COMMON_001")
    void reject_requiresComment() {
        UUID requestId = UUID.randomUUID();
        assertThatThrownBy(() -> service.reject(
                VILLAGE_ID, requestId, USER_ID, new JoinRequestReviewRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_001);
        assertThatThrownBy(() -> service.reject(
                VILLAGE_ID, requestId, USER_ID, new JoinRequestReviewRequest("   ")))
                .isInstanceOf(BusinessException.class);
        verify(joinRequestRepository, never()).save(any());
    }

    // ----------------------------------------------------------------
    // 11. 取下げ成功
    // ----------------------------------------------------------------
    @Test
    @DisplayName("11. 取下げ — 申請者本人なら WITHDRAWN")
    void withdraw_byRequester() {
        UUID requestId = UUID.randomUUID();
        VillageJoinRequestEntity pending = VillageJoinRequestEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(USER_ID)
                .requesterUserId(USER_ID)
                .status(VillageRequestStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(pending, "id", requestId);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(approvalVillage()));
        given(joinRequestRepository.findById(requestId)).willReturn(Optional.of(pending));
        given(joinRequestRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        JoinRequestResponse res = service.withdraw(VILLAGE_ID, requestId, USER_ID);

        assertThat(res.status()).isEqualTo(VillageRequestStatus.WITHDRAWN);
    }

    // ----------------------------------------------------------------
    // 12. 取下げ — 第三者は禁止
    // ----------------------------------------------------------------
    @Test
    @DisplayName("12. 取下げ — 第三者なら COMMON_002 (403)")
    void withdraw_byOtherUser_forbidden() {
        UUID requestId = UUID.randomUUID();
        VillageJoinRequestEntity pending = VillageJoinRequestEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(USER_ID)
                .requesterUserId(USER_ID)
                .status(VillageRequestStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(pending, "id", requestId);

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(approvalVillage()));
        given(joinRequestRepository.findById(requestId)).willReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.withdraw(VILLAGE_ID, requestId, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);

        verify(joinRequestRepository, never()).save(any());
    }

    // ----------------------------------------------------------------
    // 13〜15. 申請者向け「自分の申請」取得（listMine）
    // ----------------------------------------------------------------

    /**
     * AC2（IDOR 閉塞）の要。
     *
     * <p>listMine は「操作者本人が申請した行」だけをリポジトリで絞り込む。
     * 取得後に所有者を検証する方式（= 他人の行を一度読んでから弾く）だと検証漏れが
     * そのまま漏洩になるため、**そもそも他人の行を読まない**クエリにしている。
     * 絞り込みキーは withdraw の認可条件（{@code requesterUserId == actor}）と同一。</p>
     */
    @Test
    @DisplayName("13. listMine — 操作者本人の申請だけをリポジトリで絞り込む（IDOR 閉塞）")
    void listMine_filtersByRequesterUserId() {
        VillageJoinRequestEntity mine = VillageJoinRequestEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(USER_ID)
                .requesterUserId(USER_ID)
                .message("よろしく")
                .status(VillageRequestStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(mine, "id", UUID.randomUUID());

        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(approvalVillage()));
        given(joinRequestRepository.findByVillageIdAndRequesterUserIdOrderByCreatedAtDesc(
                VILLAGE_ID, USER_ID)).willReturn(List.of(mine));

        List<JoinRequestResponse> result = service.listMine(VILLAGE_ID, USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(VillageRequestStatus.PENDING);
        assertThat(result.get(0).subjectId()).isEqualTo(USER_ID);

        // 他人の ID でリポジトリを引くことは絶対にない
        verify(joinRequestRepository)
                .findByVillageIdAndRequesterUserIdOrderByCreatedAtDesc(VILLAGE_ID, USER_ID);
        verify(joinRequestRepository, never())
                .findByVillageIdAndRequesterUserIdOrderByCreatedAtDesc(VILLAGE_ID, OTHER_USER_ID);
        // 申請者は定義上まだ非メンバーなので、メンバーシップ判定は一切行わない。
        // （メソッド名に依存せず「メンバーシップ層に触れないこと」自体を固定する）
        verifyNoInteractions(membershipRepository);
        verifyNoInteractions(membershipService);
    }

    @Test
    @DisplayName("14. listMine — 申請が無ければ空リスト（例外にしない）")
    void listMine_empty() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(approvalVillage()));
        given(joinRequestRepository.findByVillageIdAndRequesterUserIdOrderByCreatedAtDesc(
                VILLAGE_ID, USER_ID)).willReturn(List.of());

        assertThat(service.listMine(VILLAGE_ID, USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("15. listMine — 村が存在しなければ VILLAGE_001")
    void listMine_villageNotFound() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.listMine(VILLAGE_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
    }
}
