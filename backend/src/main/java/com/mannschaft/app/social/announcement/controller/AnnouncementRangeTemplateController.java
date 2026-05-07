package com.mannschaft.app.social.announcement.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.social.announcement.AnnouncementRangeTemplateEntity;
import com.mannschaft.app.social.announcement.AnnouncementRangeTemplateRequest;
import com.mannschaft.app.social.announcement.AnnouncementRangeTemplateService;
import com.mannschaft.app.social.announcement.dto.TemplateResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F02.8 告知ウィザード範囲テンプレートコントローラー。
 *
 * <p>チームスコープ・組織スコープそれぞれの告知範囲テンプレートの CRUD を提供する。
 * 一覧取得は MEMBER 以上、作成・更新・削除は ADMIN / DEPUTY_ADMIN のみ実行可能
 * （権限チェックはサービス層で実施）。</p>
 *
 * <p>エンドポイント一覧:</p>
 * <ul>
 *   <li>{@code GET    /api/v1/teams/{teamId}/announcement-templates} — チームテンプレート一覧</li>
 *   <li>{@code POST   /api/v1/teams/{teamId}/announcement-templates} — チームテンプレート作成</li>
 *   <li>{@code PUT    /api/v1/teams/{teamId}/announcement-templates/{id}} — チームテンプレート更新</li>
 *   <li>{@code DELETE /api/v1/teams/{teamId}/announcement-templates/{id}} — チームテンプレート削除</li>
 *   <li>{@code GET    /api/v1/organizations/{orgId}/announcement-templates} — 組織テンプレート一覧</li>
 *   <li>{@code POST   /api/v1/organizations/{orgId}/announcement-templates} — 組織テンプレート作成</li>
 *   <li>{@code PUT    /api/v1/organizations/{orgId}/announcement-templates/{id}} — 組織テンプレート更新</li>
 *   <li>{@code DELETE /api/v1/organizations/{orgId}/announcement-templates/{id}} — 組織テンプレート削除</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "告知ウィザード範囲テンプレート", description = "F02.8 チーム・組織ダッシュボード告知ウィザード テンプレート管理 API")
public class AnnouncementRangeTemplateController {

    private final AnnouncementRangeTemplateService templateService;

    // ═════════════════════════════════════════════════════════════
    // チームスコープ
    // ═════════════════════════════════════════════════════════════

    /**
     * チームスコープの告知範囲テンプレート一覧を取得する（MEMBER 以上）。
     *
     * @param teamId チーム ID
     * @return テンプレート一覧（作成日時降順）
     */
    @GetMapping("/api/v1/teams/{teamId}/announcement-templates")
    @Operation(
            summary = "チームテンプレート一覧取得",
            description = "チームスコープの告知範囲テンプレートを作成日時降順で返す。MEMBER 以上が取得可能。")
    public ResponseEntity<ApiResponse<List<TemplateResponseDto>>> listTeamTemplates(
            @PathVariable Long teamId) {

        Long userId = SecurityUtils.getCurrentUserId();
        List<AnnouncementRangeTemplateEntity> entities =
                templateService.findAll("TEAM", teamId, userId);

        List<TemplateResponseDto> dtos = entities.stream()
                .map(TemplateResponseDto::from)
                .toList();

        return ResponseEntity.ok(ApiResponse.of(dtos));
    }

    /**
     * チームスコープの告知範囲テンプレートを新規作成する（ADMIN / DEPUTY_ADMIN のみ）。
     *
     * <p>1スコープあたり最大20件。is_default=true を指定した場合、既存デフォルトは解除される。</p>
     *
     * @param teamId チーム ID
     * @param req    テンプレート作成リクエスト
     * @return 201 Created + 作成されたテンプレート
     */
    @PostMapping("/api/v1/teams/{teamId}/announcement-templates")
    @Operation(
            summary = "チームテンプレート作成",
            description = "チームスコープの告知範囲テンプレートを作成する。ADMIN / DEPUTY_ADMIN のみ可。"
                    + "上限（20件）超過時は 409。is_default=true の場合、既存デフォルトを解除する。")
    public ResponseEntity<ApiResponse<TemplateResponseDto>> createTeamTemplate(
            @PathVariable Long teamId,
            @Valid @RequestBody AnnouncementRangeTemplateRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        AnnouncementRangeTemplateEntity entity =
                templateService.create("TEAM", teamId, userId, req);

        log.info("チームテンプレート作成 teamId={}, name={}, userId={}",
                teamId, req.getName(), userId);

        return ResponseEntity.status(201)
                .body(ApiResponse.of(TemplateResponseDto.from(entity)));
    }

    /**
     * チームスコープの告知範囲テンプレートを更新する（ADMIN / DEPUTY_ADMIN のみ）。
     *
     * @param teamId チーム ID
     * @param id     テンプレート ID
     * @param req    テンプレート更新リクエスト
     * @return 更新後テンプレート
     */
    @PutMapping("/api/v1/teams/{teamId}/announcement-templates/{id}")
    @Operation(
            summary = "チームテンプレート更新",
            description = "チームスコープの告知範囲テンプレートを更新する。ADMIN / DEPUTY_ADMIN のみ可。"
                    + "他スコープのテンプレート ID を指定した場合は 404。")
    public ResponseEntity<ApiResponse<TemplateResponseDto>> updateTeamTemplate(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody AnnouncementRangeTemplateRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        AnnouncementRangeTemplateEntity entity =
                templateService.update("TEAM", teamId, id, userId, req);

        log.info("チームテンプレート更新 teamId={}, templateId={}, userId={}",
                teamId, id, userId);

        return ResponseEntity.ok(ApiResponse.of(TemplateResponseDto.from(entity)));
    }

    /**
     * チームスコープの告知範囲テンプレートを削除する（ADMIN / DEPUTY_ADMIN のみ）。
     *
     * @param teamId チーム ID
     * @param id     テンプレート ID
     * @return 204 No Content
     */
    @DeleteMapping("/api/v1/teams/{teamId}/announcement-templates/{id}")
    @Operation(
            summary = "チームテンプレート削除",
            description = "チームスコープの告知範囲テンプレートを削除する。ADMIN / DEPUTY_ADMIN のみ可。"
                    + "他スコープのテンプレート ID を指定した場合は 404。")
    public ResponseEntity<Void> deleteTeamTemplate(
            @PathVariable Long teamId,
            @PathVariable Long id) {

        Long userId = SecurityUtils.getCurrentUserId();
        templateService.delete("TEAM", teamId, id, userId);

        log.info("チームテンプレート削除 teamId={}, templateId={}, userId={}",
                teamId, id, userId);

        return ResponseEntity.noContent().build();
    }

    // ═════════════════════════════════════════════════════════════
    // 組織スコープ
    // ═════════════════════════════════════════════════════════════

    /**
     * 組織スコープの告知範囲テンプレート一覧を取得する（MEMBER 以上）。
     *
     * @param orgId 組織 ID
     * @return テンプレート一覧（作成日時降順）
     */
    @GetMapping("/api/v1/organizations/{orgId}/announcement-templates")
    @Operation(
            summary = "組織テンプレート一覧取得",
            description = "組織スコープの告知範囲テンプレートを作成日時降順で返す。MEMBER 以上が取得可能。")
    public ResponseEntity<ApiResponse<List<TemplateResponseDto>>> listOrgTemplates(
            @PathVariable Long orgId) {

        Long userId = SecurityUtils.getCurrentUserId();
        List<AnnouncementRangeTemplateEntity> entities =
                templateService.findAll("ORGANIZATION", orgId, userId);

        List<TemplateResponseDto> dtos = entities.stream()
                .map(TemplateResponseDto::from)
                .toList();

        return ResponseEntity.ok(ApiResponse.of(dtos));
    }

    /**
     * 組織スコープの告知範囲テンプレートを新規作成する（ADMIN / DEPUTY_ADMIN のみ）。
     *
     * <p>1スコープあたり最大20件。is_default=true を指定した場合、既存デフォルトは解除される。</p>
     *
     * @param orgId 組織 ID
     * @param req   テンプレート作成リクエスト
     * @return 201 Created + 作成されたテンプレート
     */
    @PostMapping("/api/v1/organizations/{orgId}/announcement-templates")
    @Operation(
            summary = "組織テンプレート作成",
            description = "組織スコープの告知範囲テンプレートを作成する。ADMIN / DEPUTY_ADMIN のみ可。"
                    + "上限（20件）超過時は 409。is_default=true の場合、既存デフォルトを解除する。")
    public ResponseEntity<ApiResponse<TemplateResponseDto>> createOrgTemplate(
            @PathVariable Long orgId,
            @Valid @RequestBody AnnouncementRangeTemplateRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        AnnouncementRangeTemplateEntity entity =
                templateService.create("ORGANIZATION", orgId, userId, req);

        log.info("組織テンプレート作成 orgId={}, name={}, userId={}",
                orgId, req.getName(), userId);

        return ResponseEntity.status(201)
                .body(ApiResponse.of(TemplateResponseDto.from(entity)));
    }

    /**
     * 組織スコープの告知範囲テンプレートを更新する（ADMIN / DEPUTY_ADMIN のみ）。
     *
     * @param orgId 組織 ID
     * @param id    テンプレート ID
     * @param req   テンプレート更新リクエスト
     * @return 更新後テンプレート
     */
    @PutMapping("/api/v1/organizations/{orgId}/announcement-templates/{id}")
    @Operation(
            summary = "組織テンプレート更新",
            description = "組織スコープの告知範囲テンプレートを更新する。ADMIN / DEPUTY_ADMIN のみ可。"
                    + "他スコープのテンプレート ID を指定した場合は 404。")
    public ResponseEntity<ApiResponse<TemplateResponseDto>> updateOrgTemplate(
            @PathVariable Long orgId,
            @PathVariable Long id,
            @Valid @RequestBody AnnouncementRangeTemplateRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        AnnouncementRangeTemplateEntity entity =
                templateService.update("ORGANIZATION", orgId, id, userId, req);

        log.info("組織テンプレート更新 orgId={}, templateId={}, userId={}",
                orgId, id, userId);

        return ResponseEntity.ok(ApiResponse.of(TemplateResponseDto.from(entity)));
    }

    /**
     * 組織スコープの告知範囲テンプレートを削除する（ADMIN / DEPUTY_ADMIN のみ）。
     *
     * @param orgId 組織 ID
     * @param id    テンプレート ID
     * @return 204 No Content
     */
    @DeleteMapping("/api/v1/organizations/{orgId}/announcement-templates/{id}")
    @Operation(
            summary = "組織テンプレート削除",
            description = "組織スコープの告知範囲テンプレートを削除する。ADMIN / DEPUTY_ADMIN のみ可。"
                    + "他スコープのテンプレート ID を指定した場合は 404。")
    public ResponseEntity<Void> deleteOrgTemplate(
            @PathVariable Long orgId,
            @PathVariable Long id) {

        Long userId = SecurityUtils.getCurrentUserId();
        templateService.delete("ORGANIZATION", orgId, id, userId);

        log.info("組織テンプレート削除 orgId={}, templateId={}, userId={}",
                orgId, id, userId);

        return ResponseEntity.noContent().build();
    }
}
