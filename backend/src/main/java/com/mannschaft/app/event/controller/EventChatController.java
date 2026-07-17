package com.mannschaft.app.event.controller;

import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.ChannelResponse;
import com.mannschaft.app.chat.service.EventChatChannelService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.event.service.EventScopeAccessGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * イベント専用チャットチャンネルコントローラー。
 *
 * <p>イベントに紐付いた専用チャットチャンネルを取得するAPIを提供する。
 * チャンネルはイベント作成時に自動生成される（{@link com.mannschaft.app.event.listener.EventChatChannelListener}）。</p>
 *
 * <p><b>認可（認可根治 Wave3-B12event）:</b> 本 EP は eventId のみを path に持つフラットな
 * サブリソースであり、認可が一切敷設されていなかったため任意イベントの channelId・チャンネル名
 * （イベントタイトル由来）・teamId/orgId が非メンバーにも漏洩していた（IDOR）。
 * チャットメッセージ本体は chat ドメインの {@code ChatChannelController} 側で別途
 * メンバーシップ検証されるが、本 EP 自体がスコープ帰属情報を漏らすため
 * {@link EventScopeAccessGuard#requireMemberByEventId} で当該イベントスコープのメンバーのみに限定する。</p>
 */
@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "イベントチャット", description = "F03.8 イベント専用チャットチャンネル取得")
@RequiredArgsConstructor
public class EventChatController {

    private final EventChatChannelService eventChatChannelService;
    private final ChatMapper chatMapper;
    private final EventScopeAccessGuard eventScopeAccessGuard;

    /**
     * イベントに紐付いたチャットチャンネルを取得する。
     *
     * <p>イベント作成時に自動生成されたチャンネルを返す。
     * チャンネルが存在しない場合は 404 を返す。</p>
     *
     * @param eventId イベントID
     * @return チャンネルレスポンス
     */
    @GetMapping("/{eventId}/channel")
    @Operation(summary = "イベント専用チャンネル取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "チャンネル未存在")
    public ResponseEntity<ApiResponse<ChannelResponse>> getEventChannel(
            @PathVariable Long eventId) {
        eventScopeAccessGuard.requireMemberByEventId(SecurityUtils.getCurrentUserId(), eventId);
        return eventChatChannelService.findByEventId(eventId)
                .map(channel -> ResponseEntity.ok(ApiResponse.of(chatMapper.toChannelResponse(channel))))
                .orElse(ResponseEntity.notFound().build());
    }
}
