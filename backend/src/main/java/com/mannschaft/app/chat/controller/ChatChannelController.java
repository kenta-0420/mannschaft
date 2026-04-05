package com.mannschaft.app.chat.controller;

import com.mannschaft.app.chat.dto.AddMemberRequest;
import com.mannschaft.app.chat.dto.ChangeRoleRequest;
import com.mannschaft.app.chat.dto.ChannelResponse;
import com.mannschaft.app.chat.dto.ChannelSettingsRequest;
import com.mannschaft.app.chat.dto.CreateChannelRequest;
import com.mannschaft.app.chat.dto.InviteToZimmerRequest;
import com.mannschaft.app.chat.dto.MemberResponse;
import com.mannschaft.app.chat.dto.StartConversationRequest;
import com.mannschaft.app.chat.dto.UpdateChannelRequest;
import com.mannschaft.app.chat.service.ChatChannelService.ConversationResult;
import com.mannschaft.app.chat.service.ChatChannelService;
import com.mannschaft.app.chat.service.ChatMemberService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * チャットチャンネルコントローラー。チャンネルのCRUD・メンバー管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/chat/channels")
@Tag(name = "チャットチャンネル", description = "F04.2 チャットチャンネル管理")
@RequiredArgsConstructor
public class ChatChannelController {

    private final ChatChannelService channelService;
    private final ChatMemberService memberService;


    /**
     * 自分が参加しているチャンネル一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "チャンネル一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ChannelResponse>>> listChannels() {
        List<ChannelResponse> channels = channelService.listMyChannels(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(channels));
    }

    /**
     * チャンネルを作成する。
     */
    @PostMapping
    @Operation(summary = "チャンネル作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ChannelResponse>> createChannel(
            @Valid @RequestBody CreateChannelRequest request) {
        ChannelResponse response = channelService.createChannel(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * チャンネル詳細を取得する。
     */
    @GetMapping("/{channelId}")
    @Operation(summary = "チャンネル詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ChannelResponse>> getChannel(@PathVariable Long channelId) {
        ChannelResponse response = channelService.getChannel(channelId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チャンネルを更新する。
     */
    @PatchMapping("/{channelId}")
    @Operation(summary = "チャンネル更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ChannelResponse>> updateChannel(
            @PathVariable Long channelId,
            @Valid @RequestBody UpdateChannelRequest request) {
        ChannelResponse response = channelService.updateChannel(channelId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チャンネルを削除する。
     */
    @DeleteMapping("/{channelId}")
    @Operation(summary = "チャンネル削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteChannel(@PathVariable Long channelId) {
        channelService.deleteChannel(channelId);
        return ResponseEntity.noContent().build();
    }

    /**
     * チャンネルをアーカイブする。
     */
    @PostMapping("/{channelId}/archive")
    @Operation(summary = "チャンネルアーカイブ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ成功")
    public ResponseEntity<ApiResponse<ChannelResponse>> archiveChannel(@PathVariable Long channelId) {
        ChannelResponse response = channelService.archiveChannel(channelId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チャンネルにメンバーを追加する。
     */
    @PostMapping("/{channelId}/members")
    @Operation(summary = "メンバー追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<List<MemberResponse>>> addMembers(
            @PathVariable Long channelId,
            @Valid @RequestBody AddMemberRequest request) {
        List<MemberResponse> responses = memberService.addMembers(channelId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(responses));
    }

    /**
     * チャンネルからメンバーを除外する。
     */
    @DeleteMapping("/{channelId}/members/{userId}")
    @Operation(summary = "メンバー除外")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "除外成功")
    public ResponseEntity<Void> removeMember(
            @PathVariable Long channelId,
            @PathVariable Long userId) {
        memberService.removeMember(channelId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * チャンネルに参加する。
     */
    @PostMapping("/{channelId}/join")
    @Operation(summary = "チャンネル参加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "参加成功")
    public ResponseEntity<ApiResponse<MemberResponse>> joinChannel(@PathVariable Long channelId) {
        MemberResponse response = memberService.joinChannel(channelId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * メンバーのロールを変更する。
     */
    @PatchMapping("/{channelId}/members/{userId}/role")
    @Operation(summary = "ロール変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<ApiResponse<MemberResponse>> changeRole(
            @PathVariable Long channelId,
            @PathVariable Long userId,
            @Valid @RequestBody ChangeRoleRequest request) {
        MemberResponse response = memberService.changeRole(channelId, userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 会話を開始する。参加者数に応じて Kabine（DM）/ Zimmer（GROUP_DM）を自動振り分け。
     * <ul>
     *   <li>1名 → Kabine: 既存DMがあれば200、なければ201</li>
     *   <li>2名以上 → Zimmer: 常に新規作成201</li>
     * </ul>
     */
    @PostMapping("/conversations")
    @Operation(summary = "会話開始（Kabine/Zimmer自動振り分け）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "既存チャンネル返却")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "新規チャンネル作成")
    public ResponseEntity<ApiResponse<ChannelResponse>> startConversation(
            @Valid @RequestBody StartConversationRequest request) {
        ConversationResult result = channelService.startConversation(
                SecurityUtils.getCurrentUserId(), request.getUserIds());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.of(result.channel()));
    }

    /**
     * KabineからZimmerへの招待。
     * 既存のKabine（DM）はそのまま残し、Kabineメンバー全員＋招待ユーザーで新Zimmer（GROUP_DM）を作成する。
     * shareHistory=true の場合、Kabineの会話履歴が新Zimmerに転送コピーされる。
     */
    @PostMapping("/{channelId}/invite-to-zimmer")
    @Operation(summary = "KabineからZimmerへの招待")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Zimmer作成成功")
    public ResponseEntity<ApiResponse<ChannelResponse>> inviteToZimmer(
            @PathVariable Long channelId,
            @Valid @RequestBody InviteToZimmerRequest request) {
        ChannelResponse response = channelService.inviteToZimmer(
                channelId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * DMチャンネルをグループDMに変換する。
     * 2者間DMをグループDMに拡張し、追加メンバーを招待可能にする。
     */
    @PostMapping("/{channelId}/convert-to-group")
    @Operation(summary = "DMをグループDMに変換")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変換成功")
    public ResponseEntity<ApiResponse<ChannelResponse>> convertToGroup(@PathVariable Long channelId) {
        ChannelResponse response = channelService.convertToGroup(channelId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チャンネルの個人設定を更新する。
     */
    @PatchMapping("/{channelId}/settings")
    @Operation(summary = "チャンネル個人設定")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<MemberResponse>> updateSettings(
            @PathVariable Long channelId,
            @Valid @RequestBody ChannelSettingsRequest request) {
        MemberResponse response = memberService.updateSettings(channelId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
