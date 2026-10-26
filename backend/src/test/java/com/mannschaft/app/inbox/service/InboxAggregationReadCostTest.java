package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.dto.InboxItemRef;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import com.mannschaft.app.inbox.repository.InboxLabelLinkRepository;
import com.mannschaft.app.inbox.repository.NotificationLabelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/** 件数サマリが表示用ラベルを読まないことを固定する性能回帰テスト。 */
@ExtendWith(MockitoExtension.class)
class InboxAggregationReadCostTest {
    private static final Long USER_ID = 31L;

    @Mock private InboxSourceAdapter adapter;
    @Mock private InboxItemStateRepository states;
    @Mock private InboxLabelLinkRepository links;
    @Mock private NotificationLabelRepository labels;
    @Mock private InboxLabelSuggestionRules suggestions;

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

    private InboxItemDto item(Long id) {
        String ref = "ANNOUNCEMENT:" + id;
        return new InboxItemDto(ref, InboxSourceType.ANNOUNCEMENT, id, "title", "excerpt",
                InboxPriority.NORMAL, null, "/x", LocalDateTime.now(), InboxState.UNREAD,
                null, List.of(), ref, 1, List.of(new InboxItemRef(InboxSourceType.ANNOUNCEMENT, id)));
    }
}
