package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.dto.PostingIdentityListResponse;
import com.mannschaft.app.village.service.PostingIdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F17.1 Phase 1 B9 — 投稿主体一覧 Controller。
 *
 * <p>担当 API（設計書 §4.6）:</p>
 * <ul>
 *   <li>{@code GET /api/v1/me/villages/{villageId}/posting-identities} — 自分が当該村でなれる投稿主体一覧</li>
 * </ul>
 *
 * <p>呼び出しユーザーが村のメンバーでない場合は {@code 404 VILLAGE_007}（IDOR 対策）。</p>
 */
@RestController
@RequestMapping("/api/v1/me/villages/{villageId}/posting-identities")
@Tag(name = "村投稿主体 (F17.1)", description = "Phase 1: 投稿主体一覧（USER + 代表チーム/組織）")
@RequiredArgsConstructor
public class PostingIdentityController {

    private final PostingIdentityService postingIdentityService;

    /**
     * 認可は {@link PostingIdentityService#listIdentities} 内で実施する。村の存在確認
     * （削除・凍結を除外）ののち、呼び出しユーザーが在籍かつ BAN 済みでない USER メンバーで
     * あることを検証し、満たさない場合は {@code VILLAGE_007 NOT_MEMBER}（404）を返す。
     * 返す TEAM / ORGANIZATION 主体も、呼び出しユーザー自身が管理権限を持ちかつ
     * 当該村のメンバーである団体に限る。
     */
    @AuthorizedInService
    @GetMapping
    @Operation(summary = "村でなれる投稿主体一覧を取得する（村人のみ）")
    public ApiResponse<PostingIdentityListResponse> list(@PathVariable("villageId") UUID villageId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(postingIdentityService.listIdentities(actorUserId, villageId));
    }
}
