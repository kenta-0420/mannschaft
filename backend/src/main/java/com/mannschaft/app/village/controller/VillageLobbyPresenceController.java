package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.dto.LobbyPresenceResponse;
import com.mannschaft.app.village.service.VillageLobbyPresenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.UUID;

/**
 * 村ロビー在席インジケーター Controller（F17.1 Phase 2）。
 *
 * <p>REST（初回ページロード用 GET）と STOMP（join/heartbeat/leave）の両方を担当する。
 * REST は {@code @ResponseBody} で JSON を返し、
 * STOMP は {@code /topic/villages/{villageId}/lobby/presence} にブロードキャストする。</p>
 */
@Controller
@Tag(name = "村ロビー在席 (F17.1 Phase 2)", description = "井戸端会議の在席インジケーター")
@RequiredArgsConstructor
@Slf4j
public class VillageLobbyPresenceController {

    private final VillageLobbyPresenceService presenceService;

    // ========== REST: 初回ページロード時の在席一覧取得 ==========

    /**
     * 認可は {@link VillageLobbyPresenceService#getPresence} 内で実施する。
     * 呼び出しユーザーが当該村の在籍かつ BAN 済みでないメンバーであることを検証し、
     * 満たさない場合は {@code NOT_MEMBER}（404）を返す。
     */
    @AuthorizedInService
    @GetMapping("/api/v1/villages/{villageId}/lobby/presence")
    @ResponseBody
    @Operation(summary = "村ロビーの現在の在席メンバー一覧を取得")
    public ApiResponse<LobbyPresenceResponse> getPresence(
            @PathVariable UUID villageId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(presenceService.getPresence(villageId, userId));
    }

    // ========== STOMP: /app/villages/{villageId}/lobby/presence/join ==========

    @MessageMapping("/villages/{villageId}/lobby/presence/join")
    public void join(@DestinationVariable UUID villageId, SimpMessageHeaderAccessor headerAccessor) {
        Long userId = getUserId(headerAccessor);
        if (userId == null) return;
        presenceService.join(villageId, userId);
    }

    // ========== STOMP: /app/villages/{villageId}/lobby/presence/heartbeat ==========

    @MessageMapping("/villages/{villageId}/lobby/presence/heartbeat")
    public void heartbeat(@DestinationVariable UUID villageId, SimpMessageHeaderAccessor headerAccessor) {
        Long userId = getUserId(headerAccessor);
        if (userId == null) return;
        presenceService.heartbeat(villageId, userId);
    }

    // ========== STOMP: /app/villages/{villageId}/lobby/presence/leave ==========

    @MessageMapping("/villages/{villageId}/lobby/presence/leave")
    public void leave(@DestinationVariable UUID villageId, SimpMessageHeaderAccessor headerAccessor) {
        Long userId = getUserId(headerAccessor);
        if (userId == null) return;
        presenceService.leave(villageId, userId);
    }

    private Long getUserId(SimpMessageHeaderAccessor headerAccessor) {
        Map<String, Object> attrs = headerAccessor.getSessionAttributes();
        if (attrs == null) return null;
        Object userId = attrs.get("userId");
        return userId instanceof Long l ? l : null;
    }
}
