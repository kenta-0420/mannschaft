package com.mannschaft.app.corkboard.event;

import com.mannschaft.app.corkboard.dto.CorkboardCardResponse;
import com.mannschaft.app.corkboard.dto.CorkboardGroupResponse;
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
        listener.onCorkboardEvent(
                new CorkboardEvent(null, CorkboardEvent.Type.CARD_CREATED, 1L, null, null, null));
        verify(messagingTemplate, never()).convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object>any());
    }

    // ============================================================
    // 件B: card / section ペイロードを含めて配信されること
    // ============================================================

    @Test
    @DisplayName("件B: cardCreated の配信ペイロードに card DTO が含まれる")
    void cardCreatedにcardDTOが含まれる() {
        CorkboardCardResponse cardDto = stubCard(100L);
        listener.onCorkboardEvent(CorkboardEvent.cardCreated(42L, cardDto));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/corkboard/42"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) payload.getValue();
        assertThat(map).containsEntry("eventType", "CARD_CREATED")
                .containsEntry("boardId", 42L)
                .containsEntry("cardId", 100L)
                .containsEntry("card", cardDto);
        assertThat(map).doesNotContainKey("section");
    }

    @Test
    @DisplayName("件B: cardDeleted は card DTO を含まない（cardId のみ）")
    void cardDeletedはcardDTOを含まない() {
        listener.onCorkboardEvent(CorkboardEvent.cardDeleted(42L, 100L));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/corkboard/42"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) payload.getValue();
        assertThat(map).containsEntry("eventType", "CARD_DELETED")
                .containsEntry("cardId", 100L);
        assertThat(map).doesNotContainKeys("card", "section");
    }

    @Test
    @DisplayName("件B: sectionCreated の配信ペイロードに section DTO が含まれる")
    void sectionCreatedにsectionDTOが含まれる() {
        CorkboardGroupResponse sectionDto = stubSection(200L);
        listener.onCorkboardEvent(CorkboardEvent.sectionCreated(42L, sectionDto));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/corkboard/42"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) payload.getValue();
        assertThat(map).containsEntry("eventType", "SECTION_CREATED")
                .containsEntry("sectionId", 200L)
                .containsEntry("section", sectionDto);
        assertThat(map).doesNotContainKey("card");
    }

    @Test
    @DisplayName("件B: sectionDeleted は section DTO を含まない（sectionId のみ）")
    void sectionDeletedはsectionDTOを含まない() {
        listener.onCorkboardEvent(CorkboardEvent.sectionDeleted(42L, 200L));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/corkboard/42"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) payload.getValue();
        assertThat(map).containsEntry("eventType", "SECTION_DELETED")
                .containsEntry("sectionId", 200L);
        assertThat(map).doesNotContainKeys("card", "section");
    }

    @Test
    @DisplayName("件B: cardSectionChanged は card DTO を含む（sectionId は別フィールド）")
    void cardSectionChangedにcardDTOが含まれる() {
        CorkboardCardResponse cardDto = stubCard(100L);
        listener.onCorkboardEvent(CorkboardEvent.cardSectionChanged(42L, cardDto, 200L));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/corkboard/42"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) payload.getValue();
        assertThat(map).containsEntry("eventType", "CARD_SECTION_CHANGED")
                .containsEntry("cardId", 100L)
                .containsEntry("sectionId", 200L)
                .containsEntry("card", cardDto);
        assertThat(map).doesNotContainKey("section");
    }

    @Test
    @DisplayName("件B 互換: 旧ファクトリ CorkboardEvent.card(...) は card / section フィールドを含まない")
    void 旧ファクトリは新ペイロードを含まない() {
        listener.onCorkboardEvent(CorkboardEvent.card(42L, CorkboardEvent.Type.CARD_CREATED, 100L));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/corkboard/42"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) payload.getValue();
        assertThat(map).doesNotContainKeys("card", "section");
    }

    /** ArgumentMatchers.eq の短縮ラッパ。 */
    private static String eq(String s) {
        return org.mockito.ArgumentMatchers.eq(s);
    }

    private static CorkboardCardResponse stubCard(Long id) {
        return new CorkboardCardResponse(
                id, 42L, null, "MEMO", null, null, null, "title", null, null, null, null, null,
                "NONE", "MEDIUM", 0, 0, 0, null, null, null, false, false, null, false, 1L, null, null);
    }

    private static CorkboardGroupResponse stubSection(Long id) {
        return new CorkboardGroupResponse(
                id, 42L, "section name", false, 0, 0, 400, 300, (short) 0, null, null);
    }
}
