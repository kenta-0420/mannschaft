package com.mannschaft.app.contact.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.contact.dto.ContactInvitePreviewResponse;
import com.mannschaft.app.contact.dto.SendContactRequestResponse;
import com.mannschaft.app.contact.service.ContactInviteTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 招待URL公開エンドポイントコントローラー。
 */
@RestController
@RequestMapping("/api/v1/contact-invite")
@Tag(name = "Contact Invite")
@RequiredArgsConstructor
public class ContactInviteController {

    private final ContactInviteTokenService contactInviteTokenService;

    /**
     * 招待プレビュー（認証不要）。
     * 情報最小化: 発行者の表示名・ハンドル・有効期限のみ返す。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 認可は capability トークン
     * （招待 URL に埋め込まれた発行者固有トークン）で実施する。
     * {@code ContactInviteTokenService.java:93-96}（{@code getPreview}）が
     * {@code tokenRepository.findByToken(token)} で照合し、トークン不在または
     * {@code ContactInviteTokenEntity.java:97-102} の {@code isValid()}
     * （無効化済み {@code revokedAt} / 期限切れ {@code expiresAt} / 利用回数超過 {@code maxUses}）
     * が false の場合は {@code isValid=false} のみを返し、<b>発行者情報を一切返さない</b>。
     * 有効なトークンの保持者にのみ発行者の氏名・ハンドル・有効期限を開示する
     * （情報最小化のため、それ以外のフィールドは返さない）。
     * 認可根治戦役 Wave5 監査済。</p>
     */
    @AuthorizedInService
    @GetMapping("/{token}")
    @Operation(summary = "招待プレビュー取得（認証不要）")
    public ResponseEntity<ApiResponse<ContactInvitePreviewResponse>> getPreview(
            @PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.of(contactInviteTokenService.getPreview(token)));
    }

    /**
     * 招待URLから連絡先追加（認証必須）。
     *
     * <p><b>認可</b>: 追加相手は<b>招待 URL に埋め込まれた capability トークンの発行者</b>に
     * 限定され、リクエストで任意のユーザーを指定する余地がない。自分側の登録先は
     * {@code SecurityUtils.getCurrentUserId()} で確定した認証主体のフォルダのみ
     * （{@code ContactInviteTokenService.java:113-160}）。トークン不在・無効化済み・期限切れ・
     * 利用回数超過は {@code CONTACT_012}、自分が発行したトークンは {@code CONTACT_013} で拒否し、
     * ブロック・事前拒否は設計書 §2.3 のサイレント方式（応答差を作らない）で扱う。
     * 契約は {@code ContactScopeContractIT} で固定する。認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedInService
    @PostMapping("/{token}/accept")
    @Operation(summary = "招待リンクで連絡先追加（認証必須）")
    public ResponseEntity<ApiResponse<SendContactRequestResponse>> acceptInvite(
            @PathVariable String token) {
        Long userId = SecurityUtils.getCurrentUserId();
        SendContactRequestResponse response = contactInviteTokenService.acceptInvite(userId, token);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
