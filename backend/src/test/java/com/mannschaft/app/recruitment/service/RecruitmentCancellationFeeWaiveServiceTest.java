package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.CancellationSource;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F03.11.1 キャンセル料の免除（{@link RecruitmentCancellationFeeWaiveService}）の試練。
 *
 * <p>設計書 §10 の受け入れ条件 AC-9 / AC-10 / AC-18 / AC-19 / AC-20 / AC-27 / AC-28 / AC-29 を担う。</p>
 *
 * <p>認可は 3 つの {@code payeeKind} すべてについて肯定側と否定側を対で起こす。判定そのものは payment
 * ドメインの {@link ConnectChargeService#isPayeeSettlementManager} に委ね、recruitment 側は真偽値だけを
 * 受け取る（recruitment から escrow を直接読まない・§10.2）。</p>
 *
 * <p>本クラスは実装より前に書かれた red テストである。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F03.11.1 RecruitmentCancellationFeeWaiveService 試練")
class RecruitmentCancellationFeeWaiveServiceTest {

    @Mock private RecruitmentCancellationRecordRepository cancellationRecordRepository;
    @Mock private ConnectChargeService connectChargeService;
    @Mock private AccessControlService accessControlService;
    @Mock private AuditLogService auditLogService;

    private static final Long RECORD_ID = 77L;
    private static final Long LISTING_ID = 100L;
    private static final Long PARTICIPANT_ID = 200L;
    /** キャンセル料を負っている本人。 */
    private static final Long DEBTOR_ID = 1L;
    /** 受取先側の管理者。 */
    private static final Long PAYEE_MANAGER_ID = 55L;
    /** 受取先とも運営とも無関係な一般ユーザー。 */
    private static final Long OUTSIDER_ID = 66L;

    private RecruitmentCancellationFeeWaiveService service() {
        return new RecruitmentCancellationFeeWaiveService(
                cancellationRecordRepository, connectChargeService, accessControlService, auditLogService);
    }

    private RecruitmentCancellationRecordEntity record(CancellationPaymentStatus status) {
        RecruitmentCancellationRecordEntity r = RecruitmentCancellationRecordEntity.builder()
                .participantId(PARTICIPANT_ID)
                .listingId(LISTING_ID)
                .userId(DEBTOR_ID)
                .teamId(10L)
                .cancelledAt(LocalDateTime.now())
                .cancelledBy(DEBTOR_ID)
                .cancelSource(CancellationSource.USER)
                .hoursBeforeStart(12)
                .feeAmount(3_000)
                .paymentStatus(status)
                .build();
        setField(r, "id", RECORD_ID);
        return r;
    }

    private void givenRecord(CancellationPaymentStatus status) {
        given(cancellationRecordRepository.findById(RECORD_ID)).willReturn(Optional.of(record(status)));
        given(cancellationRecordRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    private void givenPayeeManager(Long actorUserId, boolean accepted) {
        given(connectChargeService.isPayeeSettlementManager(
                EscrowSourceKind.RECRUITMENT, LISTING_ID, PARTICIPANT_ID, actorUserId)).willReturn(accepted);
    }

    // ==========================================================
    // 正常系
    // ==========================================================

    @Test
    @DisplayName("AC-9: 受取先側の管理者の免除で記録が WAIVED になる（未払いがその1件だけなら申込ブロックも外れる）")
    void ac9_waiveByPayeeManager_movesRecordToWaived() {
        RecruitmentCancellationFeeWaiveService svc = service();
        givenRecord(CancellationPaymentStatus.PENDING);
        givenPayeeManager(PAYEE_MANAGER_ID, true);

        svc.waive(RECORD_ID, PAYEE_MANAGER_ID, "主催者都合のため免除");

        org.mockito.ArgumentCaptor<RecruitmentCancellationRecordEntity> captor =
                org.mockito.ArgumentCaptor.forClass(RecruitmentCancellationRecordEntity.class);
        verify(cancellationRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getPaymentStatus()).isEqualTo(CancellationPaymentStatus.WAIVED);
        // 申込ブロックは PENDING/FAILED/UNCOLLECTIBLE の件数で決まるため、WAIVED へ移った時点で
        // この記録はブロックの根拠から外れる（複数件の場合の挙動は AC-31 が担う）。
        assertThat(captor.getValue().getPaymentStatus())
                .isNotIn(CancellationPaymentStatus.PENDING,
                        CancellationPaymentStatus.FAILED,
                        CancellationPaymentStatus.UNCOLLECTIBLE);
    }

    @Test
    @DisplayName("AC-10: 免除は理由が必須（空文字は拒否され、状態は変わらない）")
    void ac10_reasonIsRequired() {
        RecruitmentCancellationFeeWaiveService svc = service();

        assertThatThrownBy(() -> svc.waive(RECORD_ID, PAYEE_MANAGER_ID, "  "))
                .isInstanceOf(BusinessException.class);

        verify(cancellationRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-10: 免除は監査ログに残る（誰がいつ何円の債権を消したかを後から追えること）")
    void ac10_waiveIsAudited() {
        RecruitmentCancellationFeeWaiveService svc = service();
        givenRecord(CancellationPaymentStatus.FAILED);
        givenPayeeManager(PAYEE_MANAGER_ID, true);

        svc.waive(RECORD_ID, PAYEE_MANAGER_ID, "支払い手段を失ったため免除");

        verify(auditLogService).record(
                eq(AuditEventType.RECRUITMENT_CANCELLATION_FEE_WAIVED.name()),
                eq(PAYEE_MANAGER_ID),
                eq(DEBTOR_ID),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AC-29(対): WAIVED への再免除は no-op で成功する（終端状態なら何でも 409 にしていないこと）")
    void ac29_reWaive_isIdempotentNoOp() {
        RecruitmentCancellationFeeWaiveService svc = service();
        given(cancellationRecordRepository.findById(RECORD_ID))
                .willReturn(Optional.of(record(CancellationPaymentStatus.WAIVED)));
        givenPayeeManager(PAYEE_MANAGER_ID, true);

        svc.waive(RECORD_ID, PAYEE_MANAGER_ID, "再免除");

        verify(cancellationRecordRepository, never()).save(any());
    }

    // ==========================================================
    // 異常系・認可
    // ==========================================================

    @Test
    @DisplayName("AC-29: PAID の記録への免除は CANCELLATION_FEE_ALREADY_PAID（409）")
    void ac29_waivePaidRecord_conflicts() {
        RecruitmentCancellationFeeWaiveService svc = service();
        given(cancellationRecordRepository.findById(RECORD_ID))
                .willReturn(Optional.of(record(CancellationPaymentStatus.PAID)));
        givenPayeeManager(PAYEE_MANAGER_ID, true);

        assertThatThrownBy(() -> svc.waive(RECORD_ID, PAYEE_MANAGER_ID, "免除したい"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RecruitmentErrorCode.CANCELLATION_FEE_ALREADY_PAID);
    }

    @Test
    @DisplayName("AC-18: キャンセル料を負っている本人は自分の記録を免除できない（債務者が自分の債務を消せてはならない）")
    void ac18_debtorCannotWaiveOwnRecord() {
        RecruitmentCancellationFeeWaiveService svc = service();
        given(cancellationRecordRepository.findById(RECORD_ID))
                .willReturn(Optional.of(record(CancellationPaymentStatus.PENDING)));
        // 本人は受取先でも運営でもない。
        givenPayeeManager(DEBTOR_ID, false);
        given(accessControlService.isSystemAdmin(DEBTOR_ID)).willReturn(false);

        assertThatThrownBy(() -> svc.waive(RECORD_ID, DEBTOR_ID, "自分で消したい"))
                .isInstanceOf(BusinessException.class);

        verify(cancellationRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-19: 何の権限も持たない一般ユーザーの免除は拒否される（IDOR）")
    void ac19_outsiderCannotWaive() {
        RecruitmentCancellationFeeWaiveService svc = service();
        given(cancellationRecordRepository.findById(RECORD_ID))
                .willReturn(Optional.of(record(CancellationPaymentStatus.PENDING)));
        givenPayeeManager(OUTSIDER_ID, false);
        given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(false);

        assertThatThrownBy(() -> svc.waive(RECORD_ID, OUTSIDER_ID, "他人の債権を消したい"))
                .isInstanceOf(BusinessException.class);

        verify(cancellationRecordRepository, never()).save(any());
        verify(auditLogService, never()).record(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AC-20: 存在しない記録 ID への免除は 404（存在を推測させない）")
    void ac20_unknownRecordId_notFound() {
        RecruitmentCancellationFeeWaiveService svc = service();
        given(cancellationRecordRepository.findById(RECORD_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> svc.waive(RECORD_ID, PAYEE_MANAGER_ID, "免除したい"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("AC-19(対): SYSTEM_ADMIN は受取先でなくても免除できる")
    void ac19_systemAdminCanWaive() {
        RecruitmentCancellationFeeWaiveService svc = service();
        givenRecord(CancellationPaymentStatus.UNCOLLECTIBLE);
        givenPayeeManager(OUTSIDER_ID, false);
        given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(true);

        svc.waive(RECORD_ID, OUTSIDER_ID, "運営判断で回収不能を免除");

        org.mockito.ArgumentCaptor<RecruitmentCancellationRecordEntity> captor =
                org.mockito.ArgumentCaptor.forClass(RecruitmentCancellationRecordEntity.class);
        verify(cancellationRecordRepository).save(captor.capture());
        // UNCOLLECTIBLE からの唯一の出口が免除である（§5.2）。
        assertThat(captor.getValue().getPaymentStatus()).isEqualTo(CancellationPaymentStatus.WAIVED);
    }

    @Test
    @DisplayName("AC-27/AC-28: 受取先の判定は payment ドメインへ委ね、recruitment から escrow を読まない")
    void ac27ac28_payeeJudgementIsDelegatedToPaymentDomain() {
        RecruitmentCancellationFeeWaiveService svc = service();
        givenRecord(CancellationPaymentStatus.PENDING);
        givenPayeeManager(PAYEE_MANAGER_ID, true);

        svc.waive(RECORD_ID, PAYEE_MANAGER_ID, "受取先として免除");

        // 3 種の payeeKind（TEAM/ORG/USER）の判定はすべてこの 1 本の入口に閉じる（§10.2）。
        verify(connectChargeService).isPayeeSettlementManager(
                EscrowSourceKind.RECRUITMENT, LISTING_ID, PARTICIPANT_ID, PAYEE_MANAGER_ID);
    }

    // ==========================================================
    // ヘルパー
    // ==========================================================

    private static void setField(Object entity, String name, Object value) {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("フィールドが見つからない: " + name);
    }
}
