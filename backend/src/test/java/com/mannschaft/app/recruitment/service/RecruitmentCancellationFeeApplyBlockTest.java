package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.CancellationSource;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentParticipantType;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.ApplyToRecruitmentRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantHistoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * F03.11.1 未払いキャンセル料による申込ブロックの試練（設計書 §5.3・§10.0）。
 *
 * <p>受け入れ条件 AC-31（複数の未払いと免除の関係）と、終端状態 {@code UNCOLLECTIBLE} をブロック対象へ
 * 加えること（§5.3）を担う。</p>
 *
 * <p>AC-31 は<b>未払い 1 件のフィクスチャでは緑にならない</b>。1 件だけだと免除した瞬間に必ずブロックが外れ、
 * 「ユーザー単位で判定している」ことと「記録単位で判定している」ことの区別がつかないためである。
 * そこで 2 件の未払いを<b>別々の受取先</b>に対して作り、(1) 2 件とも未払いで拒否 → (2) 片方を免除した後も
 * なお拒否 → (3) 両方を免除した後に通る、の 3 段階を観測する。(2) が本 AC の本体である。</p>
 *
 * <p>本クラスは実装より前に書かれた red テストである。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F03.11.1 未払いキャンセル料による申込ブロック 試練")
class RecruitmentCancellationFeeApplyBlockTest {

    @Mock private RecruitmentParticipantRepository participantRepository;
    @Mock private RecruitmentListingRepository listingRepository;
    @Mock private RecruitmentParticipantHistoryRepository historyRepository;
    @Mock private RecruitmentCancellationRecordRepository cancellationRecordRepository;
    @Mock private RecruitmentCancellationPolicyService policyService;
    @Mock private RecruitmentListingService listingService;
    @Mock private AccessControlService accessControlService;
    @Mock private RecruitmentMapper mapper;
    @Mock private MarketFinalizeService marketFinalizeService;
    @Mock private ContentVisibilityChecker visibilityChecker;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ConnectChargeService connectChargeService;
    @Mock private AuditLogService auditLogService;

    private static final Long USER_ID = 1L;
    private static final Long LISTING_ID = 300L;

    /** 未払い記録のインメモリ台帳。免除で状態が変われば、次の申込判定はその最新状態を見る。 */
    private final List<RecruitmentCancellationRecordEntity> store = new ArrayList<>();

    private RecruitmentParticipantService participantService() {
        return new RecruitmentParticipantService(
                participantRepository, listingRepository, historyRepository, cancellationRecordRepository,
                policyService, listingService, accessControlService, mapper, marketFinalizeService,
                visibilityChecker, eventPublisher);
    }

    private RecruitmentCancellationFeeWaiveService waiveService() {
        return new RecruitmentCancellationFeeWaiveService(
                cancellationRecordRepository, connectChargeService, accessControlService, auditLogService);
    }

    /**
     * 未払い判定を台帳から計算する。渡された状態の集合をそのまま使うため、
     * 「どの状態をブロック対象にするか」という production 側の判断がそのまま検証対象になる。
     */
    private void wireUnpaidLookup() {
        given(cancellationRecordRepository.existsByUserIdAndPaymentStatusIn(anyLong(), any(Collection.class)))
                .willAnswer(invocation -> {
                    Long userId = invocation.getArgument(0);
                    @SuppressWarnings("unchecked")
                    Collection<CancellationPaymentStatus> statuses = invocation.getArgument(1);
                    return store.stream()
                            .filter(r -> userId.equals(r.getUserId()))
                            .anyMatch(r -> statuses.contains(r.getPaymentStatus()));
                });
    }

    private void wireRecordStore() {
        given(cancellationRecordRepository.findById(anyLong())).willAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return store.stream().filter(r -> id.equals(r.getId())).findFirst();
        });
        given(cancellationRecordRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
    }

    /** 申込が「未払い」を理由にブロックされたかどうかを返す（他の理由での失敗と区別する）。 */
    private boolean blockedByUnpaidFee(RecruitmentParticipantService service) {
        try {
            service.apply(LISTING_ID, USER_ID,
                    new ApplyToRecruitmentRequest(RecruitmentParticipantType.USER, null, null));
            return false;
        } catch (BusinessException e) {
            return e.getErrorCode() == RecruitmentErrorCode.CANCELLATION_PAYMENT_FAILED;
        } catch (RuntimeException e) {
            // 未払い判定より後ろの段（重複申込・定員など）で落ちた場合はブロックされていない。
            return false;
        }
    }

    @Test
    @DisplayName("AC-31: 別々の受取先への未払い2件のうち片方を免除してもブロックは続き、両方の免除で初めて申込できる")
    void ac31_waivingOneOfTwoUnpaidRecordsDoesNotLiftTheBlock() throws Exception {
        RecruitmentParticipantService service = participantService();
        RecruitmentCancellationFeeWaiveService waive = waiveService();
        given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(openListing()));
        wireUnpaidLookup();
        wireRecordStore();
        // 受取先 A・受取先 B のどちらの管理者でもある操作者を仮定する（判定自体は AC-27/28 が担う）。
        given(connectChargeService.isPayeeSettlementManager(
                any(EscrowSourceKind.class), anyLong(), anyLong(), anyLong())).willReturn(true);

        // A チーム受取の未払いと B チーム受取の未払いを 1 件ずつ。
        store.add(unpaidRecord(1001L, 201L, 11L));
        store.add(unpaidRecord(1002L, 202L, 22L));

        // (1) 2 件とも未払い → 申込は拒否される。
        assertThat(blockedByUnpaidFee(service))
                .as("2 件の未払いがある状態では申込できない")
                .isTrue();

        // (2) 片方を免除しても、もう片方が残っているためブロックは続く（本 AC の本体）。
        waive.waive(1001L, 55L, "A チームの主催者が免除");
        assertThat(blockedByUnpaidFee(service))
                .as("片方を免除しただけでは申込制限は解除されない")
                .isTrue();

        // (3) 最後の 1 件が免除された瞬間にブロックが外れる。
        waive.waive(1002L, 66L, "B チームの主催者が免除");
        assertThat(blockedByUnpaidFee(service))
                .as("最後の 1 件が免除されればブロックは外れる")
                .isFalse();
    }

    @Test
    @DisplayName("AC-7(申込側): UNCOLLECTIBLE の記録を持つユーザーの新規申込はブロックされる（未払いであることに変わりはない）")
    void uncollectibleRecordAlsoBlocksApplication() throws Exception {
        RecruitmentParticipantService service = participantService();
        given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(openListing()));
        wireUnpaidLookup();

        RecruitmentCancellationRecordEntity uncollectible = unpaidRecord(1003L, 203L, 33L);
        setField(uncollectible, "paymentStatus", CancellationPaymentStatus.UNCOLLECTIBLE);
        store.add(uncollectible);

        assertThat(blockedByUnpaidFee(service))
                .as("リトライ打ち切りは「回収できない」という結果であって、支払われたことにはならない")
                .isTrue();
    }

    // ==========================================================
    // ヘルパー
    // ==========================================================

    private RecruitmentCancellationRecordEntity unpaidRecord(Long id, Long participantId, Long teamId) {
        RecruitmentCancellationRecordEntity r = RecruitmentCancellationRecordEntity.builder()
                .participantId(participantId)
                .listingId(LISTING_ID)
                .userId(USER_ID)
                .teamId(teamId)
                .cancelledAt(LocalDateTime.now())
                .cancelledBy(USER_ID)
                .cancelSource(CancellationSource.USER)
                .hoursBeforeStart(6)
                .feeAmount(3_000)
                .paymentStatus(CancellationPaymentStatus.PENDING)
                .build();
        setField(r, "id", id);
        return r;
    }

    private RecruitmentListingEntity openListing() throws Exception {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(10L)
                .categoryId(100L)
                .title("試練用の札")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(LocalDateTime.now().plusDays(2))
                .endAt(LocalDateTime.now().plusDays(2).plusHours(2))
                .applicationDeadline(LocalDateTime.now().plusDays(1))
                .autoCancelAt(LocalDateTime.now().plusDays(1))
                .capacity(10)
                .minCapacity(1)
                .visibility(RecruitmentVisibility.SCOPE_ONLY)
                .createdBy(USER_ID)
                .build();
        setField(listing, "id", LISTING_ID);
        setField(listing, "status", RecruitmentListingStatus.OPEN);
        return listing;
    }

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
