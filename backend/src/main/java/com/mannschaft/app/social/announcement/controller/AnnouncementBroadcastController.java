package com.mannschaft.app.social.announcement.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.social.announcement.AnnouncementBroadcastService;
import com.mannschaft.app.social.announcement.BroadcastRequest;
import com.mannschaft.app.social.announcement.BroadcastResult;
import com.mannschaft.app.social.announcement.audience.BroadcastAudienceResolver;
import com.mannschaft.app.social.announcement.dto.AudiencePreviewRequestDto;
import com.mannschaft.app.social.announcement.dto.AudiencePreviewResponseDto;
import com.mannschaft.app.social.announcement.dto.BroadcastRequestDto;
import com.mannschaft.app.social.announcement.dto.BroadcastResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * F02.8 告知ウィザード実行コントローラー。
 *
 * <p>チームスコープ・組織スコープそれぞれに告知ウィザードを実行するエンドポイントを提供する。
 * コンテンツ作成とお知らせフィード登録をトランザクション内で一括実行する。</p>
 *
 * <p>エンドポイント一覧:</p>
 * <ul>
 *   <li>{@code POST /api/v1/teams/{teamId}/broadcast} — チームへの告知ウィザード実行</li>
 *   <li>{@code POST /api/v1/organizations/{orgId}/broadcast} — 組織への告知ウィザード実行</li>
 *   <li>{@code POST /api/v1/organizations/{orgId}/broadcast/audience-preview} — 組織告知の宛先プレビュー（F01.2.1）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "告知ウィザード", description = "F02.8 チーム・組織ダッシュボード告知ウィザード API")
public class AnnouncementBroadcastController {

    private final AnnouncementBroadcastService broadcastService;
    private final BroadcastAudienceResolver audienceResolver;

    // ═════════════════════════════════════════════════════════════
    // POST /api/v1/teams/{teamId}/broadcast — チームスコープ告知実行
    // ═════════════════════════════════════════════════════════════

    /**
     * チームへの告知ウィザードを実行する。
     *
     * <p>指定チャネルでコンテンツを作成し、チームのお知らせフィードに登録する。
     * MEMBER ロールが NORMAL 以外の priority を指定した場合は 400 エラー。</p>
     *
     * @param teamId チーム ID
     * @param req    告知ウィザードリクエスト
     * @return 201 Created + 告知実行結果
     */
    @PostMapping("/api/v1/teams/{teamId}/broadcast")
    @Operation(
            summary = "チームへの告知ウィザード実行",
            description = "F02.8 告知ウィザード。チームスコープでコンテンツ作成 + お知らせフィード登録を一括実行する。"
                    + "MEMBER は priority=NORMAL のみ使用可。ADMIN/DEPUTY_ADMIN は全優先度が使用可。")
    public ResponseEntity<ApiResponse<BroadcastResponseDto>> broadcastToTeam(
            @PathVariable Long teamId,
            @Valid @RequestBody BroadcastRequestDto req) {

        Long userId = SecurityUtils.getCurrentUserId();
        BroadcastRequest serviceReq = toBroadcastRequest(req, "TEAM", teamId, userId);
        // 宛先の解決は告知のトランザクションの外で行う（TEAM スコープでのグループ指定は 400 BROADCAST_012）
        serviceReq.setAudience(audienceResolver.resolveForBroadcast(
                userId, "TEAM", teamId, req.toAudienceSpec(), serviceReq.getChannel()));
        BroadcastResult result = broadcastService.broadcast(serviceReq);

        log.info("チーム告知ウィザード実行 teamId={}, channel={}, userId={}",
                teamId, req.getChannel(), userId);

        return ResponseEntity.status(201)
                .body(ApiResponse.of(BroadcastResponseDto.from(result)));
    }

    // ═════════════════════════════════════════════════════════════
    // POST /api/v1/organizations/{orgId}/broadcast — 組織スコープ告知実行
    // ═════════════════════════════════════════════════════════════

    /**
     * 組織への告知ウィザードを実行する。
     *
     * <p>指定チャネルでコンテンツを作成し、組織のお知らせフィードに登録する。
     * targetTeamIds を指定した場合、組織配下チームであることを検証する（IDOR 対策）。</p>
     *
     * @param orgId 組織 ID
     * @param req   告知ウィザードリクエスト
     * @return 201 Created + 告知実行結果
     */
    @PostMapping("/api/v1/organizations/{orgId}/broadcast")
    @Operation(
            summary = "組織への告知ウィザード実行",
            description = "F02.8 告知ウィザード。組織スコープでコンテンツ作成 + お知らせフィード登録を一括実行する。"
                    + "targetTeamIds を指定した場合は組織配下チームであることを検証する。")
    public ResponseEntity<ApiResponse<BroadcastResponseDto>> broadcastToOrg(
            @PathVariable Long orgId,
            @Valid @RequestBody BroadcastRequestDto req) {

        Long userId = SecurityUtils.getCurrentUserId();
        BroadcastRequest serviceReq = toBroadcastRequest(req, "ORGANIZATION", orgId, userId);
        // 宛先の解決（認可 → 検証 → グループ展開）は告知のトランザクションの外で行う。
        // 組織・チーム・グループは別ドメインであり、告知（social）のトランザクションから引かないため。
        serviceReq.setAudience(audienceResolver.resolveForBroadcast(
                userId, "ORGANIZATION", orgId, req.toAudienceSpec(), serviceReq.getChannel()));
        BroadcastResult result = broadcastService.broadcast(serviceReq);

        log.info("組織告知ウィザード実行 orgId={}, channel={}, userId={}",
                orgId, req.getChannel(), userId);

        return ResponseEntity.status(201)
                .body(ApiResponse.of(BroadcastResponseDto.from(result)));
    }

    // ═════════════════════════════════════════════════════════════
    // POST /api/v1/organizations/{orgId}/broadcast/audience-preview — 宛先プレビュー
    // ═════════════════════════════════════════════════════════════

    /**
     * 告知の宛先プレビュー（F01.2.1 §8.4・§10.9）。宛先指定から解決されるチーム数・直属メンバー数・
     * 先頭 50 件のチームと、push が送られるかを返す。何も書き込まない。
     *
     * <p>認可は broadcast と同じ契約（組織の非メンバー・存在しない orgId は 403 {@code COMMON_002}、MEMBER 以上は 200）。
     * レートリミットは 60 件/分/ユーザー（{@code BroadcastRateLimitFilter}）。</p>
     *
     * @param orgId 組織 ID
     * @param req   宛先指定と channel・targetRole
     * @return 200 OK + プレビュー
     */
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "告知ウィザードの宛先確認は既存の告知 API と同じく機能ゲートの対象外とするため")
    @PostMapping("/api/v1/organizations/{orgId}/broadcast/audience-preview")
    @Operation(
            summary = "組織告知の宛先プレビュー",
            description = "F01.2.1 §10.9。宛先指定（チーム・グループ個別・範囲・未分類）を解決し、チーム数・直属メンバー数・"
                    + "先頭50件のチーム・展開後のグループ・pushEnabled を返す。組織の非メンバーは 403 COMMON_002。")
    public ResponseEntity<ApiResponse<AudiencePreviewResponseDto>> previewOrgAudience(
            @PathVariable Long orgId,
            @Valid @RequestBody AudiencePreviewRequestDto req) {

        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(audienceResolver.preview(userId, orgId, req)));
    }

    // ─────────────────────────────────────────────────────────────
    // プライベートヘルパー
    // ─────────────────────────────────────────────────────────────

    /**
     * {@link BroadcastRequestDto} をサービス層向けの {@link BroadcastRequest} に変換する。
     *
     * @param dto       コントローラーが受け取ったリクエスト DTO
     * @param scopeType スコープ種別文字列（TEAM / ORGANIZATION）
     * @param scopeId   スコープ ID（チーム ID または組織 ID）
     * @param userId    認証済みユーザー ID
     * @return サービス層向けリクエストオブジェクト
     */
    private BroadcastRequest toBroadcastRequest(
            BroadcastRequestDto dto, String scopeType, Long scopeId, Long userId) {
        return BroadcastRequest.builder()
                .channel(dto.getChannel())
                .targetRole(dto.getTargetRole())
                .targetTeamIds(dto.getTargetTeamIds())
                .templateId(dto.getTemplateId())
                .priority(dto.getPriority() != null ? dto.getPriority() : "NORMAL")
                .expiresAt(dto.getExpiresAt())
                .content(dto.getContent())
                .callerUserId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .build();
    }
}
