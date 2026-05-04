package com.mannschaft.app.corkboard.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("CorkboardEventListener 単体テスト")
class CorkboardEventListenerTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @InjectMocks private CorkboardEventListener listener;

    @Test
    @DisplayName("CARD_CREATED イベントは /topic/corkboard/{boardId} に配信")
    void cardCreated配信先確認() {
        CorkboardEvent event = CorkboardEvent.card(42L, CorkboardEvent.Type.CARD_CREATED, 100L);

        listener.onCorkboardEvent(event);

        ArgumentCaptor<String> dest = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(dest.capture(), payload.capture());
        assertThat(dest.getValue()).isEqualTo("/topic/corkboard/42");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) payload.getValue();
        assertThat(map).containsEntry("eventType", "CARD_CREATED")
                .containsEntry("boardId", 42L)
                .containsEntry("cardId", 100L);
    }

    @Test
    @DisplayName("BOARD_DELETED は cardId/sectionId を含まない")
    void boardDeletedペイロード最小() {
        listener.onCorkboardEvent(CorkboardEvent.boardDeleted(7L));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/corkboard/7"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) payload.getValue();
        assertThat(map).doesNotContainKeys("cardId", "sectionId");
    }

    @Test
    @DisplayName("CARD_SECTION_CHANGED は cardId と sectionId を含む")
    void cardSectionペイロード() {
        listener.onCorkboardEvent(CorkboardEvent.cardSection(1L, 2L, 3L));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/corkboard/1"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) payload.getValue();
        assertThat(map).containsEntry("cardId", 2L)
                .containsEntry("sectionId", 3L);
    }

    @Test
    @DisplayName("boardId が null のイベントは配信しない")
    void boardIdNullスキップ() {
        listener.onCorkboardEvent(new CorkboardEvent(null, CorkboardEvent.Type.CARD_CREATED, 1L, null));
        verify(messagingTemplate, never()).convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object>any());
    }

    /** ArgumentMatchers.eq の短縮ラッパ。 */
    private static String eq(String s) {
        return org.mockito.ArgumentMatchers.eq(s);
    }
}
