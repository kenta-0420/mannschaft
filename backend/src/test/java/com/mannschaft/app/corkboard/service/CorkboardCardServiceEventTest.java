package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.corkboard.CorkboardMapper;
import com.mannschaft.app.corkboard.dto.CorkboardCardResponse;
import com.mannschaft.app.corkboard.dto.CreateCardRequest;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.event.CorkboardEvent;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F09.8 Phase A-3: CorkboardCardService のイベント発行を検証する。
 * 共有ボードでのみ発行、個人ボードでは発行しないことを確認する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CorkboardCardService イベント発行テスト")
class CorkboardCardServiceEventTest {

    @Mock private CorkboardCardRepository cardRepository;
    @Mock private CorkboardService corkboardService;
    @Mock private CorkboardMapper corkboardMapper;
    @Mock private CorkboardPermissionService permissionService;
    @Mock private OgpFetchService ogpFetchService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private CorkboardCardService service;

    private CorkboardEntity teamBoard() {
        return CorkboardEntity.builder()
                .scopeType("TEAM").scopeId(10L).editPolicy("ALL_MEMBERS").name("チームボード").build();
    }

    private CorkboardEntity personalBoard() {
        return CorkboardEntity.builder()
                .scopeType("PERSONAL").ownerId(1L).name("個人ボード").build();
    }

    private CorkboardCardResponse stubResponse() {
        return new CorkboardCardResponse(
                1L, 1L, null, "MEMO", null, null, null, "t", null, null, null, null, null,
                "NONE", "MEDIUM", 0, 0, 0, null, null, null, false, false, null, false, 1L, null, null);
    }

    @Test
    @DisplayName("共有ボード（TEAM）: createCard で CARD_CREATED イベントが発行される")
    void 共有ボード_イベント発行() {
        given(corkboardService.findBoardOrThrow(1L)).willReturn(teamBoard());
        given(cardRepository.countByCorkboardId(1L)).willReturn(0L);
        given(cardRepository.save(any(CorkboardCardEntity.class)))
                .willAnswer(inv -> {
                    CorkboardCardEntity e = inv.getArgument(0);
                    return CorkboardCardEntity.builder()
                            .corkboardId(e.getCorkboardId()).cardType(e.getCardType())
                            .url(e.getUrl()).createdBy(e.getCreatedBy()).build();
                });
        given(corkboardMapper.toCardResponse(any())).willReturn(stubResponse());

        service.createCard(1L, 1L,
                new CreateCardRequest("MEMO", null, null, "t", null, null, null, null, null, null, null, null, null));

        ArgumentCaptor<CorkboardEvent> captor = ArgumentCaptor.forClass(CorkboardEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(CorkboardEvent.Type.CARD_CREATED);
        assertThat(captor.getValue().boardId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("個人ボード（PERSONAL）: createCard でイベントは発行されない")
    void 個人ボード_イベント未発行() {
        given(corkboardService.findBoardOrThrow(1L)).willReturn(personalBoard());
        given(cardRepository.countByCorkboardId(1L)).willReturn(0L);
        given(cardRepository.save(any(CorkboardCardEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(corkboardMapper.toCardResponse(any())).willReturn(stubResponse());

        service.createCard(1L, 1L,
                new CreateCardRequest("MEMO", null, null, "t", null, null, null, null, null, null, null, null, null));

        verify(eventPublisher, never()).publishEvent(any(CorkboardEvent.class));
    }
}
