package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.dto.VillageAffinityResponse;
import com.mannschaft.app.village.service.VillageAffinityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F17.2 機能⑤ — 加入前相性表示 Controller（設計書 §8）。
 *
 * <p>非メンバーもアクセス可能な唯一の村内 API。認可は Service 層で厳密に行う
 * （{@code visibility=PUBLIC} のみ応答・UNLISTED は存在秘匿の 404・§8.7）。
 * 未ログインは {@link SecurityUtils#getCurrentUserId()} が 401 を投げる。
 * レート制限は {@code VillageAffinityRateLimitFilter}（userId+villageId・30回/分・§8.4）で担う。</p>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/affinity")
@Tag(name = "村 加入前相性表示 (F17.2)",
     description = "非メンバーが「この村は自分と合いそうか」を掴む相性のヒント（バケット化・identity 非返却）")
@RequiredArgsConstructor
// 認可番人(AuthzControllerGuardArchTest・Wave4)向けマーカー。認可の根拠:
// 認可は VillageAffinityService#loadPublicVillageOrHide が担う。① visibility=PUBLIC の村のみ応答し、
// ② UNLISTED/不存在/削除済みは一律 VILLAGE_NOT_FOUND(404) で存在秘匿する（§8.7）。未ログインは入口の
// SecurityUtils.getCurrentUserId() が 401 を投げる。データ依存の開放条件（非メンバーに開く）ゆえ
// @PreAuthorize では表現できないため、Service 層で認可済みであることを本マーカーで明示する。
@AuthorizedInService
public class VillageAffinityController {

    private final VillageAffinityService affinityService;

    /**
     * 自分と対象村の相性のヒントを取得する（§8.3）。
     *
     * <p>要ログイン・非メンバー可。応答はバケット化した集計値と i18n キーのみで、
     * 村人の identity・正確な重なり人数は一切含まない。</p>
     */
    @GetMapping("/me")
    @Operation(summary = "加入前相性表示を取得する（PUBLIC 村のみ・非メンバー可・identity 非返却）")
    public ApiResponse<VillageAffinityResponse> getMyAffinity(
            @PathVariable("villageId") UUID villageId) {
        Long viewerId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(affinityService.getAffinity(villageId, viewerId));
    }
}
