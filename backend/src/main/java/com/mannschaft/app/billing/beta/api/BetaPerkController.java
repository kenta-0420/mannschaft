package com.mannschaft.app.billing.beta.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.beta.BetaGrantQueryService;
import com.mannschaft.app.billing.beta.dto.BetaGrantItem;
import com.mannschaft.app.billing.beta.dto.MyBetaPerksResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F20.3 ベータ特典: 利用者向け照会 API（本人・団体メンバー・設計書 02 §1）。
 *
 * <p><b>認可（03 §1・§2）</b>:</p>
 * <ul>
 *   <li>{@code GET /me/beta-perks} — 認証のみ・<b>scopeId をパスで受けず {@code getCurrentUserId()} 固定</b>
 *       （他人の grant/eligibility を返さない・IDOR 無効化・AC-A5）。</li>
 *   <li>{@code GET /teams/{teamId}/beta-perks} — 当該チームのメンバー以上
 *       （{@code @accessGuard.isScopeMember(authentication, #teamId, 'TEAM')}）。</li>
 *   <li>{@code GET /organizations/{orgId}/beta-perks} — 当該組織のメンバー以上
 *       （{@code 'ORGANIZATION'}）。</li>
 * </ul>
 *
 * <p>レスポンスは {@link BetaGrantItem}（審査系フィールドを含まない・03 §3・AC-A7）。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "ベータ特典", description = "F20.3 自分・団体のベータ特典照会")
@RequiredArgsConstructor
public class BetaPerkController {

    private final BetaGrantQueryService betaGrantQueryService;

    @GetMapping("/me/beta-perks")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "自分のベータ特典", description = "本人固定（scopeId を受けない）。eligibility は criteria 未定義時 null。")
    public ResponseEntity<ApiResponse<MyBetaPerksResponse>> getMyBetaPerks() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(betaGrantQueryService.getMyBetaPerks(userId)));
    }

    @GetMapping("/teams/{teamId}/beta-perks")
    @PreAuthorize("@accessGuard.isScopeMember(authentication, #teamId, 'TEAM')")
    @Operation(summary = "チームのベータ特典", description = "メンバー以上が閲覧可。審査系フィールドは返さない。")
    public ResponseEntity<ApiResponse<List<BetaGrantItem>>> getTeamBetaPerks(@PathVariable Long teamId) {
        return ResponseEntity.ok(ApiResponse.of(
                betaGrantQueryService.getScopeBetaPerks(EntitlementScopeKind.TEAM, teamId)));
    }

    @GetMapping("/organizations/{orgId}/beta-perks")
    @PreAuthorize("@accessGuard.isScopeMember(authentication, #orgId, 'ORGANIZATION')")
    @Operation(summary = "組織のベータ特典", description = "メンバー以上が閲覧可。審査系フィールドは返さない。")
    public ResponseEntity<ApiResponse<List<BetaGrantItem>>> getOrgBetaPerks(@PathVariable Long orgId) {
        return ResponseEntity.ok(ApiResponse.of(
                betaGrantQueryService.getScopeBetaPerks(EntitlementScopeKind.ORG, orgId)));
    }
}
