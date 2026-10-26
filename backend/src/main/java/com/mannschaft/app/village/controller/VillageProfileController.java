package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.dto.ProfileVisibilityResponse;
import com.mannschaft.app.village.dto.ProfileVisibilityUpdateRequest;
import com.mannschaft.app.village.dto.UserVillageSummaryResponse;
import com.mannschaft.app.village.service.VillageMembershipProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F17.2 機能⑥ — 村人ミニプロフィールの所属村一覧 Controller（設計書 §9）。
 *
 * <p>2 つの API を提供する（パスの基点が異なるためクラスレベル {@code @RequestMapping} は付けない）:</p>
 * <ul>
 *   <li>{@code PATCH /api/v1/villages/{villageId}/memberships/me/profile-visibility} — 本人の公開トグル切替</li>
 *   <li>{@code GET /api/v1/users/{userId}/villages} — 対象村人の所属村一覧（同居者限定・§9.4）</li>
 * </ul>
 *
 * <p>認可は Service 層で厳密に行う。未ログインは {@link SecurityUtils#getCurrentUserId()} が 401 を投げる。</p>
 */
@RestController
@Tag(name = "村人 所属村一覧 (F17.2)",
     description = "村人ミニプロフィールの所属村一覧と公開トグル（同居者限定・ニックネーム非返却）")
@RequiredArgsConstructor
// 認可番人(AuthzControllerGuardArchTest・Wave4)向けマーカー。認可の根拠:
// 認可は VillageMembershipProfileService が担う。① 公開トグル切替は当該村の現役メンバーシップを持つ本人のみ
// （無ければ NOT_MEMBER=404 秘匿）、② 所属村一覧は「閲覧者と対象者の現役同居」∩「対象者 profile_public=TRUE」∩
// 「村 visibility=PUBLIC」の二重フィルタで、返せる村が0件なら共通村の有無に関わらず一律 403（§9.4・同居関係を秘匿）。
// 未ログインは入口の SecurityUtils.getCurrentUserId() が 401 を投げる。データ依存（同居判定）の認可ゆえ
// @PreAuthorize では表現できないため、Service 層で認可済みであることを本マーカーで明示する。
@AuthorizedInService
public class VillageProfileController {

    private final VillageMembershipProfileService profileService;

    /**
     * 自分のその村所属の公開トグルを切り替える（本人のみ・§9.3）。
     */
    @PatchMapping("/api/v1/villages/{villageId}/memberships/me/profile-visibility")
    @Operation(summary = "自分の所属村一覧公開トグルを切り替える（本人のみ）")
    public ApiResponse<ProfileVisibilityResponse> updateMyProfileVisibility(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody ProfileVisibilityUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(
                profileService.updateMyProfileVisibility(villageId, userId, request.profilePublic()));
    }

    /**
     * 対象村人の所属村一覧を取得する（同居者限定・§9.4）。
     *
     * <p>返せる村が0件なら共通村の有無に関わらず一律 403（同居関係の有無を秘匿）。</p>
     */
    @GetMapping("/api/v1/users/{userId}/villages")
    @Operation(summary = "対象村人の所属村一覧を取得する（同居者限定・公開ON∩PUBLIC のみ・ニックネーム非返却）")
    public ApiResponse<List<UserVillageSummaryResponse>> getUserVillages(
            @PathVariable("userId") Long userId) {
        Long viewerId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(profileService.getUserVillages(userId, viewerId));
    }
}
