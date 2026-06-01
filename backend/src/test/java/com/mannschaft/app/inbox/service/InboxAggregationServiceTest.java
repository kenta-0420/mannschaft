package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.dto.InboxItemRef;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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

    /**
     * 統一 DTO を組み立てるヘルパー（オーバーレイ未マージの素の項目）。
     * canonicalRef は自分自身キー（畳まれない）・groupCount=1・members 自分 1 件を既定とする。
     */
    private InboxItemDto item(InboxSourceType type, Long sourceId, InboxPriority priority,
                              LocalDateTime occurredAt) {
        String selfKey = type.name() + ":" + sourceId;
        return new InboxItemDto(
                selfKey, type, sourceId, "title", "excerpt",
                priority, null, "/x/" + sourceId, occurredAt, InboxState.UNREAD, null, List.of(),
                selfKey, 1, List.of(new com.mannschaft.app.inbox.dto.InboxItemRef(type, sourceId)));
    }

    /**
     * canonicalRef・state を明示した統一 DTO ヘルパー（名寄せテスト用）。
     * 同一 canonicalRef を渡した複数項目は集約で 1 カードへ畳まれる。
     */
    private InboxItemDto itemWithRef(InboxSourceType type, Long sourceId, InboxPriority priority,
                                     LocalDateTime occurredAt, String canonicalRef,
                                     InboxState state, List<LabelDto> labels) {
        String selfKey = type.name() + ":" + sourceId;
        return new InboxItemDto(
                selfKey, type, sourceId, "title", "excerpt",
                priority, null, "/x/" + sourceId, occurredAt, state, null, labels,
                canonicalRef, 1, List.of(new com.mannschaft.app.inbox.dto.InboxItemRef(type, sourceId)));
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
            given(notificationAdapter.fetch(anyLong(), anyInt())).willReturn(List.of());
            given(todoDueAdapter.fetch(anyLong(), anyInt())).willReturn(List.of());

            service.getInbox(USER_ID, "INBOX", null, null, null, 0, 20);

            verify(notificationAdapter).fetch(eq(USER_ID), anyInt());
            verify(todoDueAdapter).fetch(eq(USER_ID), anyInt());
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
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(raw));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of());
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
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(raw));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of());
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
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(raw));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of());
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
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.NORMAL, LocalDateTime.now()),
                    item(InboxSourceType.NOTIFICATION, 2L, InboxPriority.NORMAL, LocalDateTime.now()),
                    item(InboxSourceType.NOTIFICATION, 3L, InboxPriority.NORMAL, LocalDateTime.now())));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    item(InboxSourceType.TODO_DUE, 4L, InboxPriority.HIGH, LocalDateTime.now())));

            service.getInbox(USER_ID, "INBOX", null, null, null, 0, 20);

            verify(itemStateRepository, times(1)).findByUserIdAndSourceTypeIn(any(), any());
        }

        @Test
        @DisplayName("ラベルは sourceType ごとにまとめ取り（item 毎には引かない＝最大ソース種別数回）")
        void labelsFetchedBounded() {
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.NORMAL, LocalDateTime.now()),
                    item(InboxSourceType.NOTIFICATION, 2L, InboxPriority.NORMAL, LocalDateTime.now())));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
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
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.NORMAL, LocalDateTime.now())));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
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
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.URGENT, LocalDateTime.now()),
                    item(InboxSourceType.NOTIFICATION, 2L, InboxPriority.NORMAL, LocalDateTime.now())));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of());
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
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.NORMAL, now.minusHours(1)),
                    item(InboxSourceType.NOTIFICATION, 2L, InboxPriority.URGENT, now.minusHours(2))));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of());
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
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.URGENT, LocalDateTime.now())));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of());
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
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.NORMAL, now)));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    item(InboxSourceType.TODO_DUE, 2L, InboxPriority.HIGH, now)));
            given(announcementAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    item(InboxSourceType.ANNOUNCEMENT, 3L, InboxPriority.NORMAL, now)));
            given(mentionAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    item(InboxSourceType.MENTION, 4L, InboxPriority.HIGH, now)));
            given(confirmableAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
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
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 10L, InboxPriority.NORMAL, LocalDateTime.now())));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of());
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
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.NORMAL, LocalDateTime.now()),
                    item(InboxSourceType.NOTIFICATION, 2L, InboxPriority.NORMAL, LocalDateTime.now())));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
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
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 10L, InboxPriority.NORMAL, LocalDateTime.now())));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of());
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

    // ─────────────────────────────────────────────────────────────────
    // Phase 3 ①: 名寄せ（canonicalRef 畳み込み・groupCount/members）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("名寄せ（Phase 3 ①）")
    class Dedupe {

        @Test
        @DisplayName("同一 canonicalRef の NOTIFICATION + ANNOUNCEMENT は 1 カードに畳まれ groupCount=2・members 2 件")
        void foldsSameCanonicalRef() {
            LocalDateTime now = LocalDateTime.now();
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    itemWithRef(InboxSourceType.NOTIFICATION, 100L, InboxPriority.NORMAL, now,
                            "BLOG_POST:7", InboxState.UNREAD, List.of())));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of());
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());

            // ANNOUNCEMENT アダプタを追加注入したサービスで同一 canonicalRef を返す
            InboxSourceAdapter announcementAdapter = org.mockito.Mockito.mock(InboxSourceAdapter.class);
            given(announcementAdapter.sourceType()).willReturn(InboxSourceType.ANNOUNCEMENT);
            given(announcementAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    itemWithRef(InboxSourceType.ANNOUNCEMENT, 200L, InboxPriority.NORMAL, now,
                            "BLOG_POST:7", InboxState.READ, List.of())));
            InboxAggregationService svc = new InboxAggregationService(
                    List.of(notificationAdapter, todoDueAdapter, announcementAdapter),
                    priorityNormalizer, itemStateRepository, labelLinkRepository, labelRepository);

            InboxPageResponse res = svc.getInbox(USER_ID, "ALL", null, null, null, 0, 20);

            assertThat(res.items()).singleElement().satisfies(card -> {
                assertThat(card.groupCount()).isEqualTo(2);
                assertThat(card.groupMembers()).extracting(InboxItemRef::sourceType)
                        .containsExactlyInAnyOrder(
                                InboxSourceType.NOTIFICATION, InboxSourceType.ANNOUNCEMENT);
                assertThat(card.canonicalRef()).isEqualTo("BLOG_POST:7");
            });
        }

        @Test
        @DisplayName("異なる実体（canonicalRef が違う）は畳まれない（誤突合の安全弁）")
        void doesNotFoldDifferentEntities() {
            LocalDateTime now = LocalDateTime.now();
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    itemWithRef(InboxSourceType.NOTIFICATION, 100L, InboxPriority.NORMAL, now,
                            "BLOG_POST:7", InboxState.UNREAD, List.of()),
                    itemWithRef(InboxSourceType.NOTIFICATION, 101L, InboxPriority.NORMAL, now,
                            "BLOG_POST:8", InboxState.UNREAD, List.of())));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of());
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());

            InboxPageResponse res = service.getInbox(USER_ID, "ALL", null, null, null, 0, 20);

            assertThat(res.items()).hasSize(2);
            assertThat(res.items()).allSatisfy(card -> assertThat(card.groupCount()).isEqualTo(1));
        }

        @Test
        @DisplayName("正規化不能（自分自身キー）の 2 件は畳まれない（誤突合の安全弁）")
        void doesNotFoldSelfKeys() {
            LocalDateTime now = LocalDateTime.now();
            // MENTION の自分自身キーは "MENTION:{id}" でそれぞれ固有 → 別カード
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    itemWithRef(InboxSourceType.MENTION, 1L, InboxPriority.HIGH, now,
                            "MENTION:1", InboxState.UNREAD, List.of()),
                    itemWithRef(InboxSourceType.MENTION, 2L, InboxPriority.HIGH, now,
                            "MENTION:2", InboxState.UNREAD, List.of())));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of());
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());

            InboxPageResponse res = service.getInbox(USER_ID, "ALL", null, null, null, 0, 20);

            assertThat(res.items()).hasSize(2);
        }

        @Test
        @DisplayName("単一項目は groupCount=1・members 自分 1 件")
        void singleItemGroupCountOne() {
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.NORMAL, LocalDateTime.now())));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of());
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());

            InboxPageResponse res = service.getInbox(USER_ID, "ALL", null, null, null, 0, 20);

            assertThat(res.items()).singleElement().satisfies(card -> {
                assertThat(card.groupCount()).isEqualTo(1);
                assertThat(card.groupMembers()).singleElement()
                        .extracting(InboxItemRef::sourceId).isEqualTo(1L);
            });
        }

        @Test
        @DisplayName("代表は priority 上位（URGENT が代表になる）")
        void representativeIsHighestPriority() {
            LocalDateTime now = LocalDateTime.now();
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    itemWithRef(InboxSourceType.NOTIFICATION, 100L, InboxPriority.NORMAL, now,
                            "BLOG_POST:7", InboxState.READ, List.of())));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of());
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());

            InboxSourceAdapter announcementAdapter = org.mockito.Mockito.mock(InboxSourceAdapter.class);
            given(announcementAdapter.sourceType()).willReturn(InboxSourceType.ANNOUNCEMENT);
            given(announcementAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    itemWithRef(InboxSourceType.ANNOUNCEMENT, 200L, InboxPriority.URGENT, now,
                            "BLOG_POST:7", InboxState.UNREAD, List.of())));
            InboxAggregationService svc = new InboxAggregationService(
                    List.of(notificationAdapter, todoDueAdapter, announcementAdapter),
                    priorityNormalizer, itemStateRepository, labelLinkRepository, labelRepository);

            InboxPageResponse res = svc.getInbox(USER_ID, "ALL", null, null, null, 0, 20);

            assertThat(res.items()).singleElement().satisfies(card -> {
                assertThat(card.priority()).isEqualTo(InboxPriority.URGENT);
                assertThat(card.sourceType()).isEqualTo(InboxSourceType.ANNOUNCEMENT);
            });
        }

        @Test
        @DisplayName("labels は構成メンバーの和集合になる（各メンバーの DB ラベルを統合）")
        void labelsAreUnion() {
            LocalDateTime now = LocalDateTime.now();
            UUID labelA = UUID.randomUUID();
            UUID labelB = UUID.randomUUID();

            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    itemWithRef(InboxSourceType.NOTIFICATION, 100L, InboxPriority.NORMAL, now,
                            "BLOG_POST:7", InboxState.UNREAD, List.of())));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of());
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());

            InboxSourceAdapter announcementAdapter = org.mockito.Mockito.mock(InboxSourceAdapter.class);
            given(announcementAdapter.sourceType()).willReturn(InboxSourceType.ANNOUNCEMENT);
            given(announcementAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    itemWithRef(InboxSourceType.ANNOUNCEMENT, 200L, InboxPriority.NORMAL, now,
                            "BLOG_POST:7", InboxState.UNREAD, List.of())));

            // ラベルは DB 経由（labelLinkRepository → labelRepository）で解決される。
            // NOTIFICATION:100 → labelA、ANNOUNCEMENT:200 → labelB を付与する。
            InboxLabelLinkEntity linkA = new InboxLabelLinkEntity();
            linkA.setLabelId(labelA);
            linkA.setUserId(USER_ID);
            linkA.setSourceType(InboxSourceType.NOTIFICATION);
            linkA.setSourceId(100L);
            InboxLabelLinkEntity linkB = new InboxLabelLinkEntity();
            linkB.setLabelId(labelB);
            linkB.setUserId(USER_ID);
            linkB.setSourceType(InboxSourceType.ANNOUNCEMENT);
            linkB.setSourceId(200L);
            given(labelLinkRepository.findByUserIdAndSourceTypeAndSourceIdIn(
                    any(), org.mockito.ArgumentMatchers.eq(InboxSourceType.NOTIFICATION), any()))
                    .willReturn(List.of(linkA));
            given(labelLinkRepository.findByUserIdAndSourceTypeAndSourceIdIn(
                    any(), org.mockito.ArgumentMatchers.eq(InboxSourceType.ANNOUNCEMENT), any()))
                    .willReturn(List.of(linkB));

            NotificationLabelEntity la = new NotificationLabelEntity();
            la.setId(labelA);
            la.setUserId(USER_ID);
            la.setName("A");
            la.setSortOrder(0);
            NotificationLabelEntity lb = new NotificationLabelEntity();
            lb.setId(labelB);
            lb.setUserId(USER_ID);
            lb.setName("B");
            lb.setSortOrder(1);
            given(labelRepository.findByIdIn(any())).willReturn(List.of(la, lb));

            InboxAggregationService svc = new InboxAggregationService(
                    List.of(notificationAdapter, todoDueAdapter, announcementAdapter),
                    priorityNormalizer, itemStateRepository, labelLinkRepository, labelRepository);

            InboxPageResponse res = svc.getInbox(USER_ID, "ALL", null, null, null, 0, 20);

            assertThat(res.items()).singleElement()
                    .extracting(InboxItemDto::labels)
                    .satisfies(labels -> {
                        @SuppressWarnings("unchecked")
                        List<LabelDto> l = (List<LabelDto>) labels;
                        assertThat(l).extracting(LabelDto::id)
                                .containsExactlyInAnyOrder(labelA, labelB);
                    });
        }

        @Test
        @DisplayName("state は最も未処理側（UNREAD + READ → UNREAD）になる")
        void stateIsMostUnprocessed() {
            LocalDateTime now = LocalDateTime.now();
            given(notificationAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    itemWithRef(InboxSourceType.NOTIFICATION, 100L, InboxPriority.NORMAL, now,
                            "BLOG_POST:7", InboxState.READ, List.of())));
            given(todoDueAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of());
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());

            InboxSourceAdapter announcementAdapter = org.mockito.Mockito.mock(InboxSourceAdapter.class);
            given(announcementAdapter.sourceType()).willReturn(InboxSourceType.ANNOUNCEMENT);
            given(announcementAdapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(
                    itemWithRef(InboxSourceType.ANNOUNCEMENT, 200L, InboxPriority.NORMAL, now,
                            "BLOG_POST:7", InboxState.UNREAD, List.of())));
            InboxAggregationService svc = new InboxAggregationService(
                    List.of(notificationAdapter, todoDueAdapter, announcementAdapter),
                    priorityNormalizer, itemStateRepository, labelLinkRepository, labelRepository);

            InboxPageResponse res = svc.getInbox(USER_ID, "ALL", null, null, null, 0, 20);

            assertThat(res.items()).singleElement()
                    .extracting(InboxItemDto::state).isEqualTo(InboxState.UNREAD);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Phase 3 ③: 境界付きウィンドウページング（取りこぼしゼロ・全ソース Pageable・完全全順序）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("境界付きウィンドウページング（Phase 3 ③）")
    class BoundedWindowPaging {

        /**
         * 自ソース内の正しい順序（priority 降順 → occurredAt 降順）の上位 window 件だけを返す
         * 「境界を守る」ソースアダプタを模す。各 fetch 呼び出しの window を記録する。
         */
        private final class WindowedFakeAdapter implements InboxSourceAdapter {
            private final InboxSourceType type;
            private final List<InboxItemDto> all;          // このソースの全項目（自ソース内で正しい順序）
            private final List<Integer> capturedWindows = new ArrayList<>();

            WindowedFakeAdapter(InboxSourceType type, List<InboxItemDto> all) {
                this.type = type;
                this.all = all;
            }

            @Override
            public InboxSourceType sourceType() {
                return type;
            }

            @Override
            public List<InboxItemDto> fetch(Long userId, int window) {
                capturedWindows.add(window);
                if (window <= 0) {
                    return List.of();
                }
                // 自ソース内の正しい順序で上位 window 件のみ（DB の ORDER BY ... LIMIT 相当）
                return all.stream().limit(window).toList();
            }

            @Override
            public boolean isVisibleTo(Long userId, Long sourceId) {
                return true;
            }
        }

        /** NOTIFICATION ソースの項目を occurredAt 降順（新着順）に N 件生成する（全件 NORMAL）。 */
        private List<InboxItemDto> notifications(int count) {
            LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
            List<InboxItemDto> list = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                // i が小さいほど新しい（base から i 分ずつ古くする）→ 自ソース内で新着順
                list.add(item(InboxSourceType.NOTIFICATION, (long) (1000 + i),
                        InboxPriority.NORMAL, base.minusMinutes(i)));
            }
            return list;
        }

        private InboxAggregationService serviceWith(InboxSourceAdapter... adapters) {
            given(itemStateRepository.findByUserIdAndSourceTypeIn(any(), any())).willReturn(List.of());
            return new InboxAggregationService(
                    List.of(adapters), priorityNormalizer,
                    itemStateRepository, labelLinkRepository, labelRepository);
        }

        @Test
        @DisplayName("page0 と page1 は重複・欠落なく連続する（同一ウィンドウからの決定的スライス）")
        void pagesAreContiguousNoOverlapNoGap() {
            // 単一ソースに 100 件。size=10 で page0/page1 を取り、連続性を検証する。
            WindowedFakeAdapter notif =
                    new WindowedFakeAdapter(InboxSourceType.NOTIFICATION, notifications(100));
            InboxAggregationService svc = serviceWith(notif);

            InboxPageResponse p0 = svc.getInbox(USER_ID, "ALL", null, null, null, 0, 10);
            InboxPageResponse p1 = svc.getInbox(USER_ID, "ALL", null, null, null, 1, 10);

            assertThat(p0.items()).hasSize(10);
            assertThat(p1.items()).hasSize(10);

            List<Long> ids0 = p0.items().stream().map(InboxItemDto::sourceId).toList();
            List<Long> ids1 = p1.items().stream().map(InboxItemDto::sourceId).toList();

            // 重複なし
            assertThat(ids0).doesNotContainAnyElementsOf(ids1);

            // 欠落なし: 全 100 件を新着順に並べた上位 20 件＝page0(0..9)+page1(10..19) と一致する。
            // notifications() は新着順（id=1000 が最新）なので上位 20 = id 1000..1019。
            List<Long> expectedTop20 = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                expectedTop20.add((long) (1000 + i));
            }
            List<Long> actualTop20 = new ArrayList<>(ids0);
            actualTop20.addAll(ids1);
            assertThat(actualTop20).containsExactlyElementsOf(expectedTop20);
        }

        @Test
        @DisplayName("ウィンドウ境界: 全 size 件ちょうどなら hasMore=false、size+1 件なら hasMore=true")
        void hasMoreAtWindowBoundary() {
            // size ちょうど（10 件）→ hasMore=false
            WindowedFakeAdapter exact =
                    new WindowedFakeAdapter(InboxSourceType.NOTIFICATION, notifications(10));
            InboxPageResponse resExact = serviceWith(exact)
                    .getInbox(USER_ID, "ALL", null, null, null, 0, 10);
            assertThat(resExact.items()).hasSize(10);
            assertThat(resExact.hasMore()).isFalse();

            // size+1 件（11 件）→ page0 は 10 件・hasMore=true
            WindowedFakeAdapter plusOne =
                    new WindowedFakeAdapter(InboxSourceType.NOTIFICATION, notifications(11));
            InboxPageResponse resPlus = serviceWith(plusOne)
                    .getInbox(USER_ID, "ALL", null, null, null, 0, 10);
            assertThat(resPlus.items()).hasSize(10);
            assertThat(resPlus.hasMore()).isTrue();
        }

        @Test
        @DisplayName("各アダプタは window >= (page+1)*size で呼ばれる（当該ページを取りこぼさない下限）")
        void adaptersReceiveWindowAtLeastCoveringPage() {
            WindowedFakeAdapter notif =
                    new WindowedFakeAdapter(InboxSourceType.NOTIFICATION, notifications(100));
            WindowedFakeAdapter todo =
                    new WindowedFakeAdapter(InboxSourceType.TODO_DUE, List.of());
            InboxAggregationService svc = serviceWith(notif, todo);

            // page=2, size=10 → (page+1)*size = 30 をウィンドウは下回ってはならない
            svc.getInbox(USER_ID, "ALL", null, null, null, 2, 10);

            assertThat(notif.capturedWindows).hasSize(1);
            assertThat(notif.capturedWindows.get(0)).isGreaterThanOrEqualTo(30);
            // 全ソースに同一ウィンドウが渡る（どれかが小さくて取りこぼす事故を防ぐ）
            assertThat(todo.capturedWindows.get(0)).isEqualTo(notif.capturedWindows.get(0));
        }

        @Test
        @DisplayName("同 priority・同 occurredAt の同着は sourceType→sourceId で決定的順序になる（タイブレーク）")
        void tieBreakDeterministicOrder() {
            LocalDateTime t = LocalDateTime.of(2026, 1, 1, 12, 0);
            // すべて priority=NORMAL・occurredAt=t の同着。sourceType/sourceId だけが異なる。
            WindowedFakeAdapter notif = new WindowedFakeAdapter(InboxSourceType.NOTIFICATION, List.of(
                    item(InboxSourceType.NOTIFICATION, 3L, InboxPriority.NORMAL, t),
                    item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.NORMAL, t),
                    item(InboxSourceType.NOTIFICATION, 2L, InboxPriority.NORMAL, t)));
            WindowedFakeAdapter ann = new WindowedFakeAdapter(InboxSourceType.ANNOUNCEMENT, List.of(
                    item(InboxSourceType.ANNOUNCEMENT, 9L, InboxPriority.NORMAL, t)));
            InboxAggregationService svc = serviceWith(notif, ann);

            // 2 回呼んで完全に同じ順序になること（決定性）＋ 期待する全順序であること。
            List<org.assertj.core.groups.Tuple> order1 = svc
                    .getInbox(USER_ID, "ALL", null, null, null, 0, 20).items().stream()
                    .map(it -> org.assertj.core.groups.Tuple.tuple(it.sourceType(), it.sourceId()))
                    .toList();
            List<org.assertj.core.groups.Tuple> order2 = svc
                    .getInbox(USER_ID, "ALL", null, null, null, 0, 20).items().stream()
                    .map(it -> org.assertj.core.groups.Tuple.tuple(it.sourceType(), it.sourceId()))
                    .toList();

            assertThat(order1).isEqualTo(order2);
            // ANNOUNCEMENT < NOTIFICATION（名前昇順）→ ANNOUNCEMENT:9 が先頭、その後 NOTIFICATION を id 昇順。
            assertThat(order1).containsExactly(
                    org.assertj.core.groups.Tuple.tuple(InboxSourceType.ANNOUNCEMENT, 9L),
                    org.assertj.core.groups.Tuple.tuple(InboxSourceType.NOTIFICATION, 1L),
                    org.assertj.core.groups.Tuple.tuple(InboxSourceType.NOTIFICATION, 2L),
                    org.assertj.core.groups.Tuple.tuple(InboxSourceType.NOTIFICATION, 3L));
        }

        @Test
        @DisplayName("名寄せ畳み込み後の件数でページング（hasMore）が決まる")
        void pagingUsesFoldedCount() {
            LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
            // 同一 canonicalRef の 2 件（NOTIFICATION + ANNOUNCEMENT）＝畳むと 1 件。
            WindowedFakeAdapter notif = new WindowedFakeAdapter(InboxSourceType.NOTIFICATION, List.of(
                    itemWithRef(InboxSourceType.NOTIFICATION, 100L, InboxPriority.NORMAL, now,
                            "BLOG_POST:7", InboxState.UNREAD, List.of())));
            WindowedFakeAdapter ann = new WindowedFakeAdapter(InboxSourceType.ANNOUNCEMENT, List.of(
                    itemWithRef(InboxSourceType.ANNOUNCEMENT, 200L, InboxPriority.NORMAL, now,
                            "BLOG_POST:7", InboxState.UNREAD, List.of())));
            InboxAggregationService svc = serviceWith(notif, ann);

            // 生 2 件だが畳み込み後 1 件。size=1 なら 1 件ちょうど＝hasMore=false（畳み後件数で判定）。
            InboxPageResponse res = svc.getInbox(USER_ID, "ALL", null, null, null, 0, 1);

            assertThat(res.items()).hasSize(1);
            assertThat(res.totalEstimated()).isEqualTo(1L);
            assertThat(res.hasMore()).isFalse();
            assertThat(res.items().get(0).groupCount()).isEqualTo(2);
        }
    }
}
