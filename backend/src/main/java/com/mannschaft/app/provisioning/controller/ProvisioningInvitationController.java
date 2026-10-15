package com.mannschaft.app.provisioning.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.provisioning.dto.ProvisioningInvitationAcceptRequest;
import com.mannschaft.app.provisioning.dto.ProvisioningInvitationAcceptResponse;
import com.mannschaft.app.provisioning.dto.ProvisioningInvitationPreviewResponse;
import com.mannschaft.app.provisioning.service.ProvisioningAcceptanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 柱②-2: 販促プロビジョニング招待の下見/承諾 API（承諾者側・要ログイン）。
 *
 * <p>{@code /api/v1/provisioning/**} は {@code SecurityConfig} の既定
 * {@code .anyRequest().authenticated()} により未ログインは 401 で弾かれる
 * （個別 requestMatcher の追加は不要）。</p>
 *
 * <p>トークンは URL パスへは載せず、POST ボディで受け取る（AC 仕様）。</p>
 */
@RestController
@RequestMapping("/api/v1/provisioning/invitations")
@Tag(name = "販促プロビジョニング招待（承諾者側）")
@RequiredArgsConstructor
public class ProvisioningInvitationController {

    private final ProvisioningAcceptanceService acceptanceService;

    /**
     * 認可根治戦役 Wave5 監査済: 平文トークン（{@code SecretTokenVault} 発行の
     * capability トークン）自体が認可の根拠であり、
     * {@link ProvisioningAcceptanceService#preview} がハッシュ照合・PENDING/TTL 検証を行う
     * （AC1 で未認証は 401、トークン不一致/失効は PROV_001 で 404）。
     */
    @PostMapping("/preview")
    @Operation(summary = "招待トークンの下見（承諾前確認画面用）")
    @AuthorizedInService
    public ResponseEntity<ProvisioningInvitationPreviewResponse> preview(
            @Valid @RequestBody ProvisioningInvitationAcceptRequest request) {
        return ResponseEntity.ok(acceptanceService.preview(request.token()));
    }

    /**
     * 認可根治戦役 Wave5 監査済: 平文トークン（capability トークン）に加え、
     * {@link ProvisioningAcceptanceService#accept} がログインユーザーの検証済みメール
     * （{@code status=ACTIVE}）と招待先メールの一致（NFC正規化+lowercase）を AC4 として
     * 必須検証する。両方を満たさない限り 403/404 で拒否する。
     */
    @PostMapping("/accept")
    @Operation(summary = "招待トークンを承諾する（ADMIN役割+membership付与→スコープACTIVE化）")
    @AuthorizedInService
    public ResponseEntity<ProvisioningInvitationAcceptResponse> accept(
            @Valid @RequestBody ProvisioningInvitationAcceptRequest request) {
        return ResponseEntity.ok(acceptanceService.accept(request.token(), SecurityUtils.getCurrentUserId()));
    }
}
