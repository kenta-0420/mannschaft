package com.mannschaft.app.recruitment;

import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.recruitment.dto.CancelRecruitmentListingRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.service.PersonalMarketListingService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Issue #2990 L2 — 個人札取下げのトランザクション境界の実 DB 検証
 * （{@code PersonalMarketListingService#cancel} → {@code RecruitmentListingService#cancelPersonalListing}）。
 *
 * <h2>是正前に何が巻き戻っていたか</h2>
 * <p>{@code cancelInternal} は業務トランザクションの内側で {@code sendCancelledNotifications} を呼び、
 * その {@code NotificationHelper#notifyAllLocalized}（非バルク経路）が受信者ごとに
 * {@code createNotification} を既定の {@code REQUIRED} 伝播で実行していた。通知の DB 例外は
 * rollback-only を残すため、<b>募集の CANCELLED 化・参加者の一括キャンセル・参加者履歴の書き込みまで
 * まとめて巻き戻っていた</b>。台帳キー
 * {@code PersonalMarketListingService#cancel -> TX_NOTIFY_VIA_DELEGATE | ROLLBACK_COUPLED}。</p>
 *
 * <h2>この IT が欠陥を捕まえる仕組み</h2>
 * <p>{@link NotificationService#createNotification} を spy して例外を投げさせる。ここは
 * 是正前・是正後のどちらの経路でも必ず通るため、是正前のコードでは取下げ自体が
 * {@code UnexpectedRollbackException} で巻き戻って本テストは赤になる。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>通知は {@code AFTER_COMMIT} で発火する。テストをトランザクションで包むとコミットが起きず
 * リスナーが発火しないまま緑になる（偽の緑）。フィクスチャ投入・検証読み取りは
 * {@link TransactionTemplate} で明示的にコミットする。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2990 L2 個人札取下げのトランザクション境界（実DB）")
class PersonalMarketCancelNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private PersonalMarketListingService personalMarketListingService;

    @Autowired
    private RecruitmentListingRepository listingRepository;

    @Autowired
    private RecruitmentParticipantRepository participantRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 是正前・是正後の双方で必ず通る一点。ここを失敗させて巻き戻りの有無を測る。 */
    @MockitoSpyBean
    private NotificationService notificationService;

    @Test
    @DisplayName("参加者への取下げ通知が失敗しても、募集と参加者のキャンセルはコミットされる")
    void 通知失敗でも取下げはコミットされる() {
        long ownerId = 960_000_000L + (System.nanoTime() % 1_000_000L);
        long participantUserId = ownerId + 1L;
        LocalDateTime start = LocalDateTime.now().plusDays(7);

        Long listingId = transactionTemplate.execute(tx -> listingRepository.save(
                RecruitmentListingEntity.builder()
                        .scopeType(RecruitmentScopeType.PERSONAL)
                        .scopeId(ownerId)
                        .categoryId(1L)
                        .title("#2990 L2 個人札取下げ検証")
                        .participationType(RecruitmentParticipationType.INDIVIDUAL)
                        .startAt(start)
                        .endAt(start.plusHours(2))
                        .applicationDeadline(start.minusDays(1))
                        .autoCancelAt(start.minusDays(2))
                        .capacity(10)
                        .minCapacity(1)
                        .status(RecruitmentListingStatus.OPEN)
                        .createdBy(ownerId)
                        .build()).getId());

        Long participantId = transactionTemplate.execute(tx -> participantRepository.save(
                RecruitmentParticipantEntity.builder()
                        .listingId(listingId)
                        .participantType(RecruitmentParticipantType.USER)
                        .userId(participantUserId)
                        .appliedBy(participantUserId)
                        .status(RecruitmentParticipantStatus.CONFIRMED)
                        .build()).getId());

        willThrow(new RuntimeException("模擬通知失敗（#2990 L2 検証用）"))
                .given(notificationService).createNotification(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        personalMarketListingService.cancel(
                ownerId, listingId, new CancelRecruitmentListingRequest("検証のため取下げ"));

        RecruitmentListingEntity savedListing = transactionTemplate.execute(
                tx -> listingRepository.findById(listingId).orElseThrow());
        assertThat(savedListing.getStatus())
                .as("通知が失敗しても募集の取下げは巻き戻らない")
                .isEqualTo(RecruitmentListingStatus.CANCELLED);

        RecruitmentParticipantEntity savedParticipant = transactionTemplate.execute(
                tx -> participantRepository.findById(participantId).orElseThrow());
        assertThat(savedParticipant.getStatus())
                .as("参加者の一括キャンセルも同じトランザクションで確定している")
                .isEqualTo(RecruitmentParticipantStatus.CANCELLED);

        // AFTER_COMMIT + @Async のリスナーが実際に配送を試みたことの裏取り（非同期のため待つ）。
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationService, atLeastOnce()).createNotification(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()));
    }
}
