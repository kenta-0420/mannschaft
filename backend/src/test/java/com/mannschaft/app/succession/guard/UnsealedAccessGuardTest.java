package com.mannschaft.app.succession.guard;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.succession.SuccessionErrorCode;
import com.mannschaft.app.succession.entity.SuccessionPreRegistrationEntity;
import com.mannschaft.app.succession.entity.UnsealRequestEntity;
import com.mannschaft.app.succession.repository.SuccessionPreRegistrationRepository;
import com.mannschaft.app.succession.repository.UnsealRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link UnsealedAccessGuard} のユニットテスト（Phase3b E-1）。
 *
 * <p>UNSEALED コンテンツの三層保護を網羅検証する。<b>現状の実装挙動の固定</b>が目的であり、
 * 仕様は変更しない（既存挙動どおりに PASS することを確認する）。
 *
 * <p>三層:
 * <ol>
 *   <li>Layer 1: seal_status が "UNSEALED" でなければ {@code UNSEAL_EXPIRED_OR_INACTIVE}</li>
 *   <li>Layer 2: auto_reseal_at が NULL または過去なら {@code UNSEAL_EXPIRED_OR_INACTIVE}（TTL）</li>
 *   <li>Layer 3: 承認者集合（申請者・一次・二次）∪ ADMIN でなければ {@code UNSEAL_ACCESS_DENIED}</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UnsealedAccessGuard 三層保護")
class UnsealedAccessGuardTest {

    @Mock
    private SuccessionPreRegistrationRepository preRegRepo;
    @Mock
    private UnsealRequestRepository unsealRequestRepo;
    @Mock
    private com.mannschaft.app.common.AccessControlService accessControlService;

    @InjectMocks
    private UnsealedAccessGuard guard;

    private static final Long ORG_ID = 100L;
    private static final Long REQUESTER_ID = 401L;
    private static final Long FIRST_APPROVER_ID = 402L;
    private static final Long SECOND_APPROVER_ID = 403L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long ADMIN_USER_ID = 555L;

    // ─────────────────────────────────────────────
    // Layer 0: NOT_FOUND
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("対象事前登録が存在しなければ PRE_REGISTRATION_NOT_FOUND")
    void notFound_throwsPreRegistrationNotFound() {
        UUID id = UUID.randomUUID();
        when(preRegRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(id, ORG_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.checkViewAccess(id, REQUESTER_ID, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SuccessionErrorCode.PRE_REGISTRATION_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // Layer 1: seal_status
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Layer 1: seal_status チェック")
    class Layer1SealStatus {

        @Test
        @DisplayName("SEALED 状態なら UNSEAL_EXPIRED_OR_INACTIVE")
        void sealed_throwsExpiredOrInactive() {
            UUID id = setup("SEALED", LocalDateTime.now().plusHours(72));

            assertThatThrownBy(() -> guard.checkViewAccess(id, REQUESTER_ID, ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SuccessionErrorCode.UNSEAL_EXPIRED_OR_INACTIVE);
        }

        @Test
        @DisplayName("RE_SEALED 状態なら UNSEAL_EXPIRED_OR_INACTIVE")
        void reSealed_throwsExpiredOrInactive() {
            UUID id = setup("RE_SEALED", LocalDateTime.now().plusHours(72));

            assertThatThrownBy(() -> guard.checkViewAccess(id, REQUESTER_ID, ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SuccessionErrorCode.UNSEAL_EXPIRED_OR_INACTIVE);
        }

        @Test
        @DisplayName("UNSEAL_REQUESTED 状態でも（UNSEALED でない以上）UNSEAL_EXPIRED_OR_INACTIVE")
        void unsealRequested_throwsExpiredOrInactive() {
            UUID id = setup("UNSEAL_REQUESTED", LocalDateTime.now().plusHours(72));

            assertThatThrownBy(() -> guard.checkViewAccess(id, REQUESTER_ID, ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SuccessionErrorCode.UNSEAL_EXPIRED_OR_INACTIVE);
        }
    }

    // ─────────────────────────────────────────────
    // Layer 2: TTL（auto_reseal_at）
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Layer 2: TTL（auto_reseal_at）チェック")
    class Layer2Ttl {

        @Test
        @DisplayName("UNSEALED かつ auto_reseal_at が NULL なら UNSEAL_EXPIRED_OR_INACTIVE")
        void ttlNull_throwsExpiredOrInactive() {
            UUID id = setup("UNSEALED", null);

            assertThatThrownBy(() -> guard.checkViewAccess(id, REQUESTER_ID, ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SuccessionErrorCode.UNSEAL_EXPIRED_OR_INACTIVE);
        }

        @Test
        @DisplayName("UNSEALED かつ auto_reseal_at が過去（期限切れ）なら UNSEAL_EXPIRED_OR_INACTIVE")
        void ttlExpired_throwsExpiredOrInactive() {
            UUID id = setup("UNSEALED", LocalDateTime.now().minusSeconds(10));

            assertThatThrownBy(() -> guard.checkViewAccess(id, REQUESTER_ID, ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SuccessionErrorCode.UNSEAL_EXPIRED_OR_INACTIVE);
        }

        @Test
        @DisplayName("UNSEALED かつ auto_reseal_at が未来（期限内）なら Layer 2 を通過する（Layer 3 へ進む）")
        void ttlInFuture_passesLayer2() {
            UUID id = setup("UNSEALED", LocalDateTime.now().plusHours(1));
            // Layer 3 で承認者にも ADMIN にも該当しない → UNSEAL_ACCESS_DENIED で止まる
            // （= Layer 2 は通過していることの裏付け）
            when(accessControlService.isAdminOrAbove(OTHER_USER_ID, ORG_ID, "ORGANIZATION"))
                    .thenReturn(false);
            mockRequests(id);

            assertThatThrownBy(() -> guard.checkViewAccess(id, OTHER_USER_ID, ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SuccessionErrorCode.UNSEAL_ACCESS_DENIED);
        }
    }

    // ─────────────────────────────────────────────
    // Layer 3: 承認者集合 ∪ ADMIN
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Layer 3: 承認者集合 ∪ ADMIN")
    class Layer3Authorization {

        @Test
        @DisplayName("ADMIN は承認者でなくとも常に可（例外なし）")
        void admin_alwaysAllowed() {
            UUID id = setup("UNSEALED", LocalDateTime.now().plusHours(1));
            when(accessControlService.isAdminOrAbove(ADMIN_USER_ID, ORG_ID, "ORGANIZATION"))
                    .thenReturn(true);

            // 例外がスローされないこと
            guard.checkViewAccess(id, ADMIN_USER_ID, ORG_ID);
            assertThat(guard.canView(id, ADMIN_USER_ID, ORG_ID)).isTrue();
        }

        @Test
        @DisplayName("申請者（requestedBy）は可（unseal 完了済み申請）")
        void requester_allowed() {
            UUID id = setup("UNSEALED", LocalDateTime.now().plusHours(1));
            when(accessControlService.isAdminOrAbove(REQUESTER_ID, ORG_ID, "ORGANIZATION"))
                    .thenReturn(false);
            mockRequests(id);

            guard.checkViewAccess(id, REQUESTER_ID, ORG_ID);
            assertThat(guard.canView(id, REQUESTER_ID, ORG_ID)).isTrue();
        }

        @Test
        @DisplayName("一次承認者（firstApprover）は可")
        void firstApprover_allowed() {
            UUID id = setup("UNSEALED", LocalDateTime.now().plusHours(1));
            when(accessControlService.isAdminOrAbove(FIRST_APPROVER_ID, ORG_ID, "ORGANIZATION"))
                    .thenReturn(false);
            mockRequests(id);

            assertThat(guard.canView(id, FIRST_APPROVER_ID, ORG_ID)).isTrue();
        }

        @Test
        @DisplayName("二次承認者（secondApprover）は可")
        void secondApprover_allowed() {
            UUID id = setup("UNSEALED", LocalDateTime.now().plusHours(1));
            when(accessControlService.isAdminOrAbove(SECOND_APPROVER_ID, ORG_ID, "ORGANIZATION"))
                    .thenReturn(false);
            mockRequests(id);

            assertThat(guard.canView(id, SECOND_APPROVER_ID, ORG_ID)).isTrue();
        }

        @Test
        @DisplayName("承認者集合に含まれない他人は UNSEAL_ACCESS_DENIED")
        void other_throwsAccessDenied() {
            UUID id = setup("UNSEALED", LocalDateTime.now().plusHours(1));
            when(accessControlService.isAdminOrAbove(OTHER_USER_ID, ORG_ID, "ORGANIZATION"))
                    .thenReturn(false);
            mockRequests(id);

            assertThatThrownBy(() -> guard.checkViewAccess(id, OTHER_USER_ID, ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SuccessionErrorCode.UNSEAL_ACCESS_DENIED);
        }

        @Test
        @DisplayName("unseal_completed_at が NULL の申請（未完了）は承認者集合として扱わない → 申請者でも DENIED")
        void incompleteRequest_notInApproverSet() {
            UUID id = setup("UNSEALED", LocalDateTime.now().plusHours(1));
            when(accessControlService.isAdminOrAbove(REQUESTER_ID, ORG_ID, "ORGANIZATION"))
                    .thenReturn(false);
            // unsealCompletedAt = null の申請のみ → isViewerAuthorized は最初の完了済みを探すが
            // 完了済みが無い（completed が null の req で break しない）。
            UnsealRequestEntity incomplete = buildRequest(id, null);
            when(unsealRequestRepo.findByPreRegistrationIdAndDeletedAtIsNullOrderByCreatedAtDesc(id))
                    .thenReturn(List.of(incomplete));

            assertThatThrownBy(() -> guard.checkViewAccess(id, REQUESTER_ID, ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SuccessionErrorCode.UNSEAL_ACCESS_DENIED);
        }
    }

    // ─────────────────────────────────────────────
    // canView（例外を投げない版）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("canView: 失敗時は例外を握りつぶして false を返す")
    void canView_returnsFalseOnFailure() {
        UUID id = setup("SEALED", LocalDateTime.now().plusHours(72));

        assertThat(guard.canView(id, REQUESTER_ID, ORG_ID)).isFalse();
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    /** 指定 seal_status / auto_reseal_at の事前登録をリポジトリモックに仕込む。 */
    private UUID setup(String sealStatus, LocalDateTime autoResealAt) {
        UUID id = UUID.randomUUID();
        SuccessionPreRegistrationEntity preReg = SuccessionPreRegistrationEntity.builder()
                .organizationId(ORG_ID)
                .dwellingUnitId(200L)
                .residentRegistryId(300L)
                .ownerUserId(REQUESTER_ID)
                .sealStatus(sealStatus)
                .autoResealAt(autoResealAt)
                .build();
        setField(preReg, "id", id);
        lenient().when(preRegRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(id, ORG_ID))
                .thenReturn(Optional.of(preReg));
        return id;
    }

    /** 申請者・一次承認者・二次承認者を持つ「完了済み」申請をモックする。 */
    private void mockRequests(UUID preRegId) {
        UnsealRequestEntity completed = buildRequest(preRegId, LocalDateTime.now().minusHours(1));
        lenient().when(unsealRequestRepo
                        .findByPreRegistrationIdAndDeletedAtIsNullOrderByCreatedAtDesc(preRegId))
                .thenReturn(List.of(completed));
    }

    private UnsealRequestEntity buildRequest(UUID preRegId, LocalDateTime unsealCompletedAt) {
        UnsealRequestEntity req = UnsealRequestEntity.builder()
                .organizationId(ORG_ID)
                .dwellingUnitId(200L)
                .residentRegistryId(300L)
                .preRegistrationId(preRegId)
                .requestedBy(REQUESTER_ID)
                .requestReason("test")
                .firstApproverUserId(FIRST_APPROVER_ID)
                .secondApproverUserId(SECOND_APPROVER_ID)
                .unsealCompletedAt(unsealCompletedAt)
                .build();
        setField(req, "id", UUID.randomUUID());
        return req;
    }

    private static void setField(Object target, String fieldName, Object value) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
