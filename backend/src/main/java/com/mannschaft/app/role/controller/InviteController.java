package com.mannschaft.app.role.controller;

import com.mannschaft.app.role.service.InviteService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.role.dto.InviteJoinRequest;
import com.mannschaft.app.role.dto.InvitePreviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
     * 招待トークンをプレビューする。
     *
     * <p>「未認証可」は招待UXの将来設計を示す旧コメント。実際は {@code /api/v1/invite/**} が
     * permitAll 未登録のため SecurityConfig.java:454 の {@code anyRequest().authenticated()}
     * で認証必須が現に強制されている。返却内容も招待トークン自体（bearer capability）に紐づく
     * 情報のみで、個人の非公開情報は含まない。</p>
     */
    @AuthorizedByPathConfig
    @GetMapping("/{token}")
    @Operation(summary = "招待プレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<InvitePreviewResponse>> previewInvite(
            @PathVariable String token) {
        return ResponseEntity.ok(inviteService.previewInvite(token));
    }

    /**
     * 招待URLをエンコードした QR コード画像（PNG）を返す。
     *
     * <p>「未認証可」は招待UXの将来設計を示す旧コメント。実際は {@code /api/v1/invite/**} が
     * permitAll 未登録のため SecurityConfig.java:454 の {@code anyRequest().authenticated()}
     * で認証必須が現に強制されている。</p>
     */
    @AuthorizedByPathConfig
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
    @SelfScopedEndpoint("InviteService#joinByInvite が参加者として "
            + "SecurityUtils.getCurrentUserId() のみを使用し、招待トークン自体が対象スコープを決める "
            + "capability であるため他人の識別子を指定する余地が無い")
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
}
