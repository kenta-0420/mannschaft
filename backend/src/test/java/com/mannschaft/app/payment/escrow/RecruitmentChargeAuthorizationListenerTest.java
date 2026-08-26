package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.escrow.event.ChargeAuthorizationFailedEvent;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.recruitment.event.RecruitmentParticipantConfirmedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F22.1 統一決済 P2-b: {@link RecruitmentChargeAuthorizationListener} 単体テスト。
 *
 * <p>応募確定イベント → {@link ConnectChargeService#authorize} へ正しい
 * {@link AuthorizeChargeCommand}（RECRUITMENT・MANUAL・system 経路 actor=null）を組み立てて渡すことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentChargeAuthorizationListener 単体テスト")
class RecruitmentChargeAuthorizationListenerTest {

    @Mock private ConnectChargeService connectChargeService;
    @Mock private StripeCustomerRepository stripeCustomerRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private RecruitmentChargeAuthorizationListener listener;

    @Test
    @DisplayName("TEAM 受領: payer Customer 解決 + 与信コマンド（actor=null・RECRUITMENT）を委譲")
    void teamPayee_buildsCommand() {
        RecruitmentParticipantConfirmedEvent event = new RecruitmentParticipantConfirmedEvent(
                100L, 200L, 999L, "TEAM", 10L, "TEAM", null, 10_000L);
        given(stripeCustomerRepository.findByUserId(999L))
                .willReturn(Optional.of(StripeCustomerEntity.builder()
                        .userId(999L).stripeCustomerId("cus_payer").build()));
        given(connectChargeService.authorize(any())).willReturn(new AuthorizeChargeResult(
                UUID.randomUUID(), EscrowStatus.AUTHORIZED, "secret", "pi_x", 10_000L, 10_250L, 500L));

        listener.onParticipantConfirmed(event);

        ArgumentCaptor<AuthorizeChargeCommand> captor = ArgumentCaptor.forClass(AuthorizeChargeCommand.class);
        verify(connectChargeService).authorize(captor.capture());
        AuthorizeChargeCommand cmd = captor.getValue();
        assertThat(cmd.sourceKind()).isEqualTo(EscrowSourceKind.RECRUITMENT);
        assertThat(cmd.sourceId()).isEqualTo(100L);
        assertThat(cmd.sourceParticipantId()).isEqualTo(200L);
        assertThat(cmd.payerScopeKind()).isEqualTo(ScopeKind.USER);
        assertThat(cmd.payerScopeId()).isEqualTo(999L);
        assertThat(cmd.payerStripeCustomerId()).isEqualTo("cus_payer");
        assertThat(cmd.payeeKind()).isEqualTo(ScopeKind.TEAM);
        assertThat(cmd.payeeScopeId()).isEqualTo(10L);
        assertThat(cmd.faceAmount()).isEqualTo(10_000L);
        assertThat(cmd.organizationId()).isNull();
        // system 経路: actor 認可なし。
        assertThat(cmd.actorUserId()).isNull();
        // 成功時は与信失敗イベントを発火しない。
        verify(eventPublisher, never()).publishEvent(any(ChargeAuthorizationFailedEvent.class));
    }

    @Test
    @DisplayName("ORG 受領: organizationId にテナント列をセット")
    void orgPayee_setsOrganizationId() {
        RecruitmentParticipantConfirmedEvent event = new RecruitmentParticipantConfirmedEvent(
                101L, 201L, 999L, "ORGANIZATION", 55L, "ORG", null, 5_000L);
        given(stripeCustomerRepository.findByUserId(999L))
                .willReturn(Optional.of(StripeCustomerEntity.builder()
                        .userId(999L).stripeCustomerId("cus_payer").build()));
        given(connectChargeService.authorize(any())).willReturn(new AuthorizeChargeResult(
                UUID.randomUUID(), EscrowStatus.HELD, null, null, 5_000L, 5_125L, 250L));

        listener.onParticipantConfirmed(event);

        ArgumentCaptor<AuthorizeChargeCommand> captor = ArgumentCaptor.forClass(AuthorizeChargeCommand.class);
        verify(connectChargeService).authorize(captor.capture());
        AuthorizeChargeCommand cmd = captor.getValue();
        assertThat(cmd.payeeKind()).isEqualTo(ScopeKind.ORG);
        assertThat(cmd.payeeScopeId()).isEqualTo(55L);
        assertThat(cmd.organizationId()).isEqualTo(55L);
    }

    @Test
    @DisplayName("USER 受領: payeeScopeId に payeeUserId をセット")
    void userPayee_usesPayeeUserId() {
        RecruitmentParticipantConfirmedEvent event = new RecruitmentParticipantConfirmedEvent(
                102L, 202L, 999L, "TEAM", 10L, "USER", 42L, 3_000L);
        given(stripeCustomerRepository.findByUserId(999L)).willReturn(Optional.empty());
        given(connectChargeService.authorize(any())).willReturn(new AuthorizeChargeResult(
                UUID.randomUUID(), EscrowStatus.HELD, null, null, 3_000L, 3_075L, 150L));

        listener.onParticipantConfirmed(event);

        ArgumentCaptor<AuthorizeChargeCommand> captor = ArgumentCaptor.forClass(AuthorizeChargeCommand.class);
        verify(connectChargeService).authorize(captor.capture());
        AuthorizeChargeCommand cmd = captor.getValue();
        assertThat(cmd.payeeKind()).isEqualTo(ScopeKind.USER);
        assertThat(cmd.payeeScopeId()).isEqualTo(42L);
        // Customer 未解決でも HELD 経路では PI を作らないため null 許容。
        assertThat(cmd.payerStripeCustomerId()).isNull();
    }

    @Test
    @DisplayName("payeeKind 不正→与信スキップ（authorize 呼ばない）")
    void invalidPayeeKind_skips() {
        RecruitmentParticipantConfirmedEvent event = new RecruitmentParticipantConfirmedEvent(
                103L, 203L, 999L, "TEAM", 10L, "BOGUS", null, 1_000L);

        listener.onParticipantConfirmed(event);

        verify(connectChargeService, never()).authorize(any());
    }

    @Test
    @DisplayName("与信失敗: 例外を握り潰さず ERROR ログ＋ChargeAuthorizationFailedEvent を発火（根治・02 §5.1）")
    void authorizeFails_publishesFailureEventAndSwallows() {
        RecruitmentParticipantConfirmedEvent event = new RecruitmentParticipantConfirmedEvent(
                104L, 204L, 999L, "TEAM", 10L, "TEAM", null, 8_000L);
        given(stripeCustomerRepository.findByUserId(999L))
                .willReturn(Optional.of(StripeCustomerEntity.builder()
                        .userId(999L).stripeCustomerId("cus_payer").build()));
        given(connectChargeService.authorize(any()))
                .willThrow(new RuntimeException("stripe authorize failed"));

        // AFTER_COMMIT 後ゆえ例外を呼び出し元に伝播させず飲み込む（握り潰しではなくイベントで救済）。
        assertThatCode(() -> listener.onParticipantConfirmed(event)).doesNotThrowAnyException();

        // 失敗が観測可能・後続でアクション可能になるよう救済イベントを発火する。
        ArgumentCaptor<ChargeAuthorizationFailedEvent> captor =
                ArgumentCaptor.forClass(ChargeAuthorizationFailedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        ChargeAuthorizationFailedEvent failed = captor.getValue();
        assertThat(failed.sourceKind()).isEqualTo(EscrowSourceKind.RECRUITMENT);
        assertThat(failed.sourceId()).isEqualTo(104L);
        assertThat(failed.sourceParticipantId()).isEqualTo(204L);
        assertThat(failed.payerScope()).isEqualTo(ScopeKind.USER);
        assertThat(failed.payerScopeId()).isEqualTo(999L);
        assertThat(failed.reason()).contains("stripe authorize failed");
    }

    // ============================ 第三陣-b 7日判定 ============================

    @Test
    @DisplayName("7日超(役務日=+10日): deferred=true で authorize を委譲（成立時に与信しない）")
    void serviceDateBeyond7Days_buildsDeferredCommand() {
        RecruitmentParticipantConfirmedEvent event = new RecruitmentParticipantConfirmedEvent(
                110L, 210L, 999L, "TEAM", 10L, "TEAM", null, 10_000L, LocalDateTime.now().plusDays(10));
        given(stripeCustomerRepository.findByUserId(999L))
                .willReturn(Optional.of(StripeCustomerEntity.builder()
                        .userId(999L).stripeCustomerId("cus_payer").build()));
        given(connectChargeService.authorize(any())).willReturn(new AuthorizeChargeResult(
                UUID.randomUUID(), EscrowStatus.DEFERRED, null, null, 10_000L, 10_250L, 500L));

        listener.onParticipantConfirmed(event);

        ArgumentCaptor<AuthorizeChargeCommand> captor = ArgumentCaptor.forClass(AuthorizeChargeCommand.class);
        verify(connectChargeService).authorize(captor.capture());
        assertThat(captor.getValue().deferred()).isTrue();
    }

    @Test
    @DisplayName("7日以内(役務日=+3日): deferred=false で従来与信を委譲")
    void serviceDateWithin7Days_buildsAuthorizeCommand() {
        RecruitmentParticipantConfirmedEvent event = new RecruitmentParticipantConfirmedEvent(
                111L, 211L, 999L, "TEAM", 10L, "TEAM", null, 10_000L, LocalDateTime.now().plusDays(3));
        given(stripeCustomerRepository.findByUserId(999L))
                .willReturn(Optional.of(StripeCustomerEntity.builder()
                        .userId(999L).stripeCustomerId("cus_payer").build()));
        given(connectChargeService.authorize(any())).willReturn(new AuthorizeChargeResult(
                UUID.randomUUID(), EscrowStatus.PENDING_CONFIRMATION, "cs", "pi", 10_000L, 10_250L, 500L));

        listener.onParticipantConfirmed(event);

        ArgumentCaptor<AuthorizeChargeCommand> captor = ArgumentCaptor.forClass(AuthorizeChargeCommand.class);
        verify(connectChargeService).authorize(captor.capture());
        assertThat(captor.getValue().deferred()).isFalse();
    }

    @Test
    @DisplayName("役務日不明(serviceDate=null): 安全側で deferred=false（従来与信・第三陣バッチに委ねる）")
    void serviceDateNull_buildsAuthorizeCommandSafeSide() {
        RecruitmentParticipantConfirmedEvent event = new RecruitmentParticipantConfirmedEvent(
                112L, 212L, 999L, "TEAM", 10L, "TEAM", null, 10_000L, null);
        given(stripeCustomerRepository.findByUserId(999L))
                .willReturn(Optional.of(StripeCustomerEntity.builder()
                        .userId(999L).stripeCustomerId("cus_payer").build()));
        given(connectChargeService.authorize(any())).willReturn(new AuthorizeChargeResult(
                UUID.randomUUID(), EscrowStatus.PENDING_CONFIRMATION, "cs", "pi", 10_000L, 10_250L, 500L));

        listener.onParticipantConfirmed(event);

        ArgumentCaptor<AuthorizeChargeCommand> captor = ArgumentCaptor.forClass(AuthorizeChargeCommand.class);
        verify(connectChargeService).authorize(captor.capture());
        assertThat(captor.getValue().deferred()).isFalse();
    }
}
