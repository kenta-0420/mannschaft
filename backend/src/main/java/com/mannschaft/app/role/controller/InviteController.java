package com.mannschaft.app.role.controller;

import com.mannschaft.app.role.service.InviteService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.role.dto.InviteJoinRequest;
import com.mannschaft.app.role.dto.InvitePreviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 招待リンクコントローラー。
 * 招待トークンのプレビュー（未認証可）・QRコード・参加エンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/invite")
@Tag(name = "招待")
@RequiredArgsConstructor
public class InviteController {

    private final InviteService inviteService;


    /**
     * 招待トークンをプレビューする（未認証可）。
     */
    @GetMapping("/{token}")
    @Operation(summary = "招待プレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<InvitePreviewResponse>> previewInvite(
            @PathVariable String token) {
        return ResponseEntity.ok(inviteService.previewInvite(token));
    }

    /**
     * 招待URLをエンコードした QR コード画像（PNG）を返す。未認証可。
     */
    @GetMapping(value = "/{token}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "招待QRコード取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "PNG画像")
    public ResponseEntity<byte[]> getInviteQrCode(
            @PathVariable String token,
            @RequestParam(required = false) Integer size) {
        byte[] png = inviteService.generateInviteQrCode(token, size);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    /**
     * 招待トークンを使用して参加する。
     *
     * <p>F15.3: リクエストボディに任意で {@code folderId} を含められる。
     * 未指定時はサーバー側で「未分類」フォルダへ自動配置される。
     * ボディ全体も任意（後方互換）。</p>
     */
    @PostMapping("/{token}/join")
    @Operation(summary = "招待による参加",
            description = "招待トークンで参加。ボディの folderId 指定時はそのフォルダへ、未指定時は未分類フォルダへ配置")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "参加成功")
    public ResponseEntity<Void> joinByInvite(
            @PathVariable String token,
            @RequestBody(required = false) InviteJoinRequest req) {
        Long folderId = req != null ? req.folderId() : null;
        inviteService.joinByInvite(token, SecurityUtils.getCurrentUserId(), folderId);
        return ResponseEntity.ok().build();
    }

    /**
     * 承諾型招待を辞退する（F04.12）。宛先本人のみ。
     *
     * <p>認可: 認証必須。宛先本人か否かの照合（IDOR 防止）は Service 層で行う
     * （宛先不一致は 403 ROLE_009。実装は /出陣）。</p>
     */
    @PostMapping("/{token}/decline")
    @Operation(summary = "承諾型招待の辞退",
            description = "宛先本人が招待を辞退する。revoked_at を立てカードを辞退済み表示にする（F04.12）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "辞退成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> declineInvite(@PathVariable String token) {
        inviteService.declineInvite(token, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok().build();
    }
}
