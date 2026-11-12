package com.mannschaft.app.chat.service;

import com.mannschaft.app.role.dto.InviteCardData;
import com.mannschaft.app.role.service.MembershipInviteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 一覧プレビューの招待カード文言の確定（トランザクションの外・CMP-261105-1507）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatInviteCardPreviewRefresher")
class ChatInviteCardPreviewRefresherTest {

    private static final Long CHANNEL_ID = 1L;

    @Mock
    private ChatMessageService messageService;
    @Mock
    private MembershipInviteService membershipInviteService;
    @InjectMocks
    private ChatInviteCardPreviewRefresher refresher;

    @Test
    @DisplayName("最新が招待カードなら、招待先名入りの文言で確定する")
    void 招待先名入りで確定する() {
        given(messageService.findLatestInviteCardPreviewTarget(CHANNEL_ID))
                .willReturn(Optional.of(new ChatMessageService.InviteCardPreviewTarget(10L, 77L, 100L)));
        given(membershipInviteService.resolveInviteCardData(77L, 100L))
                .willReturn(new InviteCardData(77L, "TEAM", 5L, "北陽SC ジュニア", "PENDING", false, null));

        refresher.refresh(CHANNEL_ID);

        verify(messageService).applyInviteCardPreview(CHANNEL_ID, 10L, "北陽SC ジュニア への招待を送りました");
    }

    @Test
    @DisplayName("最新が招待カードでなければ、role を引かず何もしない")
    void 対象なしなら何もしない() {
        given(messageService.findLatestInviteCardPreviewTarget(CHANNEL_ID)).willReturn(Optional.empty());

        refresher.refresh(CHANNEL_ID);

        verify(membershipInviteService, never()).resolveInviteCardData(anyLong(), any());
        verify(messageService, never()).applyInviteCardPreview(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("招待先を解決できなければ、暫定文言のまま上書きしない")
    void 解決できなければ上書きしない() {
        given(messageService.findLatestInviteCardPreviewTarget(CHANNEL_ID))
                .willReturn(Optional.of(new ChatMessageService.InviteCardPreviewTarget(10L, 77L, 100L)));
        given(membershipInviteService.resolveInviteCardData(77L, 100L)).willReturn(null);

        refresher.refresh(CHANNEL_ID);

        verify(messageService, never()).applyInviteCardPreview(anyLong(), anyLong(), anyString());
    }
}
