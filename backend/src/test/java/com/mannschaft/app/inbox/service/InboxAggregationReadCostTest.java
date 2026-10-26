package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.dto.InboxItemRef;
import com.mannschaft.app.inbox.entity.InboxItemStateEntity;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import com.mannschaft.app.inbox.repository.InboxLabelLinkRepository;
import com.mannschaft.app.inbox.repository.NotificationLabelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 件数サマリが表示用ラベルを読まないことを固定する性能回帰テスト。 */
@ExtendWith(MockitoExtension.class)
class InboxAggregationReadCostTest {
    private static final Long USER_ID = 31L;

    @Mock private InboxSourceAdapter adapter;
    @Mock private InboxItemStateRepository states;
    @Mock private InboxLabelLinkRepository links;
    @Mock private NotificationLabelRepository labels;
    @Mock private InboxLabelSuggestionRules suggestions;
    @Captor private ArgumentCaptor<Collection<Long>> idsCaptor;

    private InboxAggregationService service;

    @BeforeEach
    void setUp() {
        service = new InboxAggregationService(List.of(adapter), new InboxPriorityNormalizer(),
                states, links, labels, suggestions);
    }

    @Test
    @DisplayName("summaryはカード数を維持しラベルと提案を一切読まない")
    void summarySkipsDisplayOnlyReads() {
        given(adapter.fetch(eq(USER_ID), anyInt())).willReturn(List.of(item(1L)));
        var summary = service.getSummary(USER_ID);
        assertThat(summary.byState()).containsEntry("UNREAD", 1L);
        assertThat(summary.byPriority()).containsEntry("NORMAL", 1L);
        assertThat(summary.bySourceType()).containsEntry("ANNOUNCEMENT", 1L);
        verifyNoInteractions(links, labels, suggestions);
    }

    @ParameterizedTest
    @ValueSource(ints = {999, 1000, 1001})
    @DisplayName("複数スコープ分の500件超も維持し、distinctな出現IDだけ1000件ずつ読む")
    void statesAreBoundedWithoutTruncatingSummary(int count) {
        List<InboxItemDto> raw = new ArrayList<>(LongStream.rangeClosed(1, count)
                .mapToObj(this::item).toList());
        raw.add(raw.get(0)); // 同一通知が重複してもIN句のIDを増やさない。
        given(adapter.fetch(USER_ID, 500)).willReturn(raw);
        InboxItemStateEntity archived = overlay(InboxSourceType.ANNOUNCEMENT, (long) count);
        archived.setArchivedAt(LocalDateTime.now().minusDays(1));
        given(states.findByUserIdAndSourceTypeAndSourceIdIn(eq(USER_ID),
                eq(InboxSourceType.ANNOUNCEMENT), any())).willAnswer(invocation -> {
                    Collection<Long> ids = invocation.getArgument(2);
                    return ids.contains((long) count) ? List.of(archived) : List.of();
                });

        var summary = service.getSummary(USER_ID);

        assertThat(summary.byState()).containsEntry("UNREAD", (long) count - 1)
                .containsEntry("ARCHIVED", 1L);
        assertThat(summary.bySourceType()).containsEntry("ANNOUNCEMENT", (long) count);
        verify(states, times((count + 999) / 1000)).findByUserIdAndSourceTypeAndSourceIdIn(
                eq(USER_ID), eq(InboxSourceType.ANNOUNCEMENT), idsCaptor.capture());
        assertThat(idsCaptor.getAllValues()).allSatisfy(ids -> assertThat(ids).hasSizeLessThanOrEqualTo(1000));
        assertThat(idsCaptor.getAllValues().stream().flatMap(Collection::stream).toList())
                .containsExactlyElementsOf(LongStream.rangeClosed(1, count).boxed().toList());
        verifyNoInteractions(links, labels, suggestions);
    }

    @Test
    @DisplayName("同じ数値IDでもソース種別ごとに状態を分離する")
    void sameNumericIdKeepsSourceNamespace() {
        given(adapter.fetch(USER_ID, 500)).willReturn(List.of(item(1L),
                item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.URGENT, "NOTIFICATION:1", InboxState.READ)));
        InboxItemStateEntity archived = overlay(InboxSourceType.ANNOUNCEMENT, 1L);
        archived.setArchivedAt(LocalDateTime.now().minusDays(1));
        given(states.findByUserIdAndSourceTypeAndSourceIdIn(USER_ID,
                InboxSourceType.ANNOUNCEMENT, List.of(1L))).willReturn(List.of(archived));

        var summary = service.getSummary(USER_ID);

        assertThat(summary.byState()).containsEntry("ARCHIVED", 1L).containsEntry("READ", 1L);
        verify(states).findByUserIdAndSourceTypeAndSourceIdIn(USER_ID, InboxSourceType.NOTIFICATION, List.of(1L));
    }

    @Test
    @DisplayName("名寄せの代表・未処理優先とスヌーズ復帰をsummaryでも維持する")
    void summaryKeepsFoldAndTriageRules() {
        given(adapter.fetch(USER_ID, 500)).willReturn(List.of(
                item(InboxSourceType.NOTIFICATION, 1L, InboxPriority.URGENT, "SHARED:1", InboxState.READ),
                item(InboxSourceType.ANNOUNCEMENT, 2L, InboxPriority.NORMAL, "SHARED:1", InboxState.UNREAD),
                item(3L), item(4L)));
        InboxItemStateEntity archived = overlay(InboxSourceType.ANNOUNCEMENT, 2L);
        archived.setArchivedAt(LocalDateTime.now().minusDays(1));
        InboxItemStateEntity future = overlay(InboxSourceType.ANNOUNCEMENT, 3L);
        future.setSnoozedUntil(LocalDateTime.now().plusDays(1));
        InboxItemStateEntity expired = overlay(InboxSourceType.ANNOUNCEMENT, 4L);
        expired.setSnoozedUntil(LocalDateTime.now().minusDays(1));
        given(states.findByUserIdAndSourceTypeAndSourceIdIn(eq(USER_ID),
                eq(InboxSourceType.ANNOUNCEMENT), any())).willReturn(List.of(archived, future, expired));

        var summary = service.getSummary(USER_ID);

        assertThat(summary.byState()).containsOnlyKeys("READ", "SNOOZED", "UNREAD")
                .containsEntry("READ", 1L).containsEntry("SNOOZED", 1L).containsEntry("UNREAD", 1L);
        assertThat(summary.byPriority()).containsEntry("URGENT", 1L).containsEntry("NORMAL", 2L);
        assertThat(summary.bySourceType()).containsEntry("NOTIFICATION", 1L).containsEntry("ANNOUNCEMENT", 2L);
    }

    @Test
    @DisplayName("空結果ではoverlayもラベルも問い合わせない")
    void emptySkipsAllOverlays() {
        assertThat(service.getSummary(USER_ID).byState()).isEmpty();
        verifyNoInteractions(states, links, labels, suggestions);
    }

    @Test
    @DisplayName("状態取得の途中失敗を部分的な成功に変えない")
    void stateReadFailurePropagates() {
        given(adapter.fetch(USER_ID, 500)).willReturn(LongStream.rangeClosed(1, 1001)
                .mapToObj(this::item).toList());
        given(states.findByUserIdAndSourceTypeAndSourceIdIn(any(), any(), any()))
                .willAnswer(invocation -> {
                    Collection<Long> ids = invocation.getArgument(2);
                    if (ids.contains(1001L)) {
                        throw new IllegalStateException("state read failed");
                    }
                    return List.of();
                });
        assertThatThrownBy(() -> service.getSummary(USER_ID)).isInstanceOf(IllegalStateException.class)
                .hasMessage("state read failed");
        verify(states, times(2)).findByUserIdAndSourceTypeAndSourceIdIn(any(), any(), any());
        verifyNoInteractions(links, labels, suggestions);
    }

    private InboxItemStateEntity overlay(InboxSourceType type, Long id) {
        InboxItemStateEntity row = new InboxItemStateEntity();
        row.setUserId(USER_ID);
        row.setSourceType(type);
        row.setSourceId(id);
        return row;
    }

    private InboxItemDto item(Long id) {
        String ref = "ANNOUNCEMENT:" + id;
        return item(InboxSourceType.ANNOUNCEMENT, id, InboxPriority.NORMAL, ref, InboxState.UNREAD);
    }

    private InboxItemDto item(InboxSourceType type, Long id, InboxPriority priority, String ref, InboxState state) {
        return new InboxItemDto(type + ":" + id, type, id, "title", "excerpt",
                priority, null, "/x", LocalDateTime.now(), state,
                null, List.of(), ref, 1, List.of(new InboxItemRef(type, id)));
    }
}
