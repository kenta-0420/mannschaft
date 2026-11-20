package com.mannschaft.app.effortrecord.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.effortrecord.dto.CreateDefinitionSnapshotRequest;
import com.mannschaft.app.effortrecord.dto.CreateEffortRecordRequest;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionTokenResponse;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.dto.EffortDeleteResponse;
import com.mannschaft.app.effortrecord.dto.EffortOwnerRecordResponse;
import com.mannschaft.app.effortrecord.dto.EffortRecordListCriteria;
import com.mannschaft.app.effortrecord.dto.EffortRecordPage;
import com.mannschaft.app.effortrecord.dto.EffortStatsCriteria;
import com.mannschaft.app.effortrecord.dto.EffortStatsResponse;
import com.mannschaft.app.effortrecord.dto.UpdateEffortRecordRequest;
import com.mannschaft.app.effortrecord.enums.EffortChartType;
import com.mannschaft.app.effortrecord.enums.EffortGrain;
import com.mannschaft.app.effortrecord.service.EffortDefinitionService;
import com.mannschaft.app.effortrecord.service.EffortRecordOwnerService;
import com.mannschaft.app.effortrecord.service.EffortStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F06.4.1 個人の頑張り記録 個人 API。API 契約 §3 の 8 本（definition-snapshots は PR-2 で追加）。
 *
 * <p>Controller は Service を呼ぶだけ。認証主体は {@link SecurityUtils#getCurrentUserId()} で確定し、
 * リクエストから owner を受け取らない。固定 path（presets / stats）は
 * {@code /{id}} より先に解決される（Spring は具体的な path を優先する）。</p>
 */
@RestController
@RequestMapping(value = "/api/v1/effort-records/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "個人の頑張り記録", description = "F06.4.1 個人の頑張り記録（個人 API）")
@RequiredArgsConstructor
public class EffortRecordMeController {

    /** 一覧が受け付ける query。これ以外（camelCase の別名等）は 400（API 契約 §1）。 */
    private static final Set<String> LIST_QUERY = Set.of("date_from", "date_to", "definition_uuid", "cursor", "limit");
    private static final Set<String> STATS_QUERY =
            Set.of("date_from", "date_to", "definition_uuid", "field_key", "chart", "grain");
    private static final Set<String> DELETE_QUERY = Set.of("version");

    private final EffortRecordOwnerService ownerService;
    private final EffortDefinitionService definitionService;
    private final EffortStatsService statsService;

    @GetMapping("/presets")
    @SelfScopedEndpoint("標準プリセットは全員共通の参照データで、本人固有の情報を返さない。所属不要")
    @Operation(summary = "標準プリセット定義の一覧")
    public ResponseEntity<ApiResponse<List<EffortDefinitionSnapshot>>> listPresets() {
        return ResponseEntity.ok(ApiResponse.of(definitionService.listPresets()));
    }

    /**
     * チーム定義 snapshot の登録と definitionToken 発行（AC-19）。teamId / templateId を受け取るため
     * {@code @SelfScopedEndpoint} は付けない（API 契約 §3 末尾）。認可（チームへの現在所属）は
     * {@link EffortDefinitionService#registerTeamSnapshot} が {@code EffortTeamAccessGuard} で行う。
     */
    @PostMapping("/definition-snapshots")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "個人の頑張り記録でチーム定義を選ぶための定義照会。チーム所属の認可だけで提供し機能フラグに依存させない")
    @Operation(summary = "チーム定義 snapshot の登録（10 分有効 token）")
    public ResponseEntity<ApiResponse<EffortDefinitionTokenResponse>> registerDefinitionSnapshot(
            @Valid @RequestBody CreateDefinitionSnapshotRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(definitionService.registerTeamSnapshot(userId, request)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SelfScopedEndpoint(
            "本人の記録のみを対象とし、owner は SecurityUtils.getCurrentUserId() で確定する。"
                    + "Repository は ownerUserId を必須条件に引き、他人・不在は EFFORT_001 で秘匿する")
    @Operation(summary = "原本の作成")
    public ResponseEntity<ApiResponse<EffortOwnerRecordResponse>> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateEffortRecordRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        EffortOwnerRecordResponse created = ownerService.create(userId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(created));
    }

    @GetMapping
    @SelfScopedEndpoint(
            "本人の記録のみを対象とし、owner は SecurityUtils.getCurrentUserId() で確定する。"
                    + "Repository は ownerUserId を必須条件に引き、他人・不在は EFFORT_001 で秘匿する")
    @Operation(summary = "原本の一覧（カーソルページング）")
    public ResponseEntity<CursorPagedResponse<EffortOwnerRecordResponse>> list(
            @RequestParam(name = "date_from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(name = "date_to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(name = "definition_uuid", required = false) UUID definitionUuid,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            HttpServletRequest request) {
        rejectUnknownQuery(request, LIST_QUERY);
        Long userId = SecurityUtils.getCurrentUserId();
        EffortRecordPage page = ownerService.list(userId,
                new EffortRecordListCriteria(dateFrom, dateTo, definitionUuid, cursor, limit));
        return ResponseEntity.ok(CursorPagedResponse.of(page.items(),
                new CursorPagedResponse.CursorMeta(page.nextCursor(), page.hasNext(), page.limit())));
    }

    @GetMapping("/stats")
    @SelfScopedEndpoint(
            "本人の記録のみを対象とし、owner は SecurityUtils.getCurrentUserId() で確定する。"
                    + "Repository は ownerUserId を必須条件に引き、他人・不在は EFFORT_001 で秘匿する")
    @Operation(summary = "本人の集計（BAR/LINE/PIE）")
    public ResponseEntity<ApiResponse<EffortStatsResponse>> stats(
            @RequestParam(name = "date_from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(name = "date_to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(name = "definition_uuid") UUID definitionUuid,
            @RequestParam(name = "field_key") String fieldKey,
            @RequestParam(name = "chart") EffortChartType chart,
            @RequestParam(name = "grain", required = false) EffortGrain grain,
            HttpServletRequest request) {
        rejectUnknownQuery(request, STATS_QUERY);
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(statsService.aggregate(userId,
                new EffortStatsCriteria(dateFrom, dateTo, definitionUuid, fieldKey, chart, grain))));
    }

    @GetMapping("/{id}")
    @SelfScopedEndpoint(
            "本人の記録のみを対象とし、owner は SecurityUtils.getCurrentUserId() で確定する。"
                    + "Repository は ownerUserId を必須条件に引き、他人・不在は EFFORT_001 で秘匿する")
    @Operation(summary = "原本の詳細")
    public ResponseEntity<ApiResponse<EffortOwnerRecordResponse>> get(@PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(ownerService.get(userId, id)));
    }

    @PutMapping("/{id}")
    @SelfScopedEndpoint(
            "本人の記録のみを対象とし、owner は SecurityUtils.getCurrentUserId() で確定する。"
                    + "Repository は ownerUserId を必須条件に引き、他人・不在は EFFORT_001 で秘匿する")
    @Operation(summary = "原本の更新（全置換）")
    public ResponseEntity<ApiResponse<EffortOwnerRecordResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEffortRecordRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(ownerService.update(userId, id, request)));
    }

    @DeleteMapping("/{id}")
    @SelfScopedEndpoint(
            "本人の記録のみを対象とし、owner は SecurityUtils.getCurrentUserId() で確定する。"
                    + "Repository は ownerUserId を必須条件に引き、他人・不在は EFFORT_001 で秘匿する")
    @Operation(summary = "原本の削除")
    public ResponseEntity<ApiResponse<EffortDeleteResponse>> delete(
            @PathVariable UUID id,
            @RequestParam(name = "version") long version,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request) {
        rejectUnknownQuery(request, DELETE_QUERY);
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(ownerService.delete(userId, id, version, idempotencyKey)));
    }

    /** 未知の query は 400（値は応答・ログへ出さない）。 */
    private static void rejectUnknownQuery(HttpServletRequest request, Set<String> allowed) {
        for (String name : request.getParameterMap().keySet()) {
            if (!allowed.contains(name)) {
                throw new BusinessException(CommonErrorCode.COMMON_001,
                        List.of(new ErrorResponse.FieldError("query", "許可されていない query です")));
            }
        }
    }
}
