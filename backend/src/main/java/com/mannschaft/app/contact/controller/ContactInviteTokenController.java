package com.mannschaft.app.contact.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.contact.dto.ContactInviteTokenResponse;
import com.mannschaft.app.contact.dto.CreateInviteTokenBody;
import com.mannschaft.app.contact.service.ContactInviteTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 連絡先招待トークンコントローラー。
 *
 * <p><b>認可</b>: 発行・一覧は認証主体の ID をスコープとして渡すだけで、リクエストから
 * 他ユーザーを指定する余地がない（自己スコープ）。トークン ID / トークン文字列を受け取る
 * 無効化・QR 取得は、{@code ContactInviteTokenService} が<b>発行者 == 認証主体</b>を
 * 照合し、他ユーザーのトークンは 404 で存在を秘匿する。契約は
 * {@code ContactScopeContractIT} で固定する。</p>
 */
@RestController
@RequestMapping("/api/v1/contact-invite-tokens")
@Tag(name = "Contact Invite Tokens")
@RequiredArgsConstructor
public class ContactInviteTokenController {

    private final ContactInviteTokenService contactInviteTokenService;

    @PostMapping
    @Operation(summary = "招待トークンを発行する")
    public ResponseEntity<ApiResponse<ContactInviteTokenResponse>> createToken(
            @Valid @RequestBody CreateInviteTokenBody req) {
        Long userId = SecurityUtils.getCurrentUserId();
        ContactInviteTokenResponse response = contactInviteTokenService.createToken(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping
    @Operation(summary = "発行済みトークン一覧")
    public ResponseEntity<ApiResponse<List<ContactInviteTokenResponse>>> listTokens() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactInviteTokenService.listTokens(userId)));
    }

    /**
     * 招待トークンを無効化する。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: {@code ContactInviteTokenService.java:83}
     * が {@code findByIdAndUserId(tokenId, userId)} の複合フェッチで<b>発行者 == 認証主体</b>を
     * 照合し、他ユーザーのトークン ID は {@code CONTACT_014}（404）で存在を秘匿する
     * （認可判定は entity 由来の {@code userId} で行い、リクエスト値を信頼しない）。</p>
     */
    @AuthorizedInService
    @DeleteMapping("/{id}")
    @Operation(summary = "招待トークンを無効化する")
    public ResponseEntity<Void> revokeToken(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        contactInviteTokenService.revokeToken(userId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 招待トークンの QR コード画像（PNG）を取得する。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: {@code ContactInviteTokenService.java:169-171}
     * （{@code generateQrCode}）が {@code findByToken(token)} で取得した entity の
     * {@code userId} と認証主体を照合し、<b>発行者本人以外</b>は {@code CONTACT_014}（404）で
     * 存在を秘匿する。QR に載せる URL はサーバー側で組み立て、リクエスト値を URL に混ぜない。</p>
     */
    @AuthorizedInService
    @GetMapping("/{token}/qr")
    @Operation(summary = "QRコード画像を取得する（PNG）")
    public ResponseEntity<byte[]> getQrCode(
            @PathVariable String token,
            @RequestParam(defaultValue = "300") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        // sizeのクランプ（100〜1000）
        int safeSize = Math.max(100, Math.min(size, 1000));
        byte[] png = contactInviteTokenService.generateQrCode(userId, token, safeSize);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .body(png);
    }
}
