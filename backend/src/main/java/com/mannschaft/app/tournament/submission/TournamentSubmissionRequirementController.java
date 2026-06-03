package com.mannschaft.app.tournament.submission;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.forms.dto.CreateFormSubmissionRequest;
import com.mannschaft.app.forms.dto.FormSubmissionResponse;
import com.mannschaft.app.tournament.submission.dto.CreateSubmissionRequirementRequest;
import com.mannschaft.app.tournament.submission.dto.SubmissionRequirementResponse;
import com.mannschaft.app.tournament.submission.dto.SubmissionStatusDashboardResponse;
import com.mannschaft.app.tournament.submission.dto.UpdateSubmissionRequirementRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 大会ごとの書類提出受付コントローラー（F08.7.1/06）。
 *
 * <p>主催者が提出枠（form_template ＋ 締切 ＋ 対象）を定義し、各チーム代表が提出、主催者が受理／差戻し
 * する「提出インボックス」を提供する。提出／承認の実処理（form_submission 保存・workflow_request 承認）は
 * F05.6 の既存基盤に委譲し、本コントローラーは「大会スコープのファサード（requirement とのひも付け）」に
 * 留まる（設計書 §5.1）。</p>
 *
 * <p>エンドポイント:</p>
 * <ul>
 *   <li>POST   /submission-requirements                       提出枠定義（主催組織 ADMIN）</li>
 *   <li>GET    /submission-requirements                       提出枠一覧（主催者=全件 / teamId 指定=自チーム対象のみ）</li>
 *   <li>PATCH  /submission-requirements/{reqId}               提出枠更新（主催組織 ADMIN）</li>
 *   <li>DELETE /submission-requirements/{reqId}               提出枠削除（主催組織 ADMIN）</li>
 *   <li>GET    /submission-requirements/{reqId}/status        提出状況ダッシュボード（主催組織 ADMIN）</li>
 *   <li>POST   /submission-requirements/{reqId}/teams/{teamId}/submit  自チーム分の提出（自チーム ADMIN/DEPUTY）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tournaments/{tournamentId}/submission-requirements")
@Tag(name = "大会書類提出受付", description = "F08.7.1/06 大会ごとの書類提出受付（F05.6 workflow＋forms 再利用）")
@RequiredArgsConstructor
public class TournamentSubmissionRequirementController {

    private final TournamentSubmissionRequirementService submissionService;

    @PostMapping
    @Operation(summary = "提出枠の定義", description = "主催組織 ADMIN のみ。form_template は F05.6 で作成済みのものを連結する")
    public ResponseEntity<ApiResponse<SubmissionRequirementResponse>> createRequirement(
            @PathVariable Long orgId,
            @PathVariable Long tournamentId,
            @Valid @RequestBody CreateSubmissionRequirementRequest request) {
        SubmissionRequirementResponse response =
                submissionService.createRequirement(orgId, tournamentId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping
    @Operation(summary = "提出枠一覧",
            description = "teamId 未指定＝主催組織 ADMIN が全件。teamId 指定＝当該チーム ADMIN/DEPUTY が自チーム対象の枠のみ取得")
    public ResponseEntity<ApiResponse<List<SubmissionRequirementResponse>>> listRequirements(
            @PathVariable Long orgId,
            @PathVariable Long tournamentId,
            @RequestParam(required = false) Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<SubmissionRequirementResponse> response = (teamId == null)
                ? submissionService.listRequirementsForOrganizer(orgId, tournamentId, userId)
                : submissionService.listRequirementsForTeam(orgId, tournamentId, teamId, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PatchMapping("/{reqId}")
    @Operation(summary = "提出枠の更新", description = "主催組織 ADMIN のみ。締切・対象・支払い条件・表示情報")
    public ResponseEntity<ApiResponse<SubmissionRequirementResponse>> updateRequirement(
            @PathVariable Long orgId,
            @PathVariable Long tournamentId,
            @PathVariable UUID reqId,
            @Valid @RequestBody UpdateSubmissionRequirementRequest request) {
        SubmissionRequirementResponse response = submissionService.updateRequirement(
                orgId, tournamentId, reqId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @DeleteMapping("/{reqId}")
    @Operation(summary = "提出枠の削除", description = "主催組織 ADMIN のみ。論理削除")
    public ResponseEntity<Void> deleteRequirement(
            @PathVariable Long orgId,
            @PathVariable Long tournamentId,
            @PathVariable UUID reqId) {
        submissionService.deleteRequirement(orgId, tournamentId, reqId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{reqId}/status")
    @Operation(summary = "提出状況ダッシュボード",
            description = "主催組織 ADMIN のみ。チーム別 未提出/提出済/受理/差戻し・締切超過フラグ")
    public ResponseEntity<ApiResponse<SubmissionStatusDashboardResponse>> getStatus(
            @PathVariable Long orgId,
            @PathVariable Long tournamentId,
            @PathVariable UUID reqId) {
        SubmissionStatusDashboardResponse response = submissionService.getStatusDashboard(
                orgId, tournamentId, reqId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/{reqId}/teams/{teamId}/submit")
    @Operation(summary = "自チーム分の提出",
            description = "自チーム ADMIN/DEPUTY_ADMIN のみ。F05.6 の form_submission 起票へ委譲。締切超過・requires_payment 未払いはブロック")
    public ResponseEntity<ApiResponse<FormSubmissionResponse>> submitForTeam(
            @PathVariable Long orgId,
            @PathVariable Long tournamentId,
            @PathVariable UUID reqId,
            @PathVariable Long teamId,
            @Valid @RequestBody CreateFormSubmissionRequest request) {
        FormSubmissionResponse response = submissionService.submitForTeam(
                orgId, tournamentId, reqId, teamId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
