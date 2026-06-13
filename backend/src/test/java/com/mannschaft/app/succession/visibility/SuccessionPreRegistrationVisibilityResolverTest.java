package com.mannschaft.app.succession.visibility;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.ReferenceType;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link SuccessionPreRegistrationVisibilityResolver} のユニットテスト（Phase3b E-1）。
 *
 * <p>seal_status × TTL × 承認者集合の組合せで F00 可視性判定を網羅する。
 * <b>現状の実装挙動の固定</b>が目的（仕様変更しない）。
 *
 * <p>仕様:
 * <ul>
 *   <li>ADMIN は常に可視</li>
 *   <li>SEALED / RE_SEALED: 非 ADMIN は不可視</li>
 *   <li>UNSEAL_REQUESTED: 申請者・一次承認者が可視（unseal 完了不要）</li>
 *   <li>UNSEALED（TTL 内）: 申請者・一次・二次承認者（完了済み）が可視</li>
 *   <li>UNSEALED（TTL 超過 / NULL）: fail-closed</li>
 *   <li>否決済み（rejectedAt 非 NULL）申請はスキップして直近有効申請のみ確認</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SuccessionPreRegistrationVisibilityResolver")
class SuccessionPreRegistrationVisibilityResolverTest {

    @Mock
    private SuccessionPreRegistrationRepository preRegRepo;
    @Mock
    private UnsealRequestRepository unsealRequestRepo;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private SuccessionPreRegistrationVisibilityResolver resolver;

    private static final Long ORG_ID = 100L;
    private static final Long REQUESTER_ID = 401L;
    private static final Long FIRST_APPROVER_ID = 402L;
    private static final Long SECOND_APPROVER_ID = 403L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long ADMIN_USER_ID = 555L;

    @Test
    @DisplayName("referenceType() は SUCCESSION_PRE_REGISTRATION を返す")
    void referenceType_returnsExpected() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.SUCCESSION_PRE_REGISTRATION);
    }

    // ─── Long 経路（fail-closed） ───────────────────────────────

    @Test
    @DisplayName("Long 経路 canView は常に false（UUID 専用）")
    void canView_longPath_false() {
        assertThat(resolver.canView(123L, REQUESTER_ID)).isFalse();
    }

    @Test
    @DisplayName("Long 経路 filterAccessible は常に空集合（UUID 専用）")
    void filterAccessible_longPath_empty() {
        assertThat(resolver.filterAccessible(List.of(1L, 2L), REQUESTER_ID)).isEmpty();
    }

    // ─── null / not-found / deleted（fail-closed） ──────────────

    @Test
    @DisplayName("contentId が null なら false")
    void canViewUuid_nullContentId_false() {
        assertThat(resolver.canViewUuid(null, REQUESTER_ID)).isFalse();
    }

    @Test
    @DisplayName("viewerUserId が null なら false")
    void canViewUuid_nullViewer_false() {
        assertThat(resolver.canViewUuid(UUID.randomUUID(), null)).isFalse();
    }

    @Test
    @DisplayName("不存在なら false（NOT_FOUND fail-closed）")
    void canViewUuid_notFound_false() {
        UUID id = UUID.randomUUID();
        when(preRegRepo.findById(id)).thenReturn(Optional.empty());
        assertThat(resolver.canViewUuid(id, REQUESTER_ID)).isFalse();
    }

    @Test
    @DisplayName("削除済み（deletedAt 非 NULL）なら誰でも false")
    void canViewUuid_deleted_false() {
        UUID id = UUID.randomUUID();
        SuccessionPreRegistrationEntity preReg = buildPreReg(id, "UNSEALED",
                LocalDateTime.now().plusHours(1));
        preReg.setDeletedAt(LocalDateTime.now());
        when(preRegRepo.findById(id)).thenReturn(Optional.of(preReg));

        assertThat(resolver.canViewUuid(id, REQUESTER_ID)).isFalse();
    }

    // ─── ADMIN ─────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN は SEALED でも常に可視")
    void admin_alwaysVisible_evenSealed() {
        UUID id = UUID.randomUUID();
        SuccessionPreRegistrationEntity preReg = buildPreReg(id, "SEALED", null);
        when(preRegRepo.findById(id)).thenReturn(Optional.of(preReg));
        when(accessControlService.isAdminOrAbove(ADMIN_USER_ID, ORG_ID, "ORGANIZATION"))
                .thenReturn(true);

        assertThat(resolver.canViewUuid(id, ADMIN_USER_ID)).isTrue();
    }

    // ─── SEALED / RE_SEALED（非 ADMIN は不可視） ─────────────────

    @Nested
    @DisplayName("SEALED / RE_SEALED")
    class SealedStates {

        @Test
        @DisplayName("SEALED は非 ADMIN なら申請者でも false")
        void sealed_nonAdmin_false() {
            UUID id = setupNonAdmin("SEALED", null);
            assertThat(resolver.canViewUuid(id, REQUESTER_ID)).isFalse();
        }

        @Test
        @DisplayName("RE_SEALED は非 ADMIN なら申請者でも false")
        void reSealed_nonAdmin_false() {
            UUID id = setupNonAdmin("RE_SEALED", null);
            assertThat(resolver.canViewUuid(id, REQUESTER_ID)).isFalse();
        }

        @Test
        @DisplayName("未知の seal_status は default で false")
        void unknownStatus_false() {
            UUID id = setupNonAdmin("FROZEN", null);
            assertThat(resolver.canViewUuid(id, REQUESTER_ID)).isFalse();
        }

        @Test
        @DisplayName("seal_status が null なら false")
        void nullStatus_false() {
            UUID id = setupNonAdmin(null, null);
            assertThat(resolver.canViewUuid(id, REQUESTER_ID)).isFalse();
        }
    }

    // ─── UNSEAL_REQUESTED（TTL 無関係・完了不要） ────────────────

    @Nested
    @DisplayName("UNSEAL_REQUESTED")
    class UnsealRequested {

        @Test
        @DisplayName("申請者は可視（unseal 完了不要）")
        void requester_visible() {
            UUID id = setupNonAdmin("UNSEAL_REQUESTED", null);
            mockActiveRequest(id, null);
            assertThat(resolver.canViewUuid(id, REQUESTER_ID)).isTrue();
        }

        @Test
        @DisplayName("一次承認者は可視（unseal 完了不要）")
        void firstApprover_visible() {
            UUID id = setupNonAdmin("UNSEAL_REQUESTED", null);
            mockActiveRequest(id, null);
            assertThat(resolver.canViewUuid(id, FIRST_APPROVER_ID)).isTrue();
        }

        @Test
        @DisplayName("二次承認者は unseal 未完了なら不可視（UNSEAL_REQUESTED 段階）")
        void secondApprover_notVisibleBeforeCompletion() {
            UUID id = setupNonAdmin("UNSEAL_REQUESTED", null);
            mockActiveRequest(id, null);
            assertThat(resolver.canViewUuid(id, SECOND_APPROVER_ID)).isFalse();
        }

        @Test
        @DisplayName("他人は不可視")
        void other_notVisible() {
            UUID id = setupNonAdmin("UNSEAL_REQUESTED", null);
            mockActiveRequest(id, null);
            assertThat(resolver.canViewUuid(id, OTHER_USER_ID)).isFalse();
        }
    }

    // ─── UNSEALED × TTL ─────────────────────────────────────────

    @Nested
    @DisplayName("UNSEALED × TTL")
    class UnsealedTtl {

        @Test
        @DisplayName("TTL 内 + 申請者 → 可視")
        void inTtl_requester_visible() {
            UUID id = setupNonAdmin("UNSEALED", LocalDateTime.now().plusHours(1));
            mockActiveRequest(id, LocalDateTime.now().minusHours(1));
            assertThat(resolver.canViewUuid(id, REQUESTER_ID)).isTrue();
        }

        @Test
        @DisplayName("TTL 内 + 二次承認者（完了済み）→ 可視")
        void inTtl_secondApprover_visible() {
            UUID id = setupNonAdmin("UNSEALED", LocalDateTime.now().plusHours(1));
            mockActiveRequest(id, LocalDateTime.now().minusHours(1));
            assertThat(resolver.canViewUuid(id, SECOND_APPROVER_ID)).isTrue();
        }

        @Test
        @DisplayName("TTL 超過（過去）→ 申請者でも fail-closed false")
        void ttlExpired_requester_false() {
            UUID id = setupNonAdmin("UNSEALED", LocalDateTime.now().minusSeconds(10));
            mockActiveRequest(id, LocalDateTime.now().minusHours(1));
            assertThat(resolver.canViewUuid(id, REQUESTER_ID)).isFalse();
        }

        @Test
        @DisplayName("TTL が NULL → 申請者でも fail-closed false")
        void ttlNull_requester_false() {
            UUID id = setupNonAdmin("UNSEALED", null);
            mockActiveRequest(id, LocalDateTime.now().minusHours(1));
            assertThat(resolver.canViewUuid(id, REQUESTER_ID)).isFalse();
        }
    }

    // ─── 否決済み申請のスキップ ─────────────────────────────────

    @Test
    @DisplayName("直近が否決済み（rejectedAt 非 NULL）はスキップし、次の有効申請で判定する")
    void rejectedRequest_skipped() {
        UUID id = setupNonAdmin("UNSEAL_REQUESTED", null);
        UnsealRequestEntity rejected = buildRequest(id, null);
        rejected.setRejectedAt(LocalDateTime.now());
        // 別ユーザー（OTHER）が申請者の有効申請
        UnsealRequestEntity active = buildRequest(id, null);
        active.setRequestedBy(REQUESTER_ID);
        when(unsealRequestRepo.findByPreRegistrationIdAndDeletedAtIsNullOrderByCreatedAtDesc(id))
                .thenReturn(List.of(rejected, active));

        assertThat(resolver.canViewUuid(id, REQUESTER_ID)).isTrue();
    }

    // ─── filterAccessibleUuid ───────────────────────────────────

    @Test
    @DisplayName("filterAccessibleUuid: 可視のものだけ抽出（削除済み・不可視は除外）")
    void filterAccessibleUuid_extractsVisibleOnly() {
        UUID visible = UUID.randomUUID();
        UUID sealed = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();

        SuccessionPreRegistrationEntity vis = buildPreReg(visible, "UNSEAL_REQUESTED", null);
        SuccessionPreRegistrationEntity seal = buildPreReg(sealed, "SEALED", null);
        SuccessionPreRegistrationEntity del = buildPreReg(deleted, "UNSEAL_REQUESTED", null);
        del.setDeletedAt(LocalDateTime.now());

        when(preRegRepo.findAllById(List.of(visible, sealed, deleted)))
                .thenReturn(List.of(vis, seal, del));
        when(accessControlService.isAdminOrAbove(REQUESTER_ID, ORG_ID, "ORGANIZATION"))
                .thenReturn(false);
        mockActiveRequest(visible, null);

        Set<UUID> result = resolver.filterAccessibleUuid(
                List.of(visible, sealed, deleted), REQUESTER_ID);

        assertThat(result).containsExactly(visible);
    }

    @Test
    @DisplayName("filterAccessibleUuid: 空入力なら空集合")
    void filterAccessibleUuid_empty() {
        assertThat(resolver.filterAccessibleUuid(List.of(), REQUESTER_ID)).isEmpty();
    }

    @Test
    @DisplayName("filterAccessibleUuid: viewer が null なら空集合")
    void filterAccessibleUuid_nullViewer_empty() {
        assertThat(resolver.filterAccessibleUuid(List.of(UUID.randomUUID()), null)).isEmpty();
    }

    // ─── ヘルパー ───────────────────────────────────────────────

    /** 非 ADMIN 前提（isAdminOrAbove=false）の事前登録を仕込む。 */
    private UUID setupNonAdmin(String sealStatus, LocalDateTime autoResealAt) {
        UUID id = UUID.randomUUID();
        SuccessionPreRegistrationEntity preReg = buildPreReg(id, sealStatus, autoResealAt);
        when(preRegRepo.findById(id)).thenReturn(Optional.of(preReg));
        lenient().when(accessControlService.isAdminOrAbove(REQUESTER_ID, ORG_ID, "ORGANIZATION"))
                .thenReturn(false);
        lenient().when(accessControlService.isAdminOrAbove(FIRST_APPROVER_ID, ORG_ID, "ORGANIZATION"))
                .thenReturn(false);
        lenient().when(accessControlService.isAdminOrAbove(SECOND_APPROVER_ID, ORG_ID, "ORGANIZATION"))
                .thenReturn(false);
        lenient().when(accessControlService.isAdminOrAbove(OTHER_USER_ID, ORG_ID, "ORGANIZATION"))
                .thenReturn(false);
        return id;
    }

    private SuccessionPreRegistrationEntity buildPreReg(UUID id, String sealStatus,
                                                        LocalDateTime autoResealAt) {
        SuccessionPreRegistrationEntity preReg = SuccessionPreRegistrationEntity.builder()
                .organizationId(ORG_ID)
                .dwellingUnitId(200L)
                .residentRegistryId(300L)
                .ownerUserId(REQUESTER_ID)
                .sealStatus(sealStatus)
                .autoResealAt(autoResealAt)
                .build();
        setField(preReg, "id", id);
        return preReg;
    }

    private void mockActiveRequest(UUID preRegId, LocalDateTime unsealCompletedAt) {
        UnsealRequestEntity req = buildRequest(preRegId, unsealCompletedAt);
        lenient().when(unsealRequestRepo
                        .findByPreRegistrationIdAndDeletedAtIsNullOrderByCreatedAtDesc(preRegId))
                .thenReturn(List.of(req));
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
