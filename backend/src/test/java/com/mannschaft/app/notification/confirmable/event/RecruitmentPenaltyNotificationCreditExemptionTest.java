package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationSettingsEntity;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationTargetRepository;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationConfirmService;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationQueryService;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationSettingsService;
import com.mannschaft.app.notification.confirmable.service.ConfirmableRecipientGroupService;
import com.mannschaft.app.notification.confirmable.service.ConfirmableRecipientPreviewService;
import com.mannschaft.app.notification.confirmable.service.ConfirmableTargetAuthorizationValidator;
import com.mannschaft.app.notification.confirmable.service.ConfirmableTargetSelectionValidator;
import com.mannschaft.app.notification.credit.error.NotificationCreditErrorCode;
import com.mannschaft.app.notification.credit.service.NotificationCreditService;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.event.RecruitmentPenaltyAppliedNotificationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CMP-260930-1932 AC-1: システム発の自動通知（募集ペナルティ適用通知）は通知クレジットを消費しない。
 *
 * <p>再現（調査用 {@code RecruitmentPenaltyCreditBlockedReproTest} の意図を現行 16 引数シグネチャで取込）:
 * 組織スコープ・猶予72h超過・残高0 の組織では {@code NotificationCreditService#consume} が
 * {@code CREDIT_INSUFFICIENT} を投げ、リスナーの catch で握られてペナルティ通知が<b>届かない</b>。
 * 根治後は同期 {@code send/sendFromSource} が consume を呼ばないため、残高の状態にかかわらず届く。</p>
 *
 * <p>{@link ConfirmableNotificationService} は実物（協力者のみモック）、リスナーも実物で組む。
 * consume のモックは「呼ばれたら猶予超過で失敗する」組織状態を表す（呼ばれないことが合格条件）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CMP-260930-1932 AC-1: 募集ペナルティ通知は組織クレジット不足でも届く（課金対象外）")
class RecruitmentPenaltyNotificationCreditExemptionTest {

    private static final Long PENALTY_ID = 11L;
    private static final Long RECIPIENT_USER_ID = 22L;
    private static final Long ORG_ID = 33L;
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-28T12:00:00Z");

    @Mock ConfirmableNotificationRepository notificationRepository;
    @Mock ConfirmableNotificationRecipientRepository recipientRepository;
    @Mock ConfirmableNotificationSettingsService settingsService;
    @Mock UserRepository userRepository;
    @Mock NotificationHelper notificationHelper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock NotificationCreditService notificationCreditService;
    @Mock ConfirmableNotificationConfirmService confirmService;
    @Mock ConfirmableNotificationQueryService queryService;
    @Mock ConfirmableNotificationTargetRepository targetRepository;
    @Mock ConfirmableTargetAuthorizationValidator targetAuthorizationValidator;
    @Mock ConfirmableTargetSelectionValidator targetSelectionValidator;
    @Mock ConfirmableRecipientGroupService recipientGroupService;
    @Mock ConfirmableRecipientPreviewService recipientPreviewService;
    @Mock NotificationFanoutJobService fanoutJobService;

    private RecruitmentPenaltyAppliedNotificationListener listener;

    @BeforeEach
    void setUp() {
        ConfirmableNotificationService service = new ConfirmableNotificationService(
                notificationRepository, recipientRepository, settingsService, userRepository,
                notificationHelper, eventPublisher, notificationCreditService, confirmService, queryService,
                targetRepository, targetAuthorizationValidator, targetSelectionValidator,
                recipientGroupService, recipientPreviewService, fanoutJobService, Clock.systemDefaultZone());
        listener = new RecruitmentPenaltyAppliedNotificationListener(service, notificationRepository);

        given(userRepository.findById(any())).willReturn(Optional.empty());
        given(userRepository.getReferenceById(any())).willReturn(mock(UserEntity.class));
        given(notificationRepository.save(any(ConfirmableNotificationEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(recipientRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("AC-1: ORG・猶予72h超過・残高0（consume が CREDIT_INSUFFICIENT を投げる状態）でも、"
            + "受信者行1件・アプリ内通知1回・作成イベント1回が出て、consume は0回")
    void AC1_組織スコープで猶予超過かつ残高0でもペナルティ通知が届き課金されない() {
        given(settingsService.getOrCreate(ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(ConfirmableNotificationSettingsEntity.builder()
                        .scopeType(ScopeType.ORGANIZATION).scopeId(ORG_ID).build());
        // 呼ばれたら猶予超過で失敗する組織状態（猶予72hちょうど・72h+1s・残高0 のいずれも consume では同じ結果）。
        willThrow(new BusinessException(NotificationCreditErrorCode.CREDIT_INSUFFICIENT))
                .given(notificationCreditService).consume(eq(ORG_ID), anyInt(), any());

        assertThatCode(() -> listener.onPenaltyApplied(new RecruitmentPenaltyAppliedNotificationEvent(
                PENALTY_ID, RECIPIENT_USER_ID, RecruitmentScopeType.ORGANIZATION, ORG_ID, EXPIRES_AT)))
                .doesNotThrowAnyException();

        // consume は0回（現状は consume(33, 1, CONFIRMABLE) が呼ばれ例外 → 以降が全部消えるため red）
        verify(notificationCreditService, never()).consume(any(), anyInt(), any());

        // 受信者行1件
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConfirmableNotificationRecipientEntity>> recipients =
                ArgumentCaptor.forClass(List.class);
        verify(recipientRepository).saveAll(recipients.capture());
        assertThat(recipients.getValue()).as("AC-1: 受信者行は本人1件").hasSize(1);

        // notifyAll("RECRUITMENT_PENALTY_APPLIED") 1回
        verify(notificationHelper, times(1)).notifyAll(anyList(), eq("RECRUITMENT_PENALTY_APPLIED"),
                any(), any(), any(), any(), any(), any(), any(), any(), any());

        // ConfirmableNotificationCreatedEvent 1回
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(1)).publishEvent(events.capture());
        assertThat(events.getAllValues())
                .as("AC-1: ConfirmableNotificationCreatedEvent が1回だけ発行される")
                .hasSize(1)
                .allSatisfy(e -> assertThat(e).isInstanceOf(ConfirmableNotificationCreatedEvent.class));
    }

    @Test
    @DisplayName("AC-1(対照): TEAM スコープでも consume は呼ばれず通知は届く（既存挙動の維持）")
    void AC1_チームスコープでも課金されず通知は届く() {
        given(settingsService.getOrCreate(ScopeType.TEAM, ORG_ID))
                .willReturn(ConfirmableNotificationSettingsEntity.builder()
                        .scopeType(ScopeType.TEAM).scopeId(ORG_ID).build());

        listener.onPenaltyApplied(new RecruitmentPenaltyAppliedNotificationEvent(
                PENALTY_ID, RECIPIENT_USER_ID, RecruitmentScopeType.TEAM, ORG_ID, EXPIRES_AT));

        verify(notificationCreditService, never()).consume(any(), anyInt(), any());
        verify(notificationHelper, times(1)).notifyAll(anyList(), eq("RECRUITMENT_PENALTY_APPLIED"),
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
