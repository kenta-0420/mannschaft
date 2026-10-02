package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.AdvertiserAccountResponse;
import com.mannschaft.app.advertising.dto.RegisterAdvertiserRequest;
import com.mannschaft.app.advertising.service.AdvertiserAccountService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.domain.ScopeType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * F09.17 チームスコープ 広告主アカウント登録コントローラー。
 *
 * <p>チーム単位の広告主アカウントを新規登録するエンドポイントを提供する。
 * 組織版（{@code POST /api/v1/advertiser/register?organizationId=xxx}）と対称であり、
 * チーム ADMIN 以上のユーザーのみ利用可能。</p>
 *
 * <p>権限チェックは {@link AccessControlService#checkAdminOrAbove(Long, Long, String)} で
 * チームの ADMIN 以上を要求し、Service 層の {@code scope_type=TEAM / scope_id=teamId} で
 * 二段防御する。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/advertiser")
@RequiredArgsConstructor
public class TeamAdvertiserAccountController {

    private final AdvertiserAccountService advertiserAccountService;
    private final AccessControlService accessControlService;

    /**
     * チームスコープの権限検証。指定チームの ADMIN 以上であることを確認する。
     */
    private void verifyTeamAccess(Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, ScopeType.TEAM.name());
    }

    /**
     * チーム広告主アカウントを新規登録する。
     *
     * <p>同一チームで既に広告主アカウントが存在する場合は {@code AD_006} エラーを返す。
     * 登録後は SYSTEM_ADMIN による審査（{@code /system-admin/advertiser-accounts/{id}/approve}）
     * を経てアクティブになる。</p>
     *
     * <p>決済方式はクレジットカード（Stripe）一本。後払い（請求書方式）は F08.12 §5.0 により
     * 廃止済みで、{@code billingMethod=INVOICE} を指定した登録は {@code AD_036} で拒否される。
     * 省略時は既定値 {@code STRIPE} で作成される。</p>
     *
     * @param teamId  チーム ID（パスパラメータ）
     * @param request 登録リクエスト（companyName / contactEmail / billingMethod。省略可・INVOICE指定は拒否）
     * @return 作成された広告主アカウント情報（status = PENDING）
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdvertiserAccountResponse> register(
            @PathVariable Long teamId,
            @Valid @RequestBody RegisterAdvertiserRequest request) {
        verifyTeamAccess(teamId);
        return ApiResponse.of(
                advertiserAccountService.register(ScopeType.TEAM, teamId, request));
    }
}
