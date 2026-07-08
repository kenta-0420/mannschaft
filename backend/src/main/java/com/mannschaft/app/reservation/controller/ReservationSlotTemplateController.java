package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reservation.dto.CreateSlotTemplateRequest;
import com.mannschaft.app.reservation.dto.DeleteSlotTemplateResponse;
import com.mannschaft.app.reservation.dto.GenerateSingleDayRequest;
import com.mannschaft.app.reservation.dto.GenerateSlotsRequest;
import com.mannschaft.app.reservation.dto.GenerateSlotsResponse;
import com.mannschaft.app.reservation.dto.SlotGenerationResultDto;
import com.mannschaft.app.reservation.dto.SlotTemplateListResponse;
import com.mannschaft.app.reservation.dto.SlotTemplateResponse;
import com.mannschaft.app.reservation.dto.SlotTemplateSaveResponse;
import com.mannschaft.app.reservation.dto.UpdateSlotTemplateRequest;
import com.mannschaft.app.reservation.service.ReservationSlotTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 週間テンプレートコントローラー（F03.4.2 §4）。
 *
 * <p>全 5 エンドポイントとも管理者（ADMIN / DEPUTY_ADMIN・role ベース）専用の self-gate
 * （{@code @PreAuthorize("@accessGuard.isScopeAdmin(...)")}・親 §6 方針）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/teams/{teamId}/reservation-slot-templates")
@Tag(name = "予約枠週間テンプレート", description = "F03.4.2 週間テンプレートCRUD＋一括生成")
@RequiredArgsConstructor
public class ReservationSlotTemplateController {

    private final ReservationSlotTemplateService templateService;

    /**
     * テンプレ一覧を取得する（曜日・ライン別）。
     */
    @GetMapping
    @Operation(summary = "週間テンプレート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<SlotTemplateListResponse>> listTemplates(
            @PathVariable Long teamId) {
        return ResponseEntity.ok(ApiResponse.of(templateService.listTemplates(teamId)));
    }

    /**
     * テンプレを作成し、当該テンプレの枠を horizon 28 日まで<b>同期自動生成</b>する（F03.4.5 §3.1）。
     *
     * <p>保存（{@code @Transactional} 内でコミット）→ その外側で生成、の順で実行する。応答は
     * {@link SlotTemplateSaveResponse}（保存結果＋生成カウント）。生成が失敗しても保存は成立済みのため
     * HTTP 201 で返し、{@code generation.failed=true} で正直に報告する。</p>
     */
    @PostMapping
    @Operation(summary = "週間テンプレート作成（保存＝同期自動生成）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<SlotTemplateSaveResponse>> createTemplate(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateSlotTemplateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        // ① 保存 tx をコミットさせる（@Transactional 内）
        SlotTemplateResponse saved = templateService.createTemplate(teamId, request, userId);
        // ② 保存 tx コミット後・@Transactional の外側で同期自動生成（§3.1 の tx 境界・fk_rs_template 自己DL回避）
        SlotGenerationResultDto generation = generateForSavedTemplate(teamId, saved.getId(), userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(new SlotTemplateSaveResponse(saved, generation)));
    }

    /**
     * テンプレを部分更新し（isActive 切替・clearLineId を含む）、当該テンプレの枠を同期自動生成する
     * （F03.4.5 §3.1）。既生成枠への遡及はなく、変更後の新定義セルの追加生成のみが起きる。
     */
    @PatchMapping("/{templateId}")
    @Operation(summary = "週間テンプレート更新（保存＝同期自動生成）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<SlotTemplateSaveResponse>> updateTemplate(
            @PathVariable Long teamId,
            @PathVariable UUID templateId,
            @Valid @RequestBody UpdateSlotTemplateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        SlotTemplateResponse saved = templateService.updateTemplate(teamId, templateId, request, userId);
        SlotGenerationResultDto generation = generateForSavedTemplate(teamId, saved.getId(), userId);
        return ResponseEntity.ok(ApiResponse.of(new SlotTemplateSaveResponse(saved, generation)));
    }

    /**
     * 保存されたテンプレの同期自動生成を実行し、結果を {@link SlotGenerationResultDto} に包む（§3.1）。
     *
     * <p>生成段の失敗は保存を壊さない（保存 tx は既にコミット済み）。失敗時は {@code failed=true} で
     * 正直に報告し、翌朝の日次バッチ差分レンジが自己修復する。ここで例外を握りつぶすのではなく、
     * {@code log.error} で記録したうえで失敗フラグを応答に載せる（症状を隠さない・§3.1）。</p>
     */
    private SlotGenerationResultDto generateForSavedTemplate(Long teamId, UUID templateId, Long userId) {
        try {
            GenerateSlotsResponse generation = templateService.generateForTemplate(teamId, templateId, userId);
            return SlotGenerationResultDto.of(generation);
        } catch (Exception e) {
            log.error("テンプレ保存後の同期自動生成に失敗（保存は成立・翌日次バッチが自己修復）: "
                    + "teamId={}, templateId={}", teamId, templateId, e);
            return SlotGenerationResultDto.ofFailure();
        }
    }

    /**
     * テンプレを物理削除する（生成済み枠は SET NULL で残置）。
     */
    @DeleteMapping("/{templateId}")
    @Operation(summary = "週間テンプレート削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "削除成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<DeleteSlotTemplateResponse>> deleteTemplate(
            @PathVariable Long teamId,
            @PathVariable UUID templateId) {
        DeleteSlotTemplateResponse response =
                templateService.deleteTemplate(teamId, templateId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チームの active テンプレ全件を対象に一括生成する（冪等・レートリミット 2回/分/チーム）。
     *
     * @deprecated F03.4.5 §3.1: テンプレ保存＝同期自動生成の採用により「今すぐ枠を作成」UI は撤去された。
     *     生成型を参照する既存クライアント・E2E・運用リカバリ（バッチ障害時の手動追い付き）用に残置する。
     *     削除は将来の別 PR・要裁可（§14）。
     */
    @Deprecated(since = "F03.4.5")
    @PostMapping("/generate")
    @Operation(summary = "週間テンプレート一括生成（非推奨: 保存＝自動生成へ移行）", deprecated = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "生成成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<GenerateSlotsResponse>> generate(
            @PathVariable Long teamId,
            @Valid @RequestBody GenerateSlotsRequest request) {
        GenerateSlotsResponse response =
                templateService.generate(teamId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 臨時営業（単日テンプレ適用・F03.4.5 §3.3.2）: 指定日に、指定曜日ダイヤ（省略時=実曜日）の
     * active テンプレ構成で 30 分セルを一括生成する。<b>営業時間突合をスキップ</b>する
     * （臨時営業は定休日/時間外が前提）。冪等キーがそのまま効くため同一日への再実行はスキップされる。
     *
     * <p>§4 の定期予約不可枠・同日の全日休業があっても BE は生成をブロックしない
     * （生成する・runtime で落とすの一貫方針・§3.3.2/§4.2）。認可は他テンプレ系と同一（ADMIN+）。
     * レートリミットは既存 generate と同一 zone を共有（RESERVATION_044 再利用）。</p>
     */
    @PostMapping("/generate-single-day")
    @Operation(summary = "臨時営業（単日テンプレ適用）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "生成成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<GenerateSlotsResponse>> generateSingleDay(
            @PathVariable Long teamId,
            @Valid @RequestBody GenerateSingleDayRequest request) {
        GenerateSlotsResponse response =
                templateService.generateSingleDay(teamId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
