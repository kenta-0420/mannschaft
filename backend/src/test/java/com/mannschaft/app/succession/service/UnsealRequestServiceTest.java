package com.mannschaft.app.succession.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.succession.SuccessionErrorCode;
import com.mannschaft.app.succession.entity.SuccessionPreRegistrationEntity;
import com.mannschaft.app.succession.entity.UnsealRequestEntity;
import com.mannschaft.app.succession.repository.SuccessionPreRegistrationRepository;
import com.mannschaft.app.succession.repository.UnsealRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.temporal.ChronoUnit;

/**
 * {@link UnsealRequestService} のユニットテスト（F09.15 S2-D）。
 *
 * <p>外部依存（Repository/AccessControl/RoleService）はすべて Mockito スタブ化する。
 * テナント分離は {@code findByIdAndOrganizationIdAndDeletedAtIsNull} の呼び出しを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UnsealRequestService")
class UnsealRequestServiceTest {

    @Mock
    private SuccessionPreRegistrationRepository preRegRepo;
    @Mock
    private UnsealRequestRepository unsealRequestRepo;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private RoleService roleService;

    @InjectMocks
    private UnsealRequestService service;

    static final Long ORG_ID = 100L;
    static final Long USER_A = 1001L;  // 申請者
    static final Long USER_B = 1002L;  // 一次承認者
    static final Long USER_C = 1003L;  // 二次承認者
    static final Long USER_OTHER = 9999L;  // 部外者

    // ─── requestUnseal ──────────────────────────────────

    @Nested
    @DisplayName("requestUnseal")
    class RequestUnseal {

        private UUID preRegId;
        private SuccessionPreRegistrationEntity sealedPreReg;

        @BeforeEach
        void setUp() {
            preRegId = UUID.randomUUID();
            sealedPreReg = SuccessionPreRegistrationEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(200L)
                    .residentRegistryId(300L)
                    .ownerUserId(USER_A)
                    .sealStatus("SEALED")
                    .build();
            setField(sealedPreReg, "id", preRegId);
        }

        @Test
        @DisplayName("正常系: SEALED な事前登録に対して申請が起票され UNSEAL_REQUESTED に遷移する")
        void requestUnseal_success() {
            // MANAGE_SUCCESSION_UNSEAL 権限あり
            when(roleService.hasPermission(USER_A, ORG_ID, "ORGANIZATION", "MANAGE_SUCCESSION_UNSEAL"))
                    .thenReturn(true);
            when(preRegRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(preRegId, ORG_ID))
                    .thenReturn(Optional.of(sealedPreReg));

            UUID savedId = UUID.randomUUID();
            when(unsealRequestRepo.save(any(UnsealRequestEntity.class)))
                    .thenAnswer(inv -> {
                        UnsealRequestEntity e = inv.getArgument(0);
                        setField(e, "id", savedId);
                        return e;
                    });
            when(preRegRepo.save(any(SuccessionPreRegistrationEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UUID result = service.requestUnseal(ORG_ID, USER_A, preRegId, "相続調査のため");

            assertThat(result).isEqualTo(savedId);

            // sealStatus が UNSEAL_REQUESTED に遷移していること
            ArgumentCaptor<SuccessionPreRegistrationEntity> preRegCaptor =
                    ArgumentCaptor.forClass(SuccessionPreRegistrationEntity.class);
            verify(preRegRepo).save(preRegCaptor.capture());
            assertThat(preRegCaptor.getValue().getSealStatus()).isEqualTo("UNSEAL_REQUESTED");

            // UnsealRequestEntity の申請者・理由が正しくセットされていること
            ArgumentCaptor<UnsealRequestEntity> reqCaptor =
                    ArgumentCaptor.forClass(UnsealRequestEntity.class);
            verify(unsealRequestRepo).save(reqCaptor.capture());
            assertThat(reqCaptor.getValue().getRequestedBy()).isEqualTo(USER_A);
            assertThat(reqCaptor.getValue().getRequestReason()).isEqualTo("相続調査のため");
        }

        @Test
        @DisplayName("PRE_REGISTRATION_NOT_FOUND: 事前登録が存在しない場合")
        void requestUnseal_preReg_not_found() {
            when(roleService.hasPermission(USER_A, ORG_ID, "ORGANIZATION", "MANAGE_SUCCESSION_UNSEAL"))
                    .thenReturn(true);
            when(preRegRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(preRegId, ORG_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requestUnseal(ORG_ID, USER_A, preRegId, "理由"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.PRE_REGISTRATION_NOT_FOUND);

            verify(unsealRequestRepo, never()).save(any());
        }

        @Test
        @DisplayName("PRE_REGISTRATION_NOT_SEALED: 事前登録が SEALED 状態でない場合")
        void requestUnseal_already_unseal_requested() {
            when(roleService.hasPermission(USER_A, ORG_ID, "ORGANIZATION", "MANAGE_SUCCESSION_UNSEAL"))
                    .thenReturn(true);

            SuccessionPreRegistrationEntity notSealed = SuccessionPreRegistrationEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(200L)
                    .residentRegistryId(300L)
                    .ownerUserId(USER_A)
                    .sealStatus("UNSEAL_REQUESTED")  // 既に申請中
                    .build();
            setField(notSealed, "id", preRegId);

            when(preRegRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(preRegId, ORG_ID))
                    .thenReturn(Optional.of(notSealed));

            assertThatThrownBy(() -> service.requestUnseal(ORG_ID, USER_A, preRegId, "理由"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.PRE_REGISTRATION_NOT_SEALED);
        }

        @Test
        @DisplayName("UNSEAL_ACCESS_DENIED: isAdminOrAbove=false かつ hasPermission=false の場合")
        void requestUnseal_access_denied() {
            when(accessControlService.isAdminOrAbove(USER_OTHER, ORG_ID, "ORGANIZATION"))
                    .thenReturn(false);
            when(roleService.hasPermission(USER_OTHER, ORG_ID, "ORGANIZATION", "MANAGE_SUCCESSION_UNSEAL"))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.requestUnseal(ORG_ID, USER_OTHER, preRegId, "理由"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.UNSEAL_ACCESS_DENIED);

            verify(preRegRepo, never()).findByIdAndOrganizationIdAndDeletedAtIsNull(any(), anyLong());
        }
    }

    // ─── approve ────────────────────────────────────────

    @Nested
    @DisplayName("approve")
    class Approve {

        private UUID unsealReqId;
        private UnsealRequestEntity pendingRequest;

        @BeforeEach
        void setUp() {
            unsealReqId = UUID.randomUUID();
            pendingRequest = UnsealRequestEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(200L)
                    .residentRegistryId(300L)
                    .preRegistrationId(UUID.randomUUID())
                    .requestedBy(USER_A)
                    .requestReason("相続調査のため")
                    .build();
            setField(pendingRequest, "id", unsealReqId);
        }

        @Test
        @DisplayName("正常系: USER_B が USER_A の申請を一次承認すると firstApproverUserId がセットされる")
        void approve_success() {
            when(roleService.hasPermission(USER_B, ORG_ID, "ORGANIZATION", "MANAGE_SUCCESSION_UNSEAL"))
                    .thenReturn(true);
            when(unsealRequestRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(unsealReqId, ORG_ID))
                    .thenReturn(Optional.of(pendingRequest));
            when(unsealRequestRepo.save(any(UnsealRequestEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.approve(ORG_ID, USER_B, unsealReqId);

            ArgumentCaptor<UnsealRequestEntity> captor =
                    ArgumentCaptor.forClass(UnsealRequestEntity.class);
            verify(unsealRequestRepo).save(captor.capture());
            assertThat(captor.getValue().getFirstApproverUserId()).isEqualTo(USER_B);
            assertThat(captor.getValue().getFirstApprovedAt()).isNotNull();
        }

        @Test
        @DisplayName("APPROVER_CONFLICT: 申請者本人が一次承認しようとすると例外")
        void approve_self_conflict() {
            when(roleService.hasPermission(USER_A, ORG_ID, "ORGANIZATION", "MANAGE_SUCCESSION_UNSEAL"))
                    .thenReturn(true);
            when(unsealRequestRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(unsealReqId, ORG_ID))
                    .thenReturn(Optional.of(pendingRequest));

            assertThatThrownBy(() -> service.approve(ORG_ID, USER_A, unsealReqId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.APPROVER_CONFLICT);

            verify(unsealRequestRepo, never()).save(any());
        }
    }

    // ─── secondApprove ──────────────────────────────────

    @Nested
    @DisplayName("secondApprove")
    class SecondApprove {

        private UUID unsealReqId;
        private UUID preRegId;

        @BeforeEach
        void setUp() {
            unsealReqId = UUID.randomUUID();
            preRegId = UUID.randomUUID();
        }

        private UnsealRequestEntity buildFirstApprovedRequest() {
            UnsealRequestEntity req = UnsealRequestEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(200L)
                    .residentRegistryId(300L)
                    .preRegistrationId(preRegId)
                    .requestedBy(USER_A)
                    .requestReason("相続調査のため")
                    .firstApproverUserId(USER_B)
                    .firstApprovedAt(LocalDateTime.now().minusMinutes(5))
                    .build();
            setField(req, "id", unsealReqId);
            return req;
        }

        @Test
        @DisplayName("正常系: USER_C が二次承認すると sealStatus=UNSEALED・autoResealAt が NOW+72h にセットされる")
        void secondApprove_success() {
            when(roleService.hasPermission(USER_C, ORG_ID, "ORGANIZATION", "MANAGE_SUCCESSION_UNSEAL"))
                    .thenReturn(true);
            when(unsealRequestRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(unsealReqId, ORG_ID))
                    .thenReturn(Optional.of(buildFirstApprovedRequest()));
            when(unsealRequestRepo.save(any(UnsealRequestEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            SuccessionPreRegistrationEntity preReg = SuccessionPreRegistrationEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(200L)
                    .residentRegistryId(300L)
                    .ownerUserId(USER_A)
                    .sealStatus("UNSEAL_REQUESTED")
                    .build();
            setField(preReg, "id", preRegId);
            when(preRegRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(preRegId, ORG_ID))
                    .thenReturn(Optional.of(preReg));
            when(preRegRepo.save(any(SuccessionPreRegistrationEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.secondApprove(ORG_ID, USER_C, unsealReqId);

            // UnsealRequestEntity の更新を検証
            ArgumentCaptor<UnsealRequestEntity> reqCaptor =
                    ArgumentCaptor.forClass(UnsealRequestEntity.class);
            verify(unsealRequestRepo).save(reqCaptor.capture());
            UnsealRequestEntity savedReq = reqCaptor.getValue();
            assertThat(savedReq.getSecondApproverUserId()).isEqualTo(USER_C);
            assertThat(savedReq.getSecondApprovedAt()).isNotNull();
            assertThat(savedReq.getUnsealCompletedAt()).isNotNull();
            assertThat(savedReq.getAutoResealAt())
                    .isCloseTo(LocalDateTime.now().plusHours(72), within(10, ChronoUnit.SECONDS));

            // SuccessionPreRegistration の更新を検証
            ArgumentCaptor<SuccessionPreRegistrationEntity> preRegCaptor =
                    ArgumentCaptor.forClass(SuccessionPreRegistrationEntity.class);
            verify(preRegRepo).save(preRegCaptor.capture());
            assertThat(preRegCaptor.getValue().getSealStatus()).isEqualTo("UNSEALED");
            assertThat(preRegCaptor.getValue().getAutoResealAt()).isNotNull();
        }

        @Test
        @DisplayName("FIRST_APPROVER_REQUIRED: 一次承認が未完了の場合")
        void secondApprove_first_approver_required() {
            when(roleService.hasPermission(USER_C, ORG_ID, "ORGANIZATION", "MANAGE_SUCCESSION_UNSEAL"))
                    .thenReturn(true);

            UnsealRequestEntity noFirstApproval = UnsealRequestEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(200L)
                    .residentRegistryId(300L)
                    .preRegistrationId(preRegId)
                    .requestedBy(USER_A)
                    .requestReason("理由")
                    // firstApproverUserId が null
                    .build();
            setField(noFirstApproval, "id", unsealReqId);
            when(unsealRequestRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(unsealReqId, ORG_ID))
                    .thenReturn(Optional.of(noFirstApproval));

            assertThatThrownBy(() -> service.secondApprove(ORG_ID, USER_C, unsealReqId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.FIRST_APPROVER_REQUIRED);
        }

        @Test
        @DisplayName("APPROVER_CONFLICT: 申請者(USER_A)が二次承認しようとすると例外")
        void secondApprove_conflict_with_requester() {
            when(roleService.hasPermission(USER_A, ORG_ID, "ORGANIZATION", "MANAGE_SUCCESSION_UNSEAL"))
                    .thenReturn(true);
            when(unsealRequestRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(unsealReqId, ORG_ID))
                    .thenReturn(Optional.of(buildFirstApprovedRequest()));

            assertThatThrownBy(() -> service.secondApprove(ORG_ID, USER_A, unsealReqId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.APPROVER_CONFLICT);
        }

        @Test
        @DisplayName("APPROVER_CONFLICT: 一次承認者(USER_B)が二次承認しようとすると例外")
        void secondApprove_conflict_with_first_approver() {
            when(roleService.hasPermission(USER_B, ORG_ID, "ORGANIZATION", "MANAGE_SUCCESSION_UNSEAL"))
                    .thenReturn(true);
            when(unsealRequestRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(unsealReqId, ORG_ID))
                    .thenReturn(Optional.of(buildFirstApprovedRequest()));

            assertThatThrownBy(() -> service.secondApprove(ORG_ID, USER_B, unsealReqId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.APPROVER_CONFLICT);
        }
    }

    // ─── cancel ─────────────────────────────────────────

    @Nested
    @DisplayName("cancel")
    class Cancel {

        private UUID unsealReqId;
        private UUID preRegId;
        private UnsealRequestEntity pendingRequest;

        @BeforeEach
        void setUp() {
            unsealReqId = UUID.randomUUID();
            preRegId = UUID.randomUUID();
            pendingRequest = UnsealRequestEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(200L)
                    .residentRegistryId(300L)
                    .preRegistrationId(preRegId)
                    .requestedBy(USER_A)
                    .requestReason("理由")
                    .build();
            setField(pendingRequest, "id", unsealReqId);
        }

        @Test
        @DisplayName("正常系（申請者本人）: キャンセルすると sealStatus が SEALED に戻る")
        void cancel_by_requester_success() {
            when(unsealRequestRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(unsealReqId, ORG_ID))
                    .thenReturn(Optional.of(pendingRequest));
            when(unsealRequestRepo.save(any(UnsealRequestEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            SuccessionPreRegistrationEntity preReg = SuccessionPreRegistrationEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(200L)
                    .residentRegistryId(300L)
                    .ownerUserId(USER_A)
                    .sealStatus("UNSEAL_REQUESTED")
                    .build();
            setField(preReg, "id", preRegId);
            when(preRegRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(preRegId, ORG_ID))
                    .thenReturn(Optional.of(preReg));
            when(preRegRepo.save(any(SuccessionPreRegistrationEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.cancel(ORG_ID, USER_A, unsealReqId);

            ArgumentCaptor<SuccessionPreRegistrationEntity> preRegCaptor =
                    ArgumentCaptor.forClass(SuccessionPreRegistrationEntity.class);
            verify(preRegRepo).save(preRegCaptor.capture());
            assertThat(preRegCaptor.getValue().getSealStatus()).isEqualTo("SEALED");

            ArgumentCaptor<UnsealRequestEntity> reqCaptor =
                    ArgumentCaptor.forClass(UnsealRequestEntity.class);
            verify(unsealRequestRepo).save(reqCaptor.capture());
            assertThat(reqCaptor.getValue().getRejectedAt()).isNotNull();
            assertThat(reqCaptor.getValue().getRejectedBy()).isEqualTo(USER_A);
        }

        @Test
        @DisplayName("UNSEAL_ACCESS_DENIED: 部外者かつ非 ADMIN がキャンセルしようとすると例外")
        void cancel_access_denied() {
            when(unsealRequestRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(unsealReqId, ORG_ID))
                    .thenReturn(Optional.of(pendingRequest));
            when(accessControlService.isAdminOrAbove(USER_OTHER, ORG_ID, "ORGANIZATION"))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.cancel(ORG_ID, USER_OTHER, unsealReqId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.UNSEAL_ACCESS_DENIED);
        }
    }

    // ─── getById ────────────────────────────────────────

    @Nested
    @DisplayName("getById")
    class GetById {

        private UUID unsealReqId;
        private UnsealRequestEntity requestEntity;

        @BeforeEach
        void setUp() {
            unsealReqId = UUID.randomUUID();
            requestEntity = UnsealRequestEntity.builder()
                    .organizationId(ORG_ID)
                    .dwellingUnitId(200L)
                    .residentRegistryId(300L)
                    .preRegistrationId(UUID.randomUUID())
                    .requestedBy(USER_A)
                    .requestReason("理由")
                    .firstApproverUserId(USER_B)
                    .build();
            setField(requestEntity, "id", unsealReqId);
        }

        @Test
        @DisplayName("申請者本人なら取得可能")
        void requester_can_get() {
            when(unsealRequestRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(unsealReqId, ORG_ID))
                    .thenReturn(Optional.of(requestEntity));

            UnsealRequestEntity result = service.getById(ORG_ID, USER_A, unsealReqId);
            assertThat(result.getId()).isEqualTo(unsealReqId);
        }

        @Test
        @DisplayName("一次承認者なら取得可能")
        void first_approver_can_get() {
            when(unsealRequestRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(unsealReqId, ORG_ID))
                    .thenReturn(Optional.of(requestEntity));

            UnsealRequestEntity result = service.getById(ORG_ID, USER_B, unsealReqId);
            assertThat(result.getId()).isEqualTo(unsealReqId);
        }

        @Test
        @DisplayName("UNSEAL_ACCESS_DENIED: 部外者かつ非 ADMIN は取得不可")
        void other_non_admin_denied() {
            when(unsealRequestRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(unsealReqId, ORG_ID))
                    .thenReturn(Optional.of(requestEntity));
            when(accessControlService.isAdminOrAbove(USER_OTHER, ORG_ID, "ORGANIZATION"))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.getById(ORG_ID, USER_OTHER, unsealReqId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SuccessionErrorCode.UNSEAL_ACCESS_DENIED);
        }
    }

    // ─── ヘルパー ──────────────────────────────────────

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
