package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.dto.DailyThreadListResponse;
import com.mannschaft.app.village.dto.DailyThreadResponse;
import com.mannschaft.app.village.dto.LobbyChannelResponse;
import com.mannschaft.app.village.service.VillageLobbyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * F17.1 Phase 1 B9 — 村ロビー（井戸端会議）Controller。
 *
 * <p>担当 API（設計書 §4.10）:</p>
 * <ul>
 *   <li>{@code GET /api/v1/villages/{villageId}/lobby} — ロビーチャネル情報（自動払い出し含む）</li>
 *   <li>{@code GET /api/v1/villages/{villageId}/lobby/daily?days=7} — 直近 N 日の日次スレッド一覧</li>
 *   <li>{@code GET /api/v1/villages/{villageId}/lobby/daily/{date}} — 特定日のスレッド要約</li>
 * </ul>
 *
 * <p>メッセージ送信は既存 {@code /api/v1/chat/channels/{channelId}/messages} を使う（§4.10.4）。</p>
 *
 * <h2>認可</h2>
 * <p>全 EP の認可は {@link VillageLobbyService} 内で完結する。各メソッドは
 * {@code loadActiveVillage} で村の存在（削除・凍結を除外）を確認したのち、
 * {@code isUserVillageMember}（在籍かつ BAN 済みでないこと）を検証し、
 * 満たさない場合は {@code VILLAGE_007 NOT_MEMBER}（404）で村の存在ごと秘匿する。</p>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/lobby")
@Tag(name = "村ロビー (F17.1)", description = "Phase 1: 井戸端会議チャネル + 日次スレッド")
@RequiredArgsConstructor
@AuthorizedInService
public class VillageLobbyController {

    private final VillageLobbyService lobbyService;

    @GetMapping
    @Operation(summary = "村ロビーのチャネル情報を取得（未払い出しなら自動生成）")
    public ApiResponse<LobbyChannelResponse> getLobby(@PathVariable("villageId") UUID villageId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(lobbyService.getLobbyChannel(villageId, actorUserId));
    }

    @GetMapping("/daily")
    @Operation(summary = "村ロビーの日次スレッド一覧を取得（直近 N 日、デフォルト 7 日）")
    public ApiResponse<DailyThreadListResponse> listDaily(
            @PathVariable("villageId") UUID villageId,
            @RequestParam(name = "days", defaultValue = "7") int days) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(lobbyService.listDailyThreads(villageId, actorUserId, days));
    }

    @GetMapping("/daily/{date}")
    @Operation(summary = "特定日の村ロビー日次スレッドを取得")
    public ApiResponse<DailyThreadResponse> getDaily(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(lobbyService.getDailyThread(villageId, actorUserId, date));
    }
}
