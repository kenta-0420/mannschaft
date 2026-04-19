package com.mannschaft.app.committee.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.committee.dto.CommitteeDistributeRequest;
import com.mannschaft.app.committee.dto.CommitteeDistributionLogResponse;
import com.mannschaft.app.committee.entity.CommitteeDistributionLogEntity;
import com.mannschaft.app.committee.service.CommitteeDistributionService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F04.10 委員会伝達処理コントローラー。
 *
 * <p>委員会からの伝達（お知らせ配信・確認通知送信）の実行と
 * 伝達処理ログの閲覧エンドポイントを提供する。</p>
 *
 * <ul>
 *   <li>POST /committees/{committeeId}/distributions — 伝達実行（201 Created）</li>
 *   <li>GET  /committees/{committeeId}/distributions — 伝達履歴一覧</li>
 *   <li>GET  /committees/{committeeId}/distributions/{distributionId} — 伝達履歴詳細</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/committees/{committeeId}/distributions")
@Tag(name = "委員会伝達処理")
@RequiredArgsConstructor
public class CommitteeDistributionController {

    private final CommitteeDistributionService distributionService;
    private final ObjectMapper objectMapper;

    // ========================================
    // 伝達実行
    // ========================================

    /**
     * 委員会から伝達を実行する。
     *
     * <p>認可: CHAIR / VICE_CHAIR / SECRETARY のいずれか。</p>
     *
     * @param committeeId 委員会 ID
     * @param request     伝達リクエスト
     * @return 作成された伝達処理ログ
     */
    @PostMapping
    @Operation(summary = "委員会伝達実行", description = "委員会からお知らせ配信・確認通知送信を実行し、ログを保存します")
    public ResponseEntity<ApiResponse<CommitteeDistributionLogResponse>> distribute(
            @PathVariable Long committeeId,
            @Valid @RequestBody CommitteeDistributeRequest request) {

        Long currentUserId = SecurityUtils.getCurrentUserId();
        CommitteeDistributionLogEntity entity =
                distributionService.distribute(committeeId, request, currentUserId);

        CommitteeDistributionLogResponse response =
                CommitteeDistributionLogResponse.of(entity, objectMapper);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.of(response));
    }

    // ========================================
    // 伝達履歴一覧
    // ========================================

    /**
     * 委員会の伝達処理履歴一覧を取得する（作成日時降順）。
     *
     * <p>認可: 委員会メンバーのみ。</p>
     *
     * @param committeeId 委員会 ID
     * @param pageable    ページング情報
     * @return 伝達処理ログ一覧ページ
     */
    @GetMapping
    @Operation(summary = "委員会伝達履歴一覧", description = "委員会の伝達処理履歴を作成日時降順で取得します")
    public ResponseEntity<ApiResponse<Page<CommitteeDistributionLogResponse>>> listDistributions(
            @PathVariable Long committeeId,
            Pageable pageable) {

        Long currentUserId = SecurityUtils.getCurrentUserId();
        Page<CommitteeDistributionLogEntity> page =
                distributionService.listDistributions(committeeId, currentUserId, pageable);

        Page<CommitteeDistributionLogResponse> responsePage =
                page.map(entity -> CommitteeDistributionLogResponse.of(entity, objectMapper));

        return ResponseEntity.ok(ApiResponse.of(responsePage));
    }

    // ========================================
    // 伝達履歴詳細
    // ========================================

    /**
     * 伝達処理履歴の詳細を取得する。
     *
     * <p>認可: 対象委員会のメンバーのみ。</p>
     *
     * @param committeeId    委員会 ID
     * @param distributionId 伝達処理ログ ID
     * @return 伝達処理ログ詳細
     */
    @GetMapping("/{distributionId}")
    @Operation(summary = "委員会伝達履歴詳細", description = "指定した伝達処理履歴の詳細を取得します")
    public ResponseEntity<ApiResponse<CommitteeDistributionLogResponse>> getDistribution(
            @PathVariable Long committeeId,
            @PathVariable Long distributionId) {

        Long currentUserId = SecurityUtils.getCurrentUserId();
        CommitteeDistributionLogEntity entity =
                distributionService.getDistribution(distributionId, currentUserId);

        CommitteeDistributionLogResponse response =
                CommitteeDistributionLogResponse.of(entity, objectMapper);

        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
