package com.mannschaft.app.family.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.family.dto.CareLinkResponse;
import com.mannschaft.app.family.service.CareLinkService;
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
 * ケアリンク招待コントローラー（招待トークン経由の確認・承認・拒否）。F03.12。
 *
 * <p>招待リンクから遷移して招待内容を確認し、承認または拒否する導線を提供する。</p>
 *
 * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 本コントローラーの 3 EP は二段で認可する。</p>
 * <ol>
 *   <li><b>capability トークン</b> — 招待リンクに埋め込まれた推測不能なトークンで対象リンクを
 *       特定する。不一致トークンは {@code FAMILY_029}（404）で存在を秘匿する。</li>
 *   <li><b>当事者本人の照合</b> — ケアリンクは続柄・ケア区分を含む後見系の機微情報であり、
 *       かつ<b>双方の同意で成立させる</b>。参照は当該リンクの当事者（ケア対象者本人または
 *       見守り者）に、承認・拒否は<b>招待を受けた側</b>に限定し、それ以外は
 *       {@code FAMILY_030}（403）で拒否する（{@code CareLinkService} の
 *       {@code requireParty} / {@code requireInvitee}。判定は entity 由来の当事者 ID で行う）。</li>
 * </ol>
 *
 * <p>本パスは {@code SecurityConfig} の {@code permitAll()} 対象ではなく認証必須のため、
 * 当事者の同一性判定には {@code SecurityUtils.getCurrentUserId()} を用いる（未認証は 401）。
 * 設計書 §5.1 の「未登録ユーザーのアカウント作成と同時受諾」は未実装であり、実装する場合も
 * アカウント作成で当事者を確定させたうえで本 EP を通す。契約は
 * {@code CareLinkInvitationScopeContractIT} で固定する。</p>
 */
@AuthorizedInService
@RestController
@RequestMapping("/api/v1/care-links/invitations")
@Tag(name = "ケアリンク（招待）", description = "F03.12 招待トークンを使ったケアリンク承認/拒否")
@RequiredArgsConstructor
public class PublicCareLinkController {

    private final CareLinkService careLinkService;

    /**
     * 招待トークンからケアリンク情報を取得する（確認画面表示用）。
     */
    @GetMapping("/{token}")
    @Operation(summary = "招待トークン情報取得")
    public ResponseEntity<ApiResponse<CareLinkResponse>> getInvitationByToken(
            @PathVariable String token) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                careLinkService.getByInvitationToken(token, currentUserId)));
    }

    /**
     * 招待を承認してケアリンクをアクティブにする。
     */
    @PostMapping("/{token}/accept")
    @Operation(summary = "招待承認")
    public ResponseEntity<ApiResponse<CareLinkResponse>> acceptInvitation(
            @PathVariable String token) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                careLinkService.acceptInvitation(token, currentUserId)));
    }

    /**
     * 招待を拒否する。
     */
    @PostMapping("/{token}/reject")
    @Operation(summary = "招待拒否")
    public ResponseEntity<Void> rejectInvitation(@PathVariable String token) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        careLinkService.rejectInvitation(token, currentUserId);
        return ResponseEntity.noContent().build();
    }
}
