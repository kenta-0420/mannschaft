package com.mannschaft.app.event;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.event.dto.DismissalRequest;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventRsvpResponseEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.event.service.EventDismissalService;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

/**
 * Issue #2834 / CMP-056 第1群ロットB — 通知トランザクション分離の実 DB 検証
 * （{@code EventDismissalService#sendDismissalNotification}）。
 *
 * <h2>なぜモックのユニットテストでは足りないのか</h2>
 * <p>本 IT が検証する「rollback-only が業務コミットを巻き戻す／戻さない」は Spring の
 * トランザクションの<b>実挙動</b>であり、Mockito で例外を投げさせるユニットテストでは原理的に
 * 再現できない（{@code docs/task-list.md} の CMP-056 行に明文の要件がある）。よって実 DB
 * （Testcontainers MySQL）に対して<b>実際に永続化例外を起こし、コミットの成否を見る</b>。</p>
 *
 * <h2>この IT で実証すること</h2>
 * <ul>
 *   <li>AC-1: 通知配送（{@link NotificationDeliveryRunner#sendOne}）が例外を投げても、
 *       {@code sendDismissalNotification} の業務トランザクション
 *       （{@code EventEntity#recordDismissal} による {@code dismissal_notification_sent_at} の記録）は
 *       コミットされる。是正前は {@code createNotification} が同一トランザクションに参加していたため、
 *       通知側の DB 例外で rollback-only が残り<b>「解散通知済み」の記録ごと巻き戻っていた</b>。</li>
 *   <li>AC-2: 業務トランザクション自体がロールバックした場合、通知は作られない
 *       （{@code AFTER_COMMIT} は業務トランザクションがロールバックすると発火しないため）。</li>
 *   <li>AC-3: 通知配送はコミット後に非同期発火するため、配送時点で source 行
 *       （{@code events}）が実際に読み取れ、visibility ガードで deny されずに通知が作られる。</li>
 * </ul>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>通知は {@code AFTER_COMMIT} で発火する。テストメソッドをトランザクションで包むとコミットが
 * 起きず通知が1件も作られないまま「作られないことを確認できた」という<b>偽の緑</b>になる。よって
 * トランザクションを張らず、フィクスチャ投入・検証読み取りは {@link TransactionTemplate} で
 * 明示的にコミットする（{@code ContactInviteUsedNotificationTransactionIT} と同型）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2834/CMP-056 通知トランザクション分離の実DB検証（EventDismissalService）")
class EventDismissalNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    /** 解散通知が向く先のチームID（{@code events.scope_id}）。teams への FK は無いため合成値で足りる。 */
    private static final long TEAM_ID = 987654321L;

    @Autowired
    private EventDismissalService eventDismissalService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventRsvpResponseRepository rsvpResponseRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * AC-1 / AC-2 検証用 spy。{@code NotificationService} は実 Bean のまま（モックしない）で、
     * {@link NotificationDeliveryRunner#sendOne} だけを部分的に差し替える。
     */
    @MockitoSpyBean
    private NotificationDeliveryRunner notificationDeliveryRunner;

    @Test
    @DisplayName("AC-1: 通知配送のDB例外が起きても、解散記録（recordDismissal）はコミットされる")
    void 通知配送が失敗しても解散記録はコミットされる() {
        String nonce = String.valueOf(System.nanoTime());
        Long organizerId = insertUser("ev-ac1-organizer-" + nonce + "@example.com");
        Long attendeeId = insertUser("ev-ac1-attendee-" + nonce + "@example.com");
        Long eventId = insertEventWithAttendee(nonce, attendeeId);

        willThrow(new RuntimeException("模擬通知配送失敗（AC-1検証用）"))
                .given(notificationDeliveryRunner).sendOne(any());

        eventDismissalService.sendDismissalNotification(
                eventId, TEAM_ID, organizerId, dismissalRequest());

        // 本丸: 業務行（解散記録）はコミットされている。
        EventEntity saved = transactionTemplate.execute(
                tx -> eventRepository.findById(eventId).orElseThrow());
        assertThat(saved.getDismissalNotificationSentAt())
                .as("通知配送が失敗しても解散記録の永続化は巻き戻らない")
                .isNotNull();
        assertThat(saved.getDismissalNotifiedBy()).isEqualTo(organizerId);

        // AFTER_COMMIT + @Async のリスナーが Runner を呼び、例外で配送は失敗する（非同期のため待つ）。
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                org.mockito.Mockito.verify(notificationDeliveryRunner).sendOne(any()));
    }

    @Test
    @DisplayName("AC-2: 業務トランザクションがロールバックした場合、通知は作られない")
    void 業務トランザクションのロールバック時は通知が作られない() {
        String nonce = String.valueOf(System.nanoTime());
        Long organizerId = insertUser("ev-ac2-organizer-" + nonce + "@example.com");
        Long attendeeId = insertUser("ev-ac2-attendee-" + nonce + "@example.com");
        Long eventId = insertEventWithAttendee(nonce, attendeeId);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            eventDismissalService.sendDismissalNotification(
                    eventId, TEAM_ID, organizerId, dismissalRequest());
            throw new RuntimeException("強制ロールバック（AC-2検証用）");
        })).isInstanceOf(RuntimeException.class);

        // 業務行がロールバックされていること（前提の確認）。
        EventEntity saved = transactionTemplate.execute(
                tx -> eventRepository.findById(eventId).orElseThrow());
        assertThat(saved.getDismissalNotificationSentAt()).isNull();

        // 本丸: AFTER_COMMIT は発火しないため通知配送は一度も呼ばれない。
        org.mockito.Mockito.verifyNoInteractions(notificationDeliveryRunner);
    }

    @Test
    @DisplayName("AC-3: コミット直後のイベント行を通知配送タイミングで参照でき、deny されず通知が作られる")
    void コミット直後のイベント行を参照して通知が作られる() {
        String nonce = String.valueOf(System.nanoTime());
        Long organizerId = insertUser("ev-ac3-organizer-" + nonce + "@example.com");
        Long attendeeId = insertUser("ev-ac3-attendee-" + nonce + "@example.com");
        Long eventId = insertEventWithAttendee(nonce, attendeeId);

        eventDismissalService.sendDismissalNotification(
                eventId, TEAM_ID, organizerId, dismissalRequest());

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationEntity> notifications = transactionTemplate.execute(
                    tx -> notificationRepository.findByUserIdOrderByCreatedAtDesc(
                            attendeeId, PageRequest.of(0, 10)).getContent());
            assertThat(notifications)
                    .as("解散通知が deny されずに作られていること")
                    .anyMatch(n -> "EVENT_DISMISSAL".equals(n.getNotificationType())
                            && eventId.equals(n.getSourceId()));
        });
    }

    /**
     * 解散通知リクエスト。
     *
     * <p>見守り者通知は本 IT の検証対象外（{@code CareEventNotificationService} は CMP-056 の
     * 対象外クラス）のため {@code false} にして経路を閉じる。</p>
     */
    private DismissalRequest dismissalRequest() {
        return new DismissalRequest("CMP-056 検証用の解散メッセージ", null, false);
    }

    /**
     * TEAM スコープの未解散イベントを1件作成し、{@code attendeeId} を RSVP=ATTENDING で紐付ける。
     *
     * <p>visibility は {@code PUBLIC}・status は {@code REGISTRATION_OPEN}（=
     * {@code ContentStatus.PUBLISHED}）とする。これにより F00 の {@code EventVisibilityResolver} が
     * membership 無しでも閲覧可と判定するため、AC-3 が「通知が作られない」ではなく
     * 「deny されずに作られる」ことを測れる。</p>
     *
     * @param nonce      slug の一意化に使うテストごとの識別子
     * @param attendeeId RSVP=ATTENDING で紐付ける参加者ユーザーID
     * @return 作成したイベントID
     */
    private Long insertEventWithAttendee(String nonce, Long attendeeId) {
        Long eventId = transactionTemplate.execute(tx -> eventRepository.save(EventEntity.builder()
                .scopeType(EventScopeType.TEAM)
                .scopeId(TEAM_ID)
                .slug("cmp056-dismissal-" + nonce)
                .subtitle("CMP-056 解散検証イベント")
                .status(EventStatus.REGISTRATION_OPEN)
                .visibility(EventVisibility.PUBLIC)
                .build()).getId());

        transactionTemplate.execute(tx -> rsvpResponseRepository.save(EventRsvpResponseEntity.builder()
                .eventId(eventId)
                .userId(attendeeId)
                .response("ATTENDING")
                .build()));
        return eventId;
    }

    /**
     * ACTIVE な users 行を1件作成し id を返す。
     *
     * <p>{@code ContactInviteUsedNotificationTransactionIT} と同じ注意点（実 DDL の NOT NULL 制約は
     * test プロファイルの {@code ddl-auto: create} では再現せず、実 DB で初めて落ちる）に従い、
     * {@code @Builder.Default} を持たない必須フィールド（{@code is_searchable} /
     * {@code locale} / {@code timezone} を含む）を明示する。</p>
     *
     * @param email 一意なメールアドレス
     * @return 作成したユーザーID
     */
    private Long insertUser(String email) {
        return transactionTemplate.execute(tx -> userRepository.save(UserEntity.builder()
                .email(email)
                .lastName("解散試験")
                .firstName("太郎")
                .displayName("解散試験ユーザー" + UUID.randomUUID().toString().substring(0, 8))
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .contactApprovalRequired(false)
                .build()).getId());
    }
}
