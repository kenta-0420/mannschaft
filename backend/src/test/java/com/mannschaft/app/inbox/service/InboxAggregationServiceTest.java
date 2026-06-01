package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.dto.InboxPageResponse;
import com.mannschaft.app.inbox.dto.LabelDto;
import com.mannschaft.app.inbox.entity.InboxLabelLinkEntity;
import com.mannschaft.app.inbox.entity.NotificationLabelEntity;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import com.mannschaft.app.inbox.repository.InboxLabelLinkRepository;
import com.mannschaft.app.inbox.repository.NotificationLabelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F04.11 {@link InboxAggregationService} 単体テスト（Mockito）。
 *
 * <p>設計書 03_business_logic.md §4・04_security_operations.md §1.2 から、IDOR 除外・状態マージ優先順位・
 * スヌーズ自動復帰・N+1 回避（まとめ取り）・フィルタ・ソートを受け入れ条件化する。
 * MVP は NOTIFICATION + TODO_DUE の 2 アダプタ前提（残 3 ソースは出陣③）。</p>
 *
 * <p><b>test-first（red 想定）</b>: 本体は三陣で実装する。現段階は
 * {@link UnsupportedOperationException} で失敗するのが正しい。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InboxAggregationService 単体テスト")
class InboxAggregationServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private InboxSourceAdapter notificationAdapter;

    @Mock
    private InboxSourceAdapter todoDueAdapter;

    @Mock
    private InboxPriorityNormalizer priorityNormalizer;

    @Mock
    private InboxItemStateRepository itemStateRepository;

    @Mock
    private InboxLabelLinkRepository labelLinkRepository;

    @Mock
    private NotificationLabelRepository labelRepository;

    private InboxAggregationService service;

    @BeforeEach
    void setUp() {
        given(notificationAdapter.sourceType()).willReturn(InboxSourceType.NOTIFICATION);
        given(todoDueAdapter.sourceType()).willReturn(InboxSourceType.TODO_DUE);
        // List<InboxSourceAdapter> は @InjectMocks で注入できないため手動構築する。
        service = new InboxAggregationService(
                List.of(notificationAdapter, todoDueAdapter),
                priorityNormalizer,
                itemStateRepository,
                labelLinkRepository,
                labelRepository);
    }

    /** 統一 DTO を組み立てるヘルパー（オーバーレイ未マージの素の項目）。 */
    private InboxItemDto item(InboxSourceType type, Long sourceId, InboxPriority priority,
                              LocalDateTime occurredAt) {
        return new InboxItemDto(
                type.name() + ":" + sourceId, type, sourceId, "title", "excerpt",
                priority, null, "/x/" + sourceId, occurredAt, InboxState.UNREAD, null, List.of());
    }

    // ─────────────────────────────────────────────────────────────────
    // IDOR: アダプタには userId を伝播し、他人宛て通知は混ざらない
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("IDOR 除外")
    class Idor {

        @Test
        @DisplayName("各アダプタの fetch に currentUserId が伝播する")
        void propagatesUserIdToAdapters() {
            given(notificationAdapter.fetch(anyLong())).willReturn(List.of());
            given(todoDueAdapter.fetch(anyLong())).willReturn(List.of());

            service.getInbox(USER_ID, "INBOX", null, null, null, 0, 20);

            verify(notificationAdapter).fetch(USER_ID);
            verify(todoDueAdapter).fetch(USER_ID);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 状態マージ優先順位: ARCHIVED > SNOOZED > READ > UNREAD
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("状態マージ優先順位（ARCHIVED > SNOOZED > READ > UNREAD）")
    class StateMerge {

        @Test
        @DisplayName("オーバーレイで archived_at がある項目は ARCHIVED に確定する")
        void archivedWins() {
            InboxItemDto raw = item(InboxSourceType.NOTIFICATION, 10L, InboxPriority.NORMAL,
                    LocalDateTime.now());
            given(notificationAdapter.fetch(USER_ID)).willReturn(List.of(raw));
            given(todoDueAdapter.fetch(USER_ID)).willReturn(List.of());
            var stateRow = new com.mannschaft.app.inbox.entity.InboxItemStateEntity();
            stateRow.setUserId(USER_ID);
            stateRow.setSourceType(InboxSourceType.NOTIFICATION);
            stateRow.setSourceId(10L);
            stateRow.setArchivedAt(LocalDateTime.now());
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any()))
                    .willReturn(List.of(stateRow));

            InboxPageResponse res = service.getInbox(USER_ID, "ARCHIVED", null, null, null, 0, 20);

            assertThat(res.items())
                    .singleElement()
                    .extracting(InboxItemDto::state)
                    .isEqualTo(InboxState.ARCHIVED);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // スヌーズ自動復帰: state=INBOX は snoozed_until <= now を含む
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("スヌーズ自動復帰")
    class SnoozeAutoReturn {

        @Test
        @DisplayName("期限切れスヌーズ（snoozed_until <= now）は state=INBOX に出る")
        void expiredSnooze_appearsInInbox() {
            InboxItemDto raw = item(InboxSourceType.NOTIFICATION, 20L, InboxPriority.NORMAL,
                    LocalDateTime.now());
            given(notificationAdapter.fetch(USER_ID)).willReturn(List.of(raw));
            given(todoDueAdapter.fetch(USER_ID)).willReturn(List.of());
            var expired = new com.mannschaft.app.inbox.entity.InboxItemStateEntity();
            expired.setUserId(USER_ID);
            expired.setSourceType(InboxSourceType.NOTIFICATION);
            expired.setSourceId(20L);
            expired.setSnoozedUntil(LocalDateTime.now().minusHours(1));
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any()))
                    .willReturn(List.of(expired));

            InboxPageResponse res = service.getInbox(USER_ID, "INBOX", null, null, null, 0, 20);

            assertThat(res.items()).extracting(InboxItemDto::sourceId).contains(20L);
        }

        @Test
        @DisplayName("未来スヌーズ（snoozed_until > now）は state=INBOX に出ない")
        void futureSnooze_hiddenFromInbox() {
            InboxItemDto raw = item(InboxSourceType.NOTIFICATION, 21L, InboxPriority.NORMAL,
                    LocalDateTime.now());
            given(notificationAdapter.fetch(USER_ID)).willReturn(List.of(raw));
            given(todoDueAdapter.fetch(USER_ID)).willReturn(List.of());
            var future = new com.mannschaft.app.inbox.entity.InboxItemStateEntity();
            future.setUserId(USER_ID);
            future.setSourceType(InboxSourceType.NOTIFICATION);
            future.setSourceId(21L);
            future.setSnoozedUntil(LocalDateTime.now().plusHours(3));
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any()))
                    .willReturn(List.of(future));

            InboxPageResponse res = service.getInbox(USER_ID, "INBOX", null, null, null, 0, 20);

            assertThat(res.items()).extracting(InboxItemDto::sourceId).doesNotContain(21L);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // N+1 回避: オーバーレイ/ラベルはまとめ取り（ソース件数に依らず定数回）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("N+1 回避（まとめ取り）")
    class NoNPlusOne {

        @Test
        @DisplayName("オーバーレイ状態は user_id でまとめ取り（item 件数に依らず 1 回）")
        void overlayFetchedOnce() {
            // ソースから多数の項目が返っても、状態まとめ取りは 1 回のみ
            given(notificationAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.NORMAL, LocalDateTime.now()),
                    item(InboxSourceType.NOTIFICATION, 2L, InboxPriority.NORMAL, LocalDateTime.now()),
                    item(InboxSourceType.NOTIFICATION, 3L, InboxPriority.NORMAL, LocalDateTime.now())));
            given(todoDueAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.TODO_DUE, 4L, InboxPriority.HIGH, LocalDateTime.now())));

            service.getInbox(USER_ID, "INBOX", null, null, null, 0, 20);

            verify(itemStateRepository, times(1)).findByUserIdAndSourceTypeIn(any(), any());
        }

        @Test
        @DisplayName("ラベルは sourceType ごとにまとめ取り（item 毎には引かない＝最大ソース種別数回）")
        void labelsFetchedBounded() {
            given(notificationAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.NORMAL, LocalDateTime.now()),
                    item(InboxSourceType.NOTIFICATION, 2L, InboxPriority.NORMAL, LocalDateTime.now())));
            given(todoDueAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.TODO_DUE, 3L, InboxPriority.HIGH, LocalDateTime.now())));

            service.getInbox(USER_ID, "INBOX", null, null, null, 0, 20);

            // sourceType ごとに 1 回（最大 2 回）＝ item 件数（3）に比例しない
            verify(labelLinkRepository, org.mockito.Mockito.atMost(2))
                    .findByUserIdAndSourceTypeAndSourceIdIn(any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // フィルタ・ソート
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("フィルタ・ソート")
    class FilterAndSort {

        @Test
        @DisplayName("sourceType フィルタ: NOTIFICATION 指定で TODO_DUE は除外される")
        void filterBySourceType() {
            given(notificationAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.NORMAL, LocalDateTime.now())));
            given(todoDueAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.TODO_DUE, 2L, InboxPriority.HIGH, LocalDateTime.now())));
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());

            InboxPageResponse res = service.getInbox(
                    USER_ID, "ALL", null, List.of(InboxSourceType.NOTIFICATION), null, 0, 20);

            assertThat(res.items()).extracting(InboxItemDto::sourceType)
                    .containsOnly(InboxSourceType.NOTIFICATION);
        }

        @Test
        @DisplayName("priority フィルタ: URGENT 指定で NORMAL は除外される")
        void filterByPriority() {
            given(notificationAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.URGENT, LocalDateTime.now()),
                    item(InboxSourceType.NOTIFICATION, 2L, InboxPriority.NORMAL, LocalDateTime.now())));
            given(todoDueAdapter.fetch(USER_ID)).willReturn(List.of());
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());

            InboxPageResponse res = service.getInbox(
                    USER_ID, "ALL", List.of(InboxPriority.URGENT), null, null, 0, 20);

            assertThat(res.items()).extracting(InboxItemDto::priority)
                    .containsOnly(InboxPriority.URGENT);
        }

        @Test
        @DisplayName("ソート: priority DESC, occurredAt DESC で並ぶ（URGENT が先頭）")
        void sortsByPriorityThenOccurredAt() {
            LocalDateTime now = LocalDateTime.now();
            given(notificationAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.NORMAL, now.minusHours(1)),
                    item(InboxSourceType.NOTIFICATION, 2L, InboxPriority.URGENT, now.minusHours(2))));
            given(todoDueAdapter.fetch(USER_ID)).willReturn(List.of());
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());

            InboxPageResponse res = service.getInbox(USER_ID, "ALL", null, null, null, 0, 20);

            assertThat(res.items()).first()
                    .extracting(InboxItemDto::priority)
                    .isEqualTo(InboxPriority.URGENT);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // summary
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("summary")
    class Summary {

        @Test
        @DisplayName("byState / byPriority / bySourceType を返す")
        void returnsCounts() {
            given(notificationAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.URGENT, LocalDateTime.now())));
            given(todoDueAdapter.fetch(USER_ID)).willReturn(List.of());
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());

            var summary = service.getSummary(USER_ID);

            assertThat(summary.bySourceType()).containsKey("NOTIFICATION");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 出陣③: 5 ソース集約（NOTIFICATION/TODO_DUE + ANNOUNCEMENT/MENTION/CONFIRMABLE）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("5 ソース集約")
    class FiveSources {

        /** 5 アダプタ（全ソース）を注入したサービスを構築する。 */
        private InboxAggregationService fiveSourceService() {
            InboxSourceAdapter announcementAdapter = org.mockito.Mockito.mock(InboxSourceAdapter.class);
            InboxSourceAdapter mentionAdapter = org.mockito.Mockito.mock(InboxSourceAdapter.class);
            InboxSourceAdapter confirmableAdapter = org.mockito.Mockito.mock(InboxSourceAdapter.class);

            given(announcementAdapter.sourceType()).willReturn(InboxSourceType.ANNOUNCEMENT);
            given(mentionAdapter.sourceType()).willReturn(InboxSourceType.MENTION);
            given(confirmableAdapter.sourceType()).willReturn(InboxSourceType.CONFIRMABLE);

            LocalDateTime now = LocalDateTime.now();
            given(notificationAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.NORMAL, now)));
            given(todoDueAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.TODO_DUE, 2L, InboxPriority.HIGH, now)));
            given(announcementAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.ANNOUNCEMENT, 3L, InboxPriority.NORMAL, now)));
            given(mentionAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.MENTION, 4L, InboxPriority.HIGH, now)));
            given(confirmableAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.CONFIRMABLE, 5L, InboxPriority.URGENT, now)));
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());

            return new InboxAggregationService(
                    List.of(notificationAdapter, todoDueAdapter, announcementAdapter,
                            mentionAdapter, confirmableAdapter),
                    priorityNormalizer,
                    itemStateRepository,
                    labelLinkRepository,
                    labelRepository);
        }

        @Test
        @DisplayName("5 ソースすべてが一覧に含まれる")
        void allFiveSourcesAppear() {
            InboxAggregationService svc = fiveSourceService();

            InboxPageResponse res = svc.getInbox(USER_ID, "ALL", null, null, null, 0, 20);

            assertThat(res.items()).extracting(InboxItemDto::sourceType)
                    .containsExactlyInAnyOrder(
                            InboxSourceType.NOTIFICATION,
                            InboxSourceType.TODO_DUE,
                            InboxSourceType.ANNOUNCEMENT,
                            InboxSourceType.MENTION,
                            InboxSourceType.CONFIRMABLE);
        }

        @Test
        @DisplayName("5 ソースでもオーバーレイのまとめ取りは 1 回（N+1 回避を維持）")
        void overlayFetchedOnceAcrossFiveSources() {
            InboxAggregationService svc = fiveSourceService();

            svc.getInbox(USER_ID, "ALL", null, null, null, 0, 20);

            verify(itemStateRepository, times(1)).findByUserIdAndSourceTypeIn(any(), any());
        }

        @Test
        @DisplayName("summary の bySourceType に 5 ソースすべてが計上される")
        void summaryCountsAllFive() {
            InboxAggregationService svc = fiveSourceService();

            var summary = svc.getSummary(USER_ID);

            assertThat(summary.bySourceType())
                    .containsKeys("NOTIFICATION", "TODO_DUE", "ANNOUNCEMENT", "MENTION", "CONFIRMABLE");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Phase 2: ラベル名の解決（LabelDto の name/color/icon/sortOrder が暫定 null でない）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ラベル名解決（Phase 2）")
    class LabelResolution {

        @Test
        @DisplayName("付与済みラベルは name/color/icon/sortOrder まで解決され、暫定 null にならない")
        void resolvesLabelName() {
            UUID labelId = UUID.randomUUID();
            given(notificationAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 10L, InboxPriority.NORMAL, LocalDateTime.now())));
            given(todoDueAdapter.fetch(USER_ID)).willReturn(List.of());
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());

            InboxLabelLinkEntity link = new InboxLabelLinkEntity();
            link.setLabelId(labelId);
            link.setUserId(USER_ID);
            link.setSourceType(InboxSourceType.NOTIFICATION);
            link.setSourceId(10L);
            given(labelLinkRepository.findByUserIdAndSourceTypeAndSourceIdIn(any(), any(), any()))
                    .willReturn(List.of(link));

            NotificationLabelEntity label = new NotificationLabelEntity();
            label.setId(labelId);
            label.setUserId(USER_ID);
            label.setName("経理");
            label.setColor("#f59e0b");
            label.setIcon("pi-wallet");
            label.setSortOrder(2);
            given(labelRepository.findByIdIn(any())).willReturn(List.of(label));

            InboxPageResponse res = service.getInbox(USER_ID, "ALL", null, null, null, 0, 20);

            assertThat(res.items()).singleElement()
                    .extracting(InboxItemDto::labels)
                    .satisfies(labels -> {
                        @SuppressWarnings("unchecked")
                        List<LabelDto> l = (List<LabelDto>) labels;
                        assertThat(l).singleElement().satisfies(dto -> {
                            assertThat(dto.id()).isEqualTo(labelId);
                            assertThat(dto.name()).isEqualTo("経理");
                            assertThat(dto.color()).isEqualTo("#f59e0b");
                            assertThat(dto.icon()).isEqualTo("pi-wallet");
                            assertThat(dto.sortOrder()).isEqualTo(2);
                        });
                    });
        }

        @Test
        @DisplayName("ラベル本体は labelId 集合で 1 回だけまとめ取りする（N+1 回避）")
        void resolvesLabelsInOneFetch() {
            given(notificationAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.NORMAL, LocalDateTime.now()),
                    item(InboxSourceType.NOTIFICATION, 2L, InboxPriority.NORMAL, LocalDateTime.now())));
            given(todoDueAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.TODO_DUE, 3L, InboxPriority.HIGH, LocalDateTime.now())));
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());

            UUID l1 = UUID.randomUUID();
            InboxLabelLinkEntity link1 = new InboxLabelLinkEntity();
            link1.setLabelId(l1);
            link1.setUserId(USER_ID);
            link1.setSourceType(InboxSourceType.NOTIFICATION);
            link1.setSourceId(1L);
            given(labelLinkRepository.findByUserIdAndSourceTypeAndSourceIdIn(any(), any(), any()))
                    .willReturn(List.of(link1));

            NotificationLabelEntity label = new NotificationLabelEntity();
            label.setId(l1);
            label.setUserId(USER_ID);
            label.setName("tag");
            label.setSortOrder(0);
            given(labelRepository.findByIdIn(any())).willReturn(List.of(label));

            service.getInbox(USER_ID, "ALL", null, null, null, 0, 20);

            // item 件数（3）に依らず findByIdIn は 1 回のみ
            verify(labelRepository, times(1)).findByIdIn(any());
        }

        @Test
        @DisplayName("論理削除済みラベルのリンク（孤児）は findByIdIn で脱落し、表示に出ない")
        void orphanLinkDropped() {
            UUID deletedLabelId = UUID.randomUUID();
            given(notificationAdapter.fetch(USER_ID)).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 10L, InboxPriority.NORMAL, LocalDateTime.now())));
            given(todoDueAdapter.fetch(USER_ID)).willReturn(List.of());
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());

            InboxLabelLinkEntity link = new InboxLabelLinkEntity();
            link.setLabelId(deletedLabelId);
            link.setUserId(USER_ID);
            link.setSourceType(InboxSourceType.NOTIFICATION);
            link.setSourceId(10L);
            given(labelLinkRepository.findByUserIdAndSourceTypeAndSourceIdIn(any(), any(), any()))
                    .willReturn(List.of(link));
            // ラベル本体は @SQLRestriction で除外され findByIdIn は空を返す
            given(labelRepository.findByIdIn(any())).willReturn(List.of());

            InboxPageResponse res = service.getInbox(USER_ID, "ALL", null, null, null, 0, 20);

            assertThat(res.items()).singleElement()
                    .extracting(InboxItemDto::labels)
                    .satisfies(labels -> assertThat((List<?>) labels).isEmpty());
        }
    }
}
