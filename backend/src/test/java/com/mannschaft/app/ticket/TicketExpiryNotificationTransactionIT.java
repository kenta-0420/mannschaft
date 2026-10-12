package com.mannschaft.app.ticket;

import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.ticket.entity.TicketBookEntity;
import com.mannschaft.app.ticket.repository.TicketBookRepository;
import com.mannschaft.app.ticket.service.TicketExpiryBatchService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Issue #2997（CMP-260827-1152）G6 — {@link TicketExpiryBatchService} の通知トランザクション分離を実 DB で検証する。
 *
 * <p>対象: {@code expireTickets}（期限切れ遷移 + 通知）と {@code sendExpiryNotifications}（期限前の事前通知）。</p>
 *
 * <ul>
 *   <li>AC-A2: コミットされたら、所有者に通知行が作られる（期限切れは status=EXPIRED も確定）。</li>
 *   <li>AC-A: 業務TXがロールバックしたら、通知行は 1 件も作られない。</li>
 *   <li>AC-B: 通知配送が失敗しても、{@code book.expire()} による EXPIRED 遷移はコミット済みで呼び出しは正常に返る。
 *       障害は {@link NotificationDeliveryRunner#sendOne} を spy で例外化して注入する。
 *       限界: 例外は Runner 境界で注入しており、通知の永続化 SQL そのものを MySQL 上で失敗させてはいない。
 *       是正前の欠陥（通知の例外が業務TXの rollback-only になる）は、Runner が業務TXの外
 *       （AFTER_COMMIT・別スレッド）で呼ばれることで構造的に閉じていることを AC-A と合わせて固定する。</li>
 *   <li>AC-B2: 複数チケットのうち 1 件の通知が失敗しても、他のチケットの通知行と EXPIRED 遷移は影響を受けない。</li>
 *   <li>AC-D: 両メソッドとも「送信済み」の印を持たない。期限切れ通知の冪等性は status が ACTIVE でなくなること
 *       （再実行で対象から外れる）、事前通知は暦日単位の窓で抽出する日次バッチであることに依る。
 *       本 IT は前者（再実行で再通知されない）を固定する。</li>
 * </ul>
 *
 * <p>クラスに {@code @Transactional} は付けない（付けるとコミットが起きず AFTER_COMMIT が発火しない偽の緑になる）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2997 G6 チケット期限切れ通知の通知トランザクション分離の実DB検証")
class TicketExpiryNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    @Autowired private TicketExpiryBatchService batchService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager em;

    @MockitoSpyBean private NotificationDeliveryRunner notificationDeliveryRunner;
    @MockitoSpyBean private TicketBookRepository bookRepository;

    // ─────────────────────────────────────────────
    // expireTickets
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("AC-A2: コミットされたら EXPIRED に遷移し、所有者に TICKET_EXPIRED の通知行が作られる")
    void expireCommitCreatesNotification() {
        Book b = expiredBook("a2");

        batchService.expireTickets();

        assertThat(statusOf(b.bookId)).isEqualTo(TicketBookStatus.EXPIRED);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(b.userId, b.bookId, "TICKET_EXPIRED")).hasSize(1));
    }

    @Test
    @DisplayName("AC-A: 項目TX（EXPIRED への保存）が失敗したら通知行は作られず、ACTIVE のまま残る")
    void saveFailureCreatesNoNotification() {
        Book b = expiredBook("a");
        willThrow(new RuntimeException("強制ロールバック（AC-A 検証用）"))
                .given(bookRepository).save(argThat(x -> x != null && b.bookId.equals(x.getId())));

        batchService.expireTickets();

        assertThat(statusOf(b.bookId)).as("ロールバックされ ACTIVE のまま（次回バッチで再試行）")
                .isEqualTo(TicketBookStatus.ACTIVE);
        await().during(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(b.userId, b.bookId, "TICKET_EXPIRED")).isEmpty());
        verify(notificationDeliveryRunner, never()).sendOne(argThat(r -> forBook(r, b.bookId)));
    }

    @Test
    @DisplayName("AC-B: 通知配送が失敗しても、book.expire() の EXPIRED 遷移はコミット済みで呼び出しは正常に返る")
    void deliveryFailureDoesNotRollBackExpire() {
        Book b = expiredBook("b");
        willThrow(new RuntimeException("模擬通知配送失敗（AC-B 検証用）"))
                .given(notificationDeliveryRunner).sendOne(any());

        batchService.expireTickets();

        // 別接続（別TX）から読んで、業務状態がコミット済みであることを確認する。
        assertThat(statusOf(b.bookId)).isEqualTo(TicketBookStatus.EXPIRED);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner).sendOne(argThat(r -> forBook(r, b.bookId))));
        assertThat(rows(b.userId, b.bookId, "TICKET_EXPIRED")).as("配送が失敗したので通知行は無い").isEmpty();
    }

    @Test
    @DisplayName("AC-B2: 1 件の通知が失敗しても、他のチケットの通知行と EXPIRED 遷移は影響を受けない")
    void oneFailureDoesNotAffectOthers() {
        Book ng = expiredBook("b2-ng");
        Book ok = expiredBook("b2-ok");
        willAnswer(inv -> {
            NotificationDeliveryRequest req = inv.getArgument(0);
            if (ng.bookId.equals(req.sourceId())) {
                throw new RuntimeException("模擬通知配送失敗（1 件のみ）");
            }
            return inv.callRealMethod();
        }).given(notificationDeliveryRunner).sendOne(any());

        batchService.expireTickets();

        assertThat(statusOf(ng.bookId)).isEqualTo(TicketBookStatus.EXPIRED);
        assertThat(statusOf(ok.bookId)).isEqualTo(TicketBookStatus.EXPIRED);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(ok.userId, ok.bookId, "TICKET_EXPIRED")).hasSize(1));
        assertThat(rows(ng.userId, ng.bookId, "TICKET_EXPIRED")).isEmpty();
    }

    @Test
    @DisplayName("AC-D: 再実行しても EXPIRED 済みのチケットは対象外で、通知は二重に作られない")
    void rerunDoesNotRenotify() {
        Book b = expiredBook("d");

        batchService.expireTickets();
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(b.userId, b.bookId, "TICKET_EXPIRED")).hasSize(1));

        batchService.expireTickets();

        await().during(2, TimeUnit.SECONDS).atMost(6, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(b.userId, b.bookId, "TICKET_EXPIRED")).hasSize(1));
    }

    // ─────────────────────────────────────────────
    // sendExpiryNotifications
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("AC-A2（事前通知）: コミットされたら所有者に TICKET_EXPIRY_WARNING の通知行が作られる")
    void warningCommitCreatesNotification() {
        Book b = warningBook("w-a2");

        batchService.sendExpiryNotifications();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(b.userId, b.bookId, "TICKET_EXPIRY_WARNING")).hasSize(1));
    }

    @Test
    @DisplayName("AC-A（事前通知）: 呼び出し元 TX がロールバックしたら通知行は作られない")
    void warningRollbackCreatesNoNotification() {
        Book b = warningBook("w-a");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            batchService.sendExpiryNotifications();
            throw new RuntimeException("強制ロールバック（AC-A 検証用）");
        })).isInstanceOf(RuntimeException.class);

        await().during(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(b.userId, b.bookId, "TICKET_EXPIRY_WARNING")).isEmpty());
        verify(notificationDeliveryRunner, never()).sendOne(argThat(r -> forBook(r, b.bookId)));
    }

    @Test
    @DisplayName("AC-B（事前通知）: 通知配送が失敗しても、呼び出しは正常に返りチケットの状態は変わらない")
    void warningDeliveryFailureStillReturnsNormally() {
        Book b = warningBook("w-b");
        willThrow(new RuntimeException("模擬通知配送失敗（AC-B 検証用）"))
                .given(notificationDeliveryRunner).sendOne(any());

        batchService.sendExpiryNotifications();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner).sendOne(argThat(r -> forBook(r, b.bookId))));
        assertThat(statusOf(b.bookId)).isEqualTo(TicketBookStatus.ACTIVE);
        assertThat(rows(b.userId, b.bookId, "TICKET_EXPIRY_WARNING")).isEmpty();
    }

    @Test
    @DisplayName("AC-B2（事前通知）: 1 件の通知が失敗しても、他のチケットの通知行は作られる")
    void warningOneFailureDoesNotAffectOthers() {
        Book ng = warningBook("w-b2-ng");
        Book ok = warningBook("w-b2-ok");
        willAnswer(inv -> {
            NotificationDeliveryRequest req = inv.getArgument(0);
            if (ng.bookId.equals(req.sourceId())) {
                throw new RuntimeException("模擬通知配送失敗（1 件のみ）");
            }
            return inv.callRealMethod();
        }).given(notificationDeliveryRunner).sendOne(any());

        batchService.sendExpiryNotifications();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(ok.userId, ok.bookId, "TICKET_EXPIRY_WARNING")).hasSize(1));
        assertThat(rows(ng.userId, ng.bookId, "TICKET_EXPIRY_WARNING")).isEmpty();
    }

    // ─────────────────────────────────────────────
    // フィクスチャ・観測
    // ─────────────────────────────────────────────

    private record Book(Long bookId, Long userId) {}

    private static boolean forBook(NotificationDeliveryRequest r, Long bookId) {
        return r != null && bookId.equals(r.sourceId());
    }

    /** 期限が過去の ACTIVE なチケット（所有者つき）を commit 済みで作る。 */
    private Book expiredBook(String tag) {
        return book(tag, LocalDateTime.now(JST).minusDays(1));
    }

    /** 期限が 7 日後の暦日に入る ACTIVE なチケットを作る（事前通知の 7 日前窓）。 */
    private Book warningBook(String tag) {
        return book(tag, LocalDate.now(JST).plusDays(7).atTime(12, 0));
    }

    private Book book(String tag, LocalDateTime expiresAt) {
        return transactionTemplate.execute(tx -> {
            Long userId = insertUser("g6-ticket-" + tag + "-" + System.nanoTime() + "@example.com");
            TicketBookEntity saved = bookRepository.save(TicketBookEntity.builder()
                    .teamId(9_100_000_000L + (System.nanoTime() % 1_000_000L))
                    .productId(1L)
                    .userId(userId)
                    .totalTickets(10)
                    .status(TicketBookStatus.ACTIVE)
                    .purchasedAt(LocalDateTime.now(JST).minusDays(30))
                    .expiresAt(expiresAt)
                    .build());
            return new Book(saved.getId(), userId);
        });
    }

    private TicketBookStatus statusOf(Long bookId) {
        return transactionTemplate.execute(tx -> bookRepository.findById(bookId).orElseThrow().getStatus());
    }

    private List<NotificationEntity> rows(Long userId, Long bookId, String type) {
        return transactionTemplate.execute(tx -> notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20)).getContent().stream()
                .filter(n -> type.equals(n.getNotificationType()) && bookId.equals(n.getSourceId()))
                .toList());
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'G6', 'チケット', 'G6 チケット', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }
}
