package com.mannschaft.app.recruitment;

import com.mannschaft.app.notification.service.NotificationHelper;
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
 * <h2>この IT が欠陥を捕まえる仕組み（2026-09-02 に実測で作り直した）</h2>
 * <p>失敗注入は<b>2 点</b>ある。役割が違うので両方要る。</p>
 * <ol>
 *   <li><b>{@link NotificationHelper#notifyAllLocalized} を spy して例外を投げさせる</b> —
 *       これが<b>是正前の欠陥そのものの再現</b>である。是正前は
 *       {@code cancelInternal}（{@code @Transactional}）の内側からこのメソッドを同期で呼んでおり、
 *       {@code sendCancelledNotifications} には try/catch が無かった。よって通知側の失敗は
 *       そのまま業務トランザクションの外へ抜け、<b>募集の CANCELLED 化・参加者の一括キャンセルごと
 *       巻き戻る</b>。是正後はこのメソッドが呼ばれないため、この注入は無害な空振りになる。</li>
 *   <li><b>{@link NotificationService#createNotification} を spy して例外を投げさせる</b> —
 *       こちらは是正<b>後</b>の経路（AFTER_COMMIT リスナー → {@code sendOne}）が実際に配送を
 *       試みたことの裏取りに使う。1 受信者ぶんの失敗が業務へ戻らないことも同時に固定する。</li>
 * </ol>
 *
 * <p><b>なぜ (1) が要るのか — 実測で分かったこと。</b>
 * 当初この IT は「{@code createNotification} は是正前・是正後のどちらの経路でも必ず通る」という
 * 前提で (2) だけを置いていた。<b>これは誤りだった。</b>是正前のコード（{@code 362cf1bca9}）へ
 * この IT を当てて実測したところ、失敗の理由は巻き戻りではなく
 * 「{@code createNotification} との相互作用がゼロ」であった。是正前の
 * {@code notifyAllLocalized} は先に {@code NotificationHelper#filterAccessibleRecipients}
 * （{@code visibilityChecker.canView}）を通すため、実ユーザー・所属のフィクスチャを持たない
 * 本テストでは受信者が<b>全員そこで落ちて</b> {@code createNotification} まで到達しない。
 * 一方是正後は {@code sendOne} が {@code createNotification} を直接呼び、可視性判定は
 * その内側で行われるので spy に当たる。つまり (2) だけでは
 * <b>「赤くはなるが、赤くなる理由が巻き戻りではない」</b>という状態だった。
 * 見かけの赤に満足せず、赤の理由まで実測すること。</p>
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

    /** 是正後の経路（AFTER_COMMIT リスナー → sendOne）が配送を試みたことの裏取り点。 */
    @MockitoSpyBean
    private NotificationService notificationService;

    /**
     * 是正<b>前</b>の経路（業務TX内の同期通知）そのもの。ここを失敗させることで
     * 「通知の失敗が業務処理を巻き戻すか」を直接測る。是正後は呼ばれない。
     */
    @MockitoSpyBean
    private NotificationHelper notificationHelper;

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

        // (1) 是正前に業務TX内で同期実行されていた入口。是正前ならここで例外が業務TXへ抜け、
        //     募集キャンセルごと巻き戻る（＝下の CANCELLED 検証が赤になる）。
        willThrow(new RuntimeException("模擬通知失敗・業務TX内経路（#2990 L2 検証用）"))
                .given(notificationHelper).notifyAllLocalized(
                        any(), any(), any(), any(), any(), any(), any(), any(), any());
        // (2) 是正後の配送経路。1 受信者ぶんの失敗が業務へ戻らないことを固定する。
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
