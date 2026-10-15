package com.mannschaft.app.payment;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import com.mannschaft.app.payment.repository.PaymentRequestRepository;
import com.mannschaft.app.payment.service.CreatePaymentRequestCommand;
import com.mannschaft.app.payment.service.PaymentRequestService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentRequestServiceTest {

    private static final Long ORG_ID = 500L;
    private static final Long TEAM_ID = 600L;
    private static final Long ADMIN_USER_ID = 700L;
    private static final UUID PAYEE_CONNECT_ID = UUID.fromString("018f7f32-42a0-7cc0-8e9e-f6e9db5ee2c1");

    @Mock
    private PaymentRequestRepository paymentRequestRepository;

    @Mock
    private ConnectAccountRepository connectAccountRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ConfirmableNotificationService confirmableNotificationService;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private PaymentRequestService service;

    @Test
    @DisplayName("正常系: 協会 ADMIN が DRAFT 起票し、着金先 Connect 口座を焼き付ける")
    void createは組織の準備済みConnect口座を使ってDRAFTを保存する() {
        ConnectAccountEntity payee = ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.ORG)
                .scopeId(ORG_ID)
                .payoutsEnabled(true)
                .build();
        payee.setId(PAYEE_CONNECT_ID);
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.ORG, ORG_ID))
                .willReturn(Optional.of(payee));
        given(paymentRequestRepository.save(any(PaymentRequestEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        PaymentRequestEntity result = service.create(
                ORG_ID,
                ADMIN_USER_ID,
                new CreatePaymentRequestCommand(
                        TEAM_ID, "league fee", "2026", 30000L, "JPY", null,
                        LocalDate.of(2026, 7, 31), null));

        assertThat(result.getStatus()).isEqualTo(PaymentRequestStatus.DRAFT);
        assertThat(result.getIssuerScopeKind()).isEqualTo(ScopeKind.ORG);
        assertThat(result.getPayerScopeKind()).isEqualTo(ScopeKind.TEAM);
        assertThat(result.getPayerScopeId()).isEqualTo(TEAM_ID);
        assertThat(result.getPayeeConnectAccountId()).isEqualTo(PAYEE_CONNECT_ID);
        assertThat(result.getFaceAmount()).isEqualTo(30000);
        verify(accessControlService).checkAdminOrAbove(ADMIN_USER_ID, ORG_ID, "ORGANIZATION");
    }

    @Test
    @DisplayName("異常系: 協会 ADMIN でない場合 403（権原なし）")
    void create権原なしで403() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkAdminOrAbove(ADMIN_USER_ID, ORG_ID, "ORGANIZATION");

        assertThatThrownBy(() -> service.create(ORG_ID, ADMIN_USER_ID, createCommand()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOR_THIS_TEAM);
        verify(paymentRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("異常系: 協会の Connect 口座が無いと CONNECT_NOT_READY（着金先不在）")
    void create着金先不在() {
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.ORG, ORG_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(ORG_ID, ADMIN_USER_ID, createCommand()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_CONNECT_NOT_READY);
    }

    @Test
    @DisplayName("正常系: 再請求は旧 CANCELLED 行の supersededById に新行を指す")
    void create再請求でsuperseded連結() {
        UUID oldId = UUID.randomUUID();
        givenReadyPayee();
        PaymentRequestEntity old = PaymentRequestEntity.builder()
                .organizationId(ORG_ID)
                .status(PaymentRequestStatus.CANCELLED)
                .build();
        old.setId(oldId);
        given(paymentRequestRepository.findByIdAndDeletedAtIsNull(oldId)).willReturn(Optional.of(old));
        given(paymentRequestRepository.save(any(PaymentRequestEntity.class))).willAnswer(invocation -> {
            PaymentRequestEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            return entity;
        });

        service.create(ORG_ID, ADMIN_USER_ID, new CreatePaymentRequestCommand(
                TEAM_ID, "リーグ参加費（再）", null, 30000L, "JPY", null,
                LocalDate.of(2026, 7, 31), oldId));

        assertThat(old.getSupersededById()).isNotNull();
    }

    @Test
    @DisplayName("異常系: supersede 対象が CANCELLED でないと INVALID_STATUS（循環防止）")
    void create非CANCELLEDをsupersedeで409() {
        UUID oldId = UUID.randomUUID();
        givenReadyPayee();
        PaymentRequestEntity old = PaymentRequestEntity.builder()
                .organizationId(ORG_ID)
                .status(PaymentRequestStatus.SENT)
                .build();
        old.setId(oldId);
        given(paymentRequestRepository.findByIdAndDeletedAtIsNull(oldId)).willReturn(Optional.of(old));

        assertThatThrownBy(() -> service.create(ORG_ID, ADMIN_USER_ID, new CreatePaymentRequestCommand(
                TEAM_ID, "再", null, 30000L, "JPY", null, LocalDate.of(2026, 7, 31), oldId)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_INVALID_STATUS);
    }

    @Nested
    @DisplayName("cancel（取消）")
    class Cancel {

        @Test
        void DRAFT取消成功() {
            PaymentRequestEntity request = requestWithStatus(PaymentRequestStatus.DRAFT);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(request.getId())).willReturn(Optional.of(request));
            given(paymentRequestRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            assertThat(service.cancel(ORG_ID, request.getId(), ADMIN_USER_ID).getStatus())
                    .isEqualTo(PaymentRequestStatus.CANCELLED);
        }

        @Test
        void PAID取消不可() {
            PaymentRequestEntity request = requestWithStatus(PaymentRequestStatus.PAID);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(request.getId())).willReturn(Optional.of(request));

            assertThatThrownBy(() -> service.cancel(ORG_ID, request.getId(), ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_ALREADY_PAID);
        }

        @Test
        void VIEWED取消不可() {
            PaymentRequestEntity request = requestWithStatus(PaymentRequestStatus.VIEWED);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(request.getId())).willReturn(Optional.of(request));

            assertThatThrownBy(() -> service.cancel(ORG_ID, request.getId(), ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_INVALID_STATUS);
        }

        @Test
        void 他テナント404() {
            PaymentRequestEntity request = requestWithStatus(PaymentRequestStatus.DRAFT);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(request.getId())).willReturn(Optional.of(request));

            assertThatThrownBy(() -> service.cancel(999L, request.getId(), ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("send（配信・第二波）")
    class Send {

        @Test
        void 配信成功() {
            PaymentRequestEntity request = draftRequest();
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(request.getId())).willReturn(Optional.of(request));
            given(userRoleRepository.findAdminUserIdsByTeamIds(List.of(TEAM_ID))).willReturn(List.of(11L, 12L));
            given(messageSource.getMessage(any(String.class), any(), any(), any())).willReturn("通知文言");
            given(confirmableNotificationService.send(
                    eq(com.mannschaft.app.membership.ScopeType.TEAM), eq(TEAM_ID), any(), any(),
                    eq(com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority.HIGH),
                    any(), any(), any(), any(), any(), eq(ADMIN_USER_ID), any()))
                    .willReturn(com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity.builder()
                            .id(9001L)
                            .build());
            given(paymentRequestRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            PaymentRequestEntity result = service.send(ORG_ID, request.getId(), ADMIN_USER_ID);

            assertThat(result.getStatus()).isEqualTo(PaymentRequestStatus.SENT);
            assertThat(result.getConfirmableNotificationId()).isEqualTo(9001L);
            assertThat(result.getSentAt()).isNotNull();
            ArgumentCaptor<List<Long>> recipients = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<java.time.LocalDateTime> deadline = ArgumentCaptor.forClass(java.time.LocalDateTime.class);
            ArgumentCaptor<String> actionUrl = ArgumentCaptor.forClass(String.class);
            verify(confirmableNotificationService).send(
                    eq(com.mannschaft.app.membership.ScopeType.TEAM), eq(TEAM_ID), any(), any(),
                    eq(com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority.HIGH),
                    deadline.capture(), any(), any(), actionUrl.capture(), any(), eq(ADMIN_USER_ID), recipients.capture());
            assertThat(recipients.getValue()).containsExactly(11L, 12L);
            assertThat(deadline.getValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 31));
            assertThat(actionUrl.getValue()).contains("/teams/" + TEAM_ID + "/payment-requests/" + request.getId());
        }

        @Test
        void send権原なしで403() {
            PaymentRequestEntity request = draftRequest();
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(request.getId())).willReturn(Optional.of(request));
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(ADMIN_USER_ID, ORG_ID, "ORGANIZATION");

            assertThatThrownBy(() -> service.send(ORG_ID, request.getId(), ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class);
            verify(confirmableNotificationService, never()).send(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        void DRAFT以外は409() {
            PaymentRequestEntity request = draftRequest();
            request.markAsSent(7L);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(request.getId())).willReturn(Optional.of(request));

            assertThatThrownBy(() -> service.send(ORG_ID, request.getId(), ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_INVALID_STATUS);
        }

        @Test
        void 受信者ゼロで409() {
            PaymentRequestEntity request = draftRequest();
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(request.getId())).willReturn(Optional.of(request));
            given(userRoleRepository.findAdminUserIdsByTeamIds(List.of(TEAM_ID))).willReturn(List.of());

            assertThatThrownBy(() -> service.send(ORG_ID, request.getId(), ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_NO_RECIPIENTS);
        }

        @Test
        void 他テナントsendは404() {
            PaymentRequestEntity request = draftRequest();
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(request.getId())).willReturn(Optional.of(request));

            assertThatThrownBy(() -> service.send(999L, request.getId(), ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("viewByTeam（詳細・VIEWED 遷移・第二波）")
    class ViewByTeam {

        @Test
        void 初閲覧でVIEWED() {
            PaymentRequestEntity request = requestWithStatus(PaymentRequestStatus.SENT);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(request.getId())).willReturn(Optional.of(request));
            given(paymentRequestRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            assertThat(service.viewByTeam(TEAM_ID, request.getId(), ADMIN_USER_ID).getStatus())
                    .isEqualTo(PaymentRequestStatus.VIEWED);
            assertThat(request.getViewedAt()).isNotNull();
            verify(paymentRequestRepository).save(request);
        }

        @Test
        void VIEWEDは冪等() {
            PaymentRequestEntity request = requestWithStatus(PaymentRequestStatus.VIEWED);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(request.getId())).willReturn(Optional.of(request));

            assertThat(service.viewByTeam(TEAM_ID, request.getId(), ADMIN_USER_ID).getStatus())
                    .isEqualTo(PaymentRequestStatus.VIEWED);
            verify(paymentRequestRepository, never()).save(any());
        }

        @Test
        void 他チームは403() {
            PaymentRequestEntity request = requestWithStatus(PaymentRequestStatus.SENT);
            given(paymentRequestRepository.findByIdAndDeletedAtIsNull(request.getId())).willReturn(Optional.of(request));

            assertThatThrownBy(() -> service.viewByTeam(999L, request.getId(), ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOR_THIS_TEAM);
        }
    }

    private CreatePaymentRequestCommand createCommand() {
        return new CreatePaymentRequestCommand(
                TEAM_ID, "リーグ参加費", "2026年度", 30000L, "JPY", null,
                LocalDate.of(2026, 7, 31), null);
    }

    private void givenReadyPayee() {
        ConnectAccountEntity payee = ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.ORG)
                .scopeId(ORG_ID)
                .payoutsEnabled(true)
                .build();
        payee.setId(PAYEE_CONNECT_ID);
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.ORG, ORG_ID))
                .willReturn(Optional.of(payee));
    }

    private PaymentRequestEntity requestWithStatus(PaymentRequestStatus status) {
        PaymentRequestEntity request = PaymentRequestEntity.builder()
                .organizationId(ORG_ID)
                .payerScopeKind(ScopeKind.TEAM)
                .payerScopeId(TEAM_ID)
                .status(status)
                .build();
        request.setId(UUID.randomUUID());
        return request;
    }

    private PaymentRequestEntity draftRequest() {
        PaymentRequestEntity request = requestWithStatus(PaymentRequestStatus.DRAFT);
        request = request.toBuilder()
                .issuerScopeKind(ScopeKind.ORG)
                .issuerScopeId(ORG_ID)
                .title("リーグ参加費")
                .faceAmount(30000)
                .currency("JPY")
                .dueDate(LocalDate.of(2026, 7, 31))
                .build();
        request.setId(UUID.randomUUID());
        return request;
    }
}
