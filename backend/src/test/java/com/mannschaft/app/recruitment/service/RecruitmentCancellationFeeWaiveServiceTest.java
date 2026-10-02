package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.CancellationSource;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F03.11.1 キャンセル料の免除（{@link RecruitmentCancellationFeeWaiveService}）の試練。
 *
 * <p>設計書 §10 の受け入れ条件 AC-9 / AC-10 / AC-19 / AC-20 / AC-29 を担う。</p>
 *
 * <p>認可（受取先側の精算管理者か・SYSTEM_ADMIN か・存在を知り得る者か）は CMP-260923-0954 W4 で
 * tx の外の {@link RecruitmentMoneyFacade} へ移った。その検証（AC-18・AC-19・AC-27/28）は
 * {@code RecruitmentMoneyFacadeTest} が担い、本クラスは <b>tx 本体の振る舞い</b>
 * （理由の検査・状態遷移・冪等・監査・記録→募集のたどり直し）だけを検証する。
 * 認可の検証を消したのではなく、検証の置き場を認可の置き場に合わせて移している。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F03.11.1 RecruitmentCancellationFeeWaiveService 試練（tx 本体）")
class RecruitmentCancellationFeeWaiveServiceTest {

    @Mock private RecruitmentCancellationRecordRepository cancellationRecordRepository;
    @Mock private RecruitmentListingRepository listingRepository;
    @Mock private AuditLogService auditLogService;

    private static final Long RECORD_ID = 77L;
    private static final Long LISTING_ID = 100L;
    private static final Long PARTICIPANT_ID = 200L;
    /** キャンセル料を負っている本人。 */
    private static final Long DEBTOR_ID = 1L;
    /** 受取先側の管理者。 */
    private static final Long PAYEE_MANAGER_ID = 55L;
    /** 運営（SYSTEM_ADMIN）として許可された操作者。 */
    private static final Long SYSTEM_ADMIN_ID = 66L;

    private RecruitmentCancellationFeeWaiveService service() {
        return new RecruitmentCancellationFeeWaiveService(
                cancellationRecordRepository, listingRepository, auditLogService);
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
        givenLiveListing();
    }

    /** 記録の募集が生きている（論理削除されていない。モデレーション非表示でも通る）。 */
    private void givenLiveListing() {
        given(listingRepository.lockLiveListingIdIgnoringModeration(LISTING_ID)).willReturn(Optional.of(LISTING_ID));
    }

    // ==========================================================
    // 正常系
    // ==========================================================

    @Test
    @DisplayName("AC-9: 受取先側の管理者の免除で記録が WAIVED になる（未払いがその1件だけなら申込ブロックも外れる）")
    void ac9_waiveByPayeeManager_movesRecordToWaived() {
        RecruitmentCancellationFeeWaiveService svc = service();
        givenRecord(CancellationPaymentStatus.PENDING);

        svc.waive(RECORD_ID, PAYEE_MANAGER_ID, "主催者都合のため免除", true);

        ArgumentCaptor<RecruitmentCancellationRecordEntity> captor =
                ArgumentCaptor.forClass(RecruitmentCancellationRecordEntity.class);
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

        assertThatThrownBy(() -> svc.waive(RECORD_ID, PAYEE_MANAGER_ID, "  ", true))
                .isInstanceOf(BusinessException.class);

        verify(cancellationRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-10: 免除は監査ログに残る（誰がいつ何円の債権を消したかを後から追えること。受取先側なら PAYEE_SIDE）")
    void ac10_waiveIsAudited() {
        RecruitmentCancellationFeeWaiveService svc = service();
        givenRecord(CancellationPaymentStatus.FAILED);

        svc.waive(RECORD_ID, PAYEE_MANAGER_ID, "支払い手段を失ったため免除", true);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(
                eq(AuditEventType.RECRUITMENT_CANCELLATION_FEE_WAIVED.name()),
                eq(PAYEE_MANAGER_ID),
                eq(DEBTOR_ID),
                any(), any(), any(), any(), any(), detail.capture());
        assertThat(detail.getValue()).contains("\"operatorRole\":\"PAYEE_SIDE\"");
    }

    @Test
    @DisplayName("AC-29(対): WAIVED への再免除は no-op で成功する（終端状態なら何でも 409 にしていないこと）")
    void ac29_reWaive_isIdempotentNoOp() {
        RecruitmentCancellationFeeWaiveService svc = service();
        given(cancellationRecordRepository.findById(RECORD_ID))
                .willReturn(Optional.of(record(CancellationPaymentStatus.WAIVED)));
        givenLiveListing();

        svc.waive(RECORD_ID, PAYEE_MANAGER_ID, "再免除", true);

        verify(cancellationRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-19(対): SYSTEM_ADMIN として許可された免除は UNCOLLECTIBLE を WAIVED にし、監査の operatorRole は SYSTEM_ADMIN")
    void ac19_systemAdminWaive_isAuditedAsSystemAdmin() {
        RecruitmentCancellationFeeWaiveService svc = service();
        givenRecord(CancellationPaymentStatus.UNCOLLECTIBLE);

        svc.waive(RECORD_ID, SYSTEM_ADMIN_ID, "運営判断で回収不能を免除", false);

        ArgumentCaptor<RecruitmentCancellationRecordEntity> captor =
                ArgumentCaptor.forClass(RecruitmentCancellationRecordEntity.class);
        verify(cancellationRecordRepository).save(captor.capture());
        // UNCOLLECTIBLE からの唯一の出口が免除である（§5.2）。
        assertThat(captor.getValue().getPaymentStatus()).isEqualTo(CancellationPaymentStatus.WAIVED);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(any(), eq(SYSTEM_ADMIN_ID), eq(DEBTOR_ID),
                any(), any(), any(), any(), any(), detail.capture());
        assertThat(detail.getValue()).contains("\"operatorRole\":\"SYSTEM_ADMIN\"");
    }

    // ==========================================================
    // 異常系
    // ==========================================================

    @Test
    @DisplayName("AC-29: PAID の記録への免除は CANCELLATION_FEE_ALREADY_PAID（409）")
    void ac29_waivePaidRecord_conflicts() {
        RecruitmentCancellationFeeWaiveService svc = service();
        given(cancellationRecordRepository.findById(RECORD_ID))
                .willReturn(Optional.of(record(CancellationPaymentStatus.PAID)));
        givenLiveListing();

        assertThatThrownBy(() -> svc.waive(RECORD_ID, PAYEE_MANAGER_ID, "免除したい", true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RecruitmentErrorCode.CANCELLATION_FEE_ALREADY_PAID);
    }

    @Test
    @DisplayName("AC-20: 存在しない記録 ID への免除は 404（存在を推測させない）")
    void ac20_unknownRecordId_notFound() {
        RecruitmentCancellationFeeWaiveService svc = service();
        given(cancellationRecordRepository.findById(RECORD_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> svc.waive(RECORD_ID, PAYEE_MANAGER_ID, "免除したい", true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_005);
    }

    @Test
    @DisplayName("K1: 認可の後に親の募集が論理削除されていたら COMMON_005（404）で記録は変わらず、監査も残さない")
    void k1_listingDeletedAfterAuthorization_notFoundAndNothingChanges() {
        RecruitmentCancellationFeeWaiveService svc = service();
        given(cancellationRecordRepository.findById(RECORD_ID))
                .willReturn(Optional.of(record(CancellationPaymentStatus.PENDING)));
        given(listingRepository.lockLiveListingIdIgnoringModeration(LISTING_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> svc.waive(RECORD_ID, PAYEE_MANAGER_ID, "免除したい", true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_005);

        verify(cancellationRecordRepository, never()).save(any());
        verify(auditLogService, never()).record(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AC-7: 親の募集が消えていれば、PAID の状態判定（409）より先に COMMON_005（404）になる")
    void ac7_traversalPrecedesStateJudgement() {
        RecruitmentCancellationFeeWaiveService svc = service();
        given(cancellationRecordRepository.findById(RECORD_ID))
                .willReturn(Optional.of(record(CancellationPaymentStatus.PAID)));
        given(listingRepository.lockLiveListingIdIgnoringModeration(LISTING_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> svc.waive(RECORD_ID, PAYEE_MANAGER_ID, "免除したい", true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_005);
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
