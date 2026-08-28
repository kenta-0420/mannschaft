package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageCreationRequestCreateRequest;
import com.mannschaft.app.village.dto.VillageCreationRequestResponse;
import com.mannschaft.app.village.dto.VillageCreationRequestReviewRequest;
import com.mannschaft.app.village.entity.VillageCreationRequestEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.repository.VillageCreationRequestRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F17.1 Phase 1 B5 — VillageCreationRequestService 単体テスト。
 *
 * <p>10 ケース:
 * <ol>
 *   <li>申請作成成功</li>
 *   <li>日次レートリミット（4件目で 429）</li>
 *   <li>保有 PENDING 上限（11件目で 429）</li>
 *   <li>guideline 未同意で 422</li>
 *   <li>slug 衝突で 409</li>
 *   <li>運営承認 → 自動村作成 + HEADMAN 追加</li>
 *   <li>運営拒否 + review_comment 必須</li>
 *   <li>二重審査拒否</li>
 *   <li>取り下げ（申請者本人）</li>
 *   <li>非運営による承認試行拒否（withdraw を他人が行う）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F17.1 VillageCreationRequestService 単体テスト")
class VillageCreationRequestServiceTest {

    @Mock
    private VillageCreationRequestRepository requestRepository;
    @Mock
    private VillageRepository villageRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @Mock
    private UserRoleRepository userRoleRepository;

    @InjectMocks
    private VillageCreationRequestService service;

    private static final Long REQUESTER_ID = 100L;
    private static final Long ADMIN_ID = 999L;

    private VillageCreationRequestCreateRequest validRequest() {
        return new VillageCreationRequestCreateRequest(
                "草野球村",
                "casual-baseball",
                "スポーツ",
                "草野球チーム同士の交流の場が欲しい",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5),
                VillageType.COMMUNITY,
                "## ガイドライン"
        );
    }

    @BeforeEach
    void setup() {
        // テストごとに必要分だけ stubbing
    }

    // ---------------------------------------------------------------
    // 1. 申請作成成功（自動承認）
    // ---------------------------------------------------------------
    @Test
    @DisplayName("01. 申請作成成功 — 自動承認で APPROVED になり villageId が返る")
    void createRequest_success() {
        given(requestRepository.countByRequesterUserIdAndCreatedAtAfter(eq(REQUESTER_ID), any())).willReturn(0L);
        given(villageRepository.existsBySlug("casual-baseball")).willReturn(false);
        // 1回目（初回保存）と2回目（自動承認後更新）の両方に対応
        given(requestRepository.save(any())).willAnswer(inv -> {
            VillageCreationRequestEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            }
            e.setCreatedAt(LocalDateTime.now());
            return e;
        });
        given(villageRepository.save(any())).willAnswer(inv -> {
            VillageEntity v = inv.getArgument(0);
            ReflectionTestUtils.setField(v, "id", UUID.randomUUID());
            return v;
        });
        given(membershipRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        VillageCreationRequestResponse res = service.createRequest(REQUESTER_ID, validRequest());

        // 自動承認されるので APPROVED
        assertThat(res.status()).isEqualTo(VillageRequestStatus.APPROVED);
        assertThat(res.slug()).isEqualTo("casual-baseball");
        assertThat(res.requesterUserId()).isEqualTo(REQUESTER_ID);
        // createdVillageId が設定されていること
        assertThat(res.createdVillageId()).isNotNull();

        // 村レコードが作成された
        ArgumentCaptor<VillageEntity> vCap = ArgumentCaptor.forClass(VillageEntity.class);
        verify(villageRepository).save(vCap.capture());
        assertThat(vCap.getValue().getSlug()).isEqualTo("casual-baseball");
        assertThat(vCap.getValue().getName()).isEqualTo("草野球村");
        assertThat(vCap.getValue().getCreatedByUserId()).isEqualTo(REQUESTER_ID);

        // HEADMAN として membership が作成された
        ArgumentCaptor<VillageMembershipEntity> mCap = ArgumentCaptor.forClass(VillageMembershipEntity.class);
        verify(membershipRepository).save(mCap.capture());
        assertThat(mCap.getValue().getSubjectId()).isEqualTo(REQUESTER_ID);
        assertThat(mCap.getValue().getRole()).isEqualTo(VillageRole.HEADMAN);

        // requestRepository.save は 2 回呼ばれる（初回保存 + 承認更新）
        verify(requestRepository, times(2)).save(any());
    }

    // ---------------------------------------------------------------
    // 2. 日次レートリミット（4件目で 429）
    // ---------------------------------------------------------------
    @Test
    @DisplayName("02. 日次レートリミット — 既に3件あれば VILLAGE_017")
    void createRequest_dailyRateLimit() {
        given(requestRepository.countByRequesterUserIdAndCreatedAtAfter(eq(REQUESTER_ID), any()))
                .willReturn(3L);

        assertThatThrownBy(() -> service.createRequest(REQUESTER_ID, validRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.CREATION_REQUEST_THROTTLED);

        verify(requestRepository, never()).save(any());
    }

    // ---------------------------------------------------------------
    // 3.（旧）保有 PENDING 上限テストは削除。
    //   createRequest は同一トランザクション内で即時自動承認するため PENDING は蓄積せず、
    //   保有上限チェックは構造的に発火し得ない死条件だった（本体から撤去済み）。
    //   将来 2 段階承認フローに戻す場合は専用エラーコードと共に上限チェックを再導入すること。
    // ---------------------------------------------------------------

    // ---------------------------------------------------------------
    // 4. guideline 未同意（1時間より前） → VILLAGE_015
    // ---------------------------------------------------------------
    @Test
    @DisplayName("04. ガイドライン同意が古すぎる — VILLAGE_015")
    void createRequest_guidelineExpired() {
        VillageCreationRequestCreateRequest req = new VillageCreationRequestCreateRequest(
                "草野球村", "casual-baseball", null, "p",
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(2),
                VillageType.COMMUNITY, null);

        assertThatThrownBy(() -> service.createRequest(REQUESTER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.GUIDELINE_NOT_AGREED);
    }

    // ---------------------------------------------------------------
    // 5. slug 衝突 → VILLAGE_027
    // ---------------------------------------------------------------
    @Test
    @DisplayName("05. slug 衝突 — VILLAGE_027")
    void createRequest_slugConflict() {
        given(requestRepository.countByRequesterUserIdAndCreatedAtAfter(eq(REQUESTER_ID), any())).willReturn(0L);
        given(villageRepository.existsBySlug("casual-baseball")).willReturn(true);

        assertThatThrownBy(() -> service.createRequest(REQUESTER_ID, validRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.CREATION_REQUEST_SLUG_TAKEN);
    }

    // ---------------------------------------------------------------
    // 6. 承認 → 自動村作成 + HEADMAN 追加
    // ---------------------------------------------------------------
    @Test
    @DisplayName("06. 承認 — villages レコードと HEADMAN membership が作成される")
    void approve_createsVillageAndMembership() {
        UUID requestId = UUID.randomUUID();
        VillageCreationRequestEntity pending = VillageCreationRequestEntity.builder()
                .requesterUserId(REQUESTER_ID)
                .proposedName("草野球村")
                .proposedSlug("casual-baseball")
                .proposedCategory("スポーツ")
                .purpose("p")
                .status(VillageRequestStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(pending, "id", requestId);

        given(requestRepository.findById(requestId)).willReturn(Optional.of(pending));
        given(villageRepository.existsBySlug("casual-baseball")).willReturn(false);
        given(villageRepository.save(any())).willAnswer(inv -> {
            VillageEntity v = inv.getArgument(0);
            ReflectionTestUtils.setField(v, "id", UUID.randomUUID());
            return v;
        });
        given(membershipRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(requestRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        VillageCreationRequestResponse res = service.approve(
                requestId, ADMIN_ID, new VillageCreationRequestReviewRequest("問題なし"));

        // 村が作成された
        ArgumentCaptor<VillageEntity> vCap = ArgumentCaptor.forClass(VillageEntity.class);
        verify(villageRepository).save(vCap.capture());
        assertThat(vCap.getValue().getSlug()).isEqualTo("casual-baseball");
        assertThat(vCap.getValue().getName()).isEqualTo("草野球村");
        assertThat(vCap.getValue().getType()).isEqualTo(VillageType.COMMUNITY);
        assertThat(vCap.getValue().getCreatedByUserId()).isEqualTo(REQUESTER_ID);

        // HEADMAN として membership 追加
        ArgumentCaptor<VillageMembershipEntity> mCap = ArgumentCaptor.forClass(VillageMembershipEntity.class);
        verify(membershipRepository).save(mCap.capture());
        assertThat(mCap.getValue().getSubjectId()).isEqualTo(REQUESTER_ID);
        assertThat(mCap.getValue().getRole()).isEqualTo(VillageRole.HEADMAN);

        // 申請が APPROVED に更新
        assertThat(res.status()).isEqualTo(VillageRequestStatus.APPROVED);
        assertThat(res.reviewedBy()).isEqualTo(ADMIN_ID);
        assertThat(res.createdVillageId()).isNotNull();
    }

    // ---------------------------------------------------------------
    // 7. 拒否 — review_comment 必須
    // ---------------------------------------------------------------
    @Test
    @DisplayName("07. 拒否 — review_comment 空欄なら COMMON_001")
    void reject_requiresComment() {
        UUID requestId = UUID.randomUUID();
        // findById は通る必要すらない（先にコメント検証で弾かれる）
        assertThatThrownBy(() ->
                service.reject(requestId, ADMIN_ID, new VillageCreationRequestReviewRequest(null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() ->
                service.reject(requestId, ADMIN_ID, new VillageCreationRequestReviewRequest("  ")))
                .isInstanceOf(BusinessException.class);

        verify(requestRepository, never()).save(any());
    }

    @Test
    @DisplayName("07b. 拒否 — コメント付きなら REJECTED に更新される")
    void reject_success() {
        UUID requestId = UUID.randomUUID();
        VillageCreationRequestEntity pending = VillageCreationRequestEntity.builder()
                .requesterUserId(REQUESTER_ID)
                .proposedName("n")
                .proposedSlug("s")
                .purpose("p")
                .status(VillageRequestStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(pending, "id", requestId);

        given(requestRepository.findById(requestId)).willReturn(Optional.of(pending));
        given(requestRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        VillageCreationRequestResponse res = service.reject(
                requestId, ADMIN_ID, new VillageCreationRequestReviewRequest("既存と重複"));

        assertThat(res.status()).isEqualTo(VillageRequestStatus.REJECTED);
        assertThat(res.reviewComment()).isEqualTo("既存と重複");
        assertThat(res.reviewedBy()).isEqualTo(ADMIN_ID);
    }

    // ---------------------------------------------------------------
    // 8. 二重審査拒否
    // ---------------------------------------------------------------
    @Test
    @DisplayName("08. 二重審査 — 既に APPROVED の申請は VILLAGE_019")
    void approve_alreadyReviewed() {
        UUID requestId = UUID.randomUUID();
        VillageCreationRequestEntity approved = VillageCreationRequestEntity.builder()
                .requesterUserId(REQUESTER_ID)
                .proposedName("n")
                .proposedSlug("s")
                .purpose("p")
                .status(VillageRequestStatus.APPROVED)
                .build();
        ReflectionTestUtils.setField(approved, "id", requestId);
        given(requestRepository.findById(requestId)).willReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.approve(requestId, ADMIN_ID,
                new VillageCreationRequestReviewRequest("再審査")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.CREATION_REQUEST_ALREADY_REVIEWED);

        verify(villageRepository, never()).save(any());
    }

    @Test
    @DisplayName("08b. 拒否済み申請を再審査 — VILLAGE_023")
    void approve_rejectedHistory() {
        UUID requestId = UUID.randomUUID();
        VillageCreationRequestEntity rejected = VillageCreationRequestEntity.builder()
                .requesterUserId(REQUESTER_ID)
                .proposedName("n")
                .proposedSlug("s")
                .purpose("p")
                .status(VillageRequestStatus.REJECTED)
                .build();
        ReflectionTestUtils.setField(rejected, "id", requestId);
        given(requestRepository.findById(requestId)).willReturn(Optional.of(rejected));

        assertThatThrownBy(() -> service.approve(requestId, ADMIN_ID,
                new VillageCreationRequestReviewRequest("コメ")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.CREATION_REQUEST_REJECTED);
    }

    // ---------------------------------------------------------------
    // 9. 取り下げ（申請者本人）
    // ---------------------------------------------------------------
    @Test
    @DisplayName("09. 取り下げ — 申請者本人なら WITHDRAWN")
    void withdraw_byOwner() {
        UUID requestId = UUID.randomUUID();
        VillageCreationRequestEntity pending = VillageCreationRequestEntity.builder()
                .requesterUserId(REQUESTER_ID)
                .proposedName("n")
                .proposedSlug("s")
                .purpose("p")
                .status(VillageRequestStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(pending, "id", requestId);

        given(requestRepository.findById(requestId)).willReturn(Optional.of(pending));
        given(requestRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        VillageCreationRequestResponse res = service.withdraw(requestId, REQUESTER_ID);

        assertThat(res.status()).isEqualTo(VillageRequestStatus.WITHDRAWN);
        // 本人取り下げなので reviewer は記録しない
        assertThat(res.reviewedBy()).isNull();
    }

    // ---------------------------------------------------------------
    // 10. 非運営による他人申請の取り下げ → 403
    // ---------------------------------------------------------------
    @Test
    @DisplayName("10. 取り下げ — 第三者かつ非運営なら COMMON_002 (403)")
    void withdraw_byOtherUser_forbidden() {
        UUID requestId = UUID.randomUUID();
        VillageCreationRequestEntity pending = VillageCreationRequestEntity.builder()
                .requesterUserId(REQUESTER_ID)
                .proposedName("n")
                .proposedSlug("s")
                .purpose("p")
                .status(VillageRequestStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(pending, "id", requestId);

        Long otherUserId = 200L;
        given(requestRepository.findById(requestId)).willReturn(Optional.of(pending));
        given(userRoleRepository.existsSystemAdminByUserId(otherUserId)).willReturn(0L);

        assertThatThrownBy(() -> service.withdraw(requestId, otherUserId))
                .isInstanceOf(BusinessException.class);

        verify(requestRepository, never()).save(any());
    }

    // ---------------------------------------------------------------
    // 補足: 一般ユーザーが OFFICIAL を申請 → VILLAGE_028
    // ---------------------------------------------------------------
    @Test
    @DisplayName("11. 一般ユーザーが OFFICIAL 申請 — VILLAGE_028")
    void createRequest_officialForbiddenForNonAdmin() {
        VillageCreationRequestCreateRequest req = new VillageCreationRequestCreateRequest(
                "公式村", "official-slug", null, "p",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5),
                VillageType.OFFICIAL, null);
        given(userRoleRepository.existsSystemAdminByUserId(REQUESTER_ID)).willReturn(0L);

        assertThatThrownBy(() -> service.createRequest(REQUESTER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.OFFICIAL_VILLAGE_FORBIDDEN);
    }
}
