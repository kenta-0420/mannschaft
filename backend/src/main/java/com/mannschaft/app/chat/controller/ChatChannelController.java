package com.mannschaft.app.chat.controller;

import com.mannschaft.app.chat.dto.ActiveThreadItemResponse;
import com.mannschaft.app.chat.dto.AddMemberRequest;
import com.mannschaft.app.chat.dto.ChangeRoleRequest;
import com.mannschaft.app.chat.dto.ChannelIconUploadUrlRequest;
import com.mannschaft.app.chat.dto.ChannelResponse;
import com.mannschaft.app.chat.dto.ChannelSettingsRequest;
import com.mannschaft.app.chat.dto.CreateChannelRequest;
import com.mannschaft.app.chat.dto.InviteToZimmerRequest;
import com.mannschaft.app.chat.dto.MemberResponse;
import com.mannschaft.app.chat.dto.StartConversationRequest;
import com.mannschaft.app.chat.dto.UpdateChannelRequest;
import com.mannschaft.app.chat.dto.UpdateInquiryChannelRequest;
import com.mannschaft.app.chat.dto.UpdateMyChannelSettingsRequest;
import com.mannschaft.app.chat.dto.UploadUrlResponse;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.service.ChatAttachmentService;
import com.mannschaft.app.chat.service.ChatChannelService.ConversationResult;
import com.mannschaft.app.chat.service.ChatChannelService;
import com.mannschaft.app.chat.service.ChatMemberService;
import com.mannschaft.app.chat.service.ChatMessageService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.storage.PresignedUploadResult;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;

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
    private final ChatMessageService messageService;
    private final ChatAttachmentService attachmentService;


    /**
     * 自分が参加しているチャンネル一覧を取得する。
     */
    @SelfScopedEndpoint("検索条件が SecurityUtils.getCurrentUserId() のみで、リクエストは他ユーザーの識別子を受け取らない"
            + "（ChatChannelService#listMyChannels の findByMemberUserId が認証主体の参加チャンネルに束縛される）")
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
        ChannelResponse response = channelService.getChannel(channelId, SecurityUtils.getCurrentUserId());
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
        ChannelResponse response = channelService.updateChannel(channelId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チャンネルを削除する。
     */
    @DeleteMapping("/{channelId}")
    @Operation(summary = "チャンネル削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteChannel(@PathVariable Long channelId) {
        channelService.deleteChannel(channelId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * チャンネルをアーカイブする。
     */
    @PostMapping("/{channelId}/archive")
    @Operation(summary = "チャンネルアーカイブ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ成功")
    public ResponseEntity<ApiResponse<ChannelResponse>> archiveChannel(@PathVariable Long channelId) {
        ChannelResponse response = channelService.archiveChannel(channelId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チャンネルのアーカイブを解除する。
     */
    @DeleteMapping("/{channelId}/archive")
    @Operation(summary = "チャンネルアーカイブ解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ解除成功")
    public ResponseEntity<ApiResponse<ChannelResponse>> unarchiveChannel(@PathVariable Long channelId) {
        ChannelResponse response = channelService.unarchiveChannel(channelId, SecurityUtils.getCurrentUserId());
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
        List<MemberResponse> responses = memberService.addMembers(channelId, SecurityUtils.getCurrentUserId(), request);
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
        memberService.removeMember(channelId, userId, SecurityUtils.getCurrentUserId());
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
        MemberResponse response = memberService.changeRole(channelId, userId, request, SecurityUtils.getCurrentUserId());
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
        ChannelResponse response = channelService.convertToGroup(channelId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チャンネルのアクティブスレッド一覧を取得する（reply_count > 0 のトップレベルメッセージ）。
     */
    @GetMapping("/{channelId}/threads")
    @Operation(summary = "アクティブスレッド一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<CursorPagedResponse<ActiveThreadItemResponse>> getActiveThreads(
            @PathVariable Long channelId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        CursorPagedResponse<ActiveThreadItemResponse> response =
                messageService.getActiveThreads(channelId, SecurityUtils.getCurrentUserId(), cursor, limit);
        return ResponseEntity.ok(response);
    }

    /**
     * チャンネルの個人設定を更新する。
     */
    @SelfScopedEndpoint("更新対象は (channelId, SecurityUtils.getCurrentUserId()) のメンバー行に限定され、"
            + "対象ユーザー ID をリクエストで指定する余地が無い"
            + "（ChatMemberService#updateSettings の findByChannelIdAndUserId が認証主体に束縛される）")
    @PatchMapping("/{channelId}/settings")
    @Operation(summary = "チャンネル個人設定")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<MemberResponse>> updateSettings(
            @PathVariable Long channelId,
            @Valid @RequestBody ChannelSettingsRequest request) {
        MemberResponse response = memberService.updateSettings(channelId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * F04.2 Phase 11 第二陣 2-β: 自分のチャンネル個人設定を更新する。
     *
     * <p>{@code /settings}（チャンネル全体の管理者向け設定）とは別リソース。
     * 「自分の通知ミュート / ピン留め / カテゴリ」のメンバー個人設定のみを更新する。</p>
     *
     * <p>認可: チャンネルメンバーであること（自分自身のみ）。</p>
     */
    @SelfScopedEndpoint("更新対象は (channelId, SecurityUtils.getCurrentUserId()) のメンバー行に限定され、"
            + "対象ユーザー ID をリクエストで指定する余地が無い"
            + "（ChatMemberService#updateMySettings の findByChannelIdAndUserId が認証主体に束縛される）")
    @PatchMapping("/{channelId}/members/me")
    @Operation(summary = "自分のチャンネル個人設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<MemberResponse>> updateMySettings(
            @PathVariable Long channelId,
            @Valid @RequestBody UpdateMyChannelSettingsRequest request) {
        MemberResponse response = memberService.updateMySettings(
                channelId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * F10.7: 問い合わせチャンネル設定を更新する。
     *
     * <p>チームの ADMIN のみ操作可能。</p>
     *
     * <ul>
     *   <li>チームチャンネル（{@code channelType=TEAM}）のみ設定可能</li>
     *   <li>アーカイブ済みのチャンネルへの設定変更は不可</li>
     *   <li>{@code is_inquiry_channel=true} にする場合、同チームに既に問い合わせチャンネルがあれば 409 Conflict</li>
     * </ul>
     *
     * <p><b>認可（認可根治 Phase 3-b / 2026-05-30）:</b> 旧 {@code @PreAuthorize("hasRole('ADMIN')")} は
     * {@code @EnableMethodSecurity} 点火時に JWT へ ROLE_ADMIN が乗らず一斉 403 となるため是正した。
     * scope は <b>パス変数でなくチャンネルエンティティ由来</b>（{@code channelId} から取得した teamId）であり
     * SpEL でパス変数参照できないため、宣言は {@code isAuthenticated()} とし、真の per-scope 認可は
     * {@code ChatChannelService.updateInquiryChannel} 内で {@code AccessControlService} により強制する
     *（当該チャンネルのチーム ADMIN/DEPUTY_ADMIN、または SYSTEM_ADMIN 短絡）。</p>
     */
    @PatchMapping("/{channelId}/inquiry")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "問い合わせチャンネル設定更新（F10.7）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "チームチャンネル以外 / アーカイブ済み")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "同チームに問い合わせチャンネルが既に存在する")
    public ResponseEntity<ApiResponse<ChannelResponse>> updateInquiryChannel(
            @PathVariable Long channelId,
            @Valid @RequestBody UpdateInquiryChannelRequest request) {
        ChannelResponse response = channelService.updateInquiryChannel(
                channelId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * F04.2 Phase 11 第二陣 2-β: チャンネルアイコン用 Pre-signed URL を発行する。
     *
     * <p>メッセージ添付用 {@code /files/upload-url} とは別経路。MIME は画像のみ（JPEG/PNG/WebP）、
     * サイズ上限は 2MB の専用制約を持つ。発行された URL は 5 分間有効で、フロントエンドは
     * 取得した {@code fileKey} を {@code PATCH /chat/channels/{id}} の {@code icon_key} に
     * 設定することでチャンネルアイコンを更新する。</p>
     *
     * <p>認可: チャンネル OWNER / ADMIN のみ。</p>
     */
    @PostMapping("/{channelId}/icon/upload-url")
    @Operation(summary = "チャンネルアイコン Pre-signed URL 発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<UploadUrlResponse>> generateIconUploadUrl(
            @PathVariable Long channelId,
            @Valid @RequestBody ChannelIconUploadUrlRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ChatChannelEntity channel = channelService.findChannelOrThrow(channelId);
        PresignedUploadResult result = attachmentService.presignChannelIconUpload(
                channel, currentUserId,
                request.getContentType(),
                request.getFileSize() != null ? request.getFileSize() : 0L,
                request.getFileName());
        UploadUrlResponse response = new UploadUrlResponse(
                result.uploadUrl(),
                result.s3Key(),
                result.expiresInSeconds()
        );
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
