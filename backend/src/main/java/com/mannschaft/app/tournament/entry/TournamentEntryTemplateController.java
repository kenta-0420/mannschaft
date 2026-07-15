package com.mannschaft.app.tournament.entry;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.config.OrgScopeId;
import com.mannschaft.app.config.TeamScopeId;
import com.mannschaft.app.tournament.entry.dto.ApplyTemplateRequest;
import com.mannschaft.app.tournament.entry.dto.ApplyTemplateResponse;
import com.mannschaft.app.tournament.entry.dto.CreateEntryTemplateRequest;
import com.mannschaft.app.tournament.entry.dto.EntryTemplateDetailResponse;
import com.mannschaft.app.tournament.entry.dto.EntryTemplateResponse;
import com.mannschaft.app.tournament.entry.dto.UpdateEntryTemplateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * エントリーテンプレート管理コントローラー。
 *
 * <p>F08.7 Phase 9-B: チームごとのエントリーテンプレートのCRUD（最大5件）と
 * エントリー表への適用を担当する。</p>
 *
 * <p>設計書: docs/features/F08.7_tournament_league.md §Phase9-B</p>
 */
@RestController
@Tag(name = "エントリーテンプレート", description = "F08.7 Phase 9-B エントリーテンプレート管理")
@RequiredArgsConstructor
public class TournamentEntryTemplateController {

    private final TournamentEntryTemplateService entryTemplateService;

    /**
     * テンプレート一覧を取得する（最大5件）。
     *
     * @param orgId  組織ID
     * @param teamId チームID
     * @return テンプレート一覧
     */
    @GetMapping("/api/v1/organizations/{orgId}/teams/{teamId}/entry-templates")
    @Operation(summary = "エントリーテンプレート一覧")
    public ResponseEntity<ApiResponse<List<EntryTemplateResponse>>> getTemplates(
            @PathVariable Long orgId,
            @PathVariable Long teamId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        List<EntryTemplateResponse> result = entryTemplateService.getTemplates(orgId.value(), teamId.value(), currentUserId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * テンプレートを作成する（最大5件チェック）。
     *
     * @param orgId  組織ID
     * @param teamId チームID
     * @param req    作成リクエスト
     * @return 作成されたテンプレート詳細
     */
    @PostMapping("/api/v1/organizations/{orgId}/teams/{teamId}/entry-templates")
    @Operation(summary = "エントリーテンプレート作成")
    public ResponseEntity<ApiResponse<EntryTemplateDetailResponse>> createTemplate(
            @PathVariable OrgScopeId orgId,
            @PathVariable TeamScopeId teamId,
            @Valid @RequestBody CreateEntryTemplateRequest req) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        EntryTemplateDetailResponse result = entryTemplateService.createTemplate(orgId.value(), teamId.value(), req, currentUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    /**
     * テンプレート詳細を取得する。
     *
     * @param orgId      組織ID
     * @param teamId     チームID
     * @param templateId テンプレートID
     * @return テンプレート詳細（メンバー一覧付き）
     */
    @GetMapping("/api/v1/organizations/{orgId}/teams/{teamId}/entry-templates/{templateId}")
    @Operation(summary = "エントリーテンプレート詳細")
    public ResponseEntity<ApiResponse<EntryTemplateDetailResponse>> getTemplate(
            @PathVariable OrgScopeId orgId,
            @PathVariable TeamScopeId teamId,
            @PathVariable UUID templateId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        EntryTemplateDetailResponse result = entryTemplateService.getTemplate(orgId.value(), teamId.value(), templateId, currentUserId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * テンプレートを更新する（membersは全置換）。
     *
     * @param orgId      組織ID
     * @param teamId     チームID
     * @param templateId テンプレートID
     * @param req        更新リクエスト
     * @return 更新後のテンプレート詳細
     */
    @PutMapping("/api/v1/organizations/{orgId}/teams/{teamId}/entry-templates/{templateId}")
    @Operation(summary = "エントリーテンプレート更新")
    public ResponseEntity<ApiResponse<EntryTemplateDetailResponse>> updateTemplate(
            @PathVariable OrgScopeId orgId,
            @PathVariable TeamScopeId teamId,
            @PathVariable UUID templateId,
            @Valid @RequestBody UpdateEntryTemplateRequest req) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        EntryTemplateDetailResponse result = entryTemplateService.updateTemplate(
                orgId.value(), teamId.value(), templateId, req, currentUserId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * テンプレートを論理削除する。
     *
     * @param orgId      組織ID
     * @param teamId     チームID
     * @param templateId テンプレートID
     */
    @DeleteMapping("/api/v1/organizations/{orgId}/teams/{teamId}/entry-templates/{templateId}")
    @Operation(summary = "エントリーテンプレート論理削除")
    public ResponseEntity<Void> deleteTemplate(
            @PathVariable OrgScopeId orgId,
            @PathVariable TeamScopeId teamId,
            @PathVariable UUID templateId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        entryTemplateService.deleteTemplate(orgId.value(), teamId.value(), templateId, currentUserId);
        return ResponseEntity.noContent().build();
    }

    /**
     * テンプレートをエントリー表に適用する。
     *
     * @param orgId  組織ID
     * @param tId    大会ID
     * @param divId  ディビジョンID
     * @param pId    参加チームID
     * @param req    適用リクエスト
     * @return 適用結果
     */
    @PostMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/participants/{pId}/entry-members/apply-template")
    @Operation(summary = "テンプレートをエントリー表に適用")
    public ResponseEntity<ApiResponse<ApplyTemplateResponse>> applyTemplate(
            @PathVariable Long orgId,
            @PathVariable Long tId,
            @PathVariable Long divId,
            @PathVariable Long pId,
            @Valid @RequestBody ApplyTemplateRequest req) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ApplyTemplateResponse result = entryTemplateService.applyTemplate(
                orgId, tId, divId, pId, req, currentUserId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
