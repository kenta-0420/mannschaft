package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.reservation.ReservationBlockedResourceType;
import com.mannschaft.app.reservation.dto.BlockedTimeImpactResponse;
import com.mannschaft.app.reservation.dto.BlockedTimeRequest;
import com.mannschaft.app.reservation.dto.BlockedTimeResponse;
import com.mannschaft.app.reservation.dto.BusinessHourResponse;
import com.mannschaft.app.reservation.dto.BusinessHoursSaveResponse;
import com.mannschaft.app.reservation.dto.BusinessHoursUpdateOutcome;
import com.mannschaft.app.reservation.dto.BusinessHoursUpdateRequest;
import com.mannschaft.app.reservation.dto.GenerateSlotsResponse;
import com.mannschaft.app.reservation.dto.ReservationSettingsResponse;
import com.mannschaft.app.reservation.dto.SlotGenerationResultDto;
import com.mannschaft.app.reservation.dto.UpdateReservationSettingRequest;
import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity;
import com.mannschaft.app.reservation.service.ReservationBusinessHourService;
import com.mannschaft.app.reservation.service.ReservationPolicyService;
import com.mannschaft.app.reservation.service.ReservationSlotTemplateService;
import com.mannschaft.app.reservation.service.ReservationTeamSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 予約営業時間コントローラー。営業時間・ブロック時間・設定管理APIを提供する。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/teams/{teamId}/reservation-settings")
@Tag(name = "予約設定管理", description = "F03.4 営業時間・ブロック時間・設定管理")
@RequiredArgsConstructor
public class ReservationBusinessHourController {

    private final ReservationBusinessHourService businessHourService;
    private final ReservationTeamSettingService teamSettingService;
    private final ReservationPolicyService policyService;
    /** 営業時間変更差分の同期自動生成用（保存 tx 外側で呼ぶ・F03.4.5 §3.2）。 */
    private final ReservationSlotTemplateService templateService;


    /**
     * 営業時間設定を取得する。
     */
    @GetMapping("/business-hours")
    @Operation(summary = "営業時間取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<BusinessHourResponse>>> getBusinessHours(
            @PathVariable Long teamId) {
        List<BusinessHourResponse> hours = businessHourService.getBusinessHours(teamId);
        return ResponseEntity.ok(ApiResponse.of(hours));
    }

    /**
     * 営業時間設定を一括更新し、<b>変更のあった曜日の active テンプレ</b>を horizon 28 日まで
     * 同期自動生成する（F03.4.5 §3.2）。応答は {@link BusinessHoursSaveResponse}（営業時間＋生成カウント）。
     *
     * <p>保存（{@code @Transactional} 内でコミット）→ その外側で生成、の順で実行する。営業時間の拡大で
     * {@code skippedOutsideHoursCount} に落ちていたセルが自動的に埋まる。生成が失敗しても営業時間の保存は
     * 成立済みのため HTTP 200 で返し、{@code generation.failed=true} で正直に報告する。GET は不変
     * （{@code BusinessHourResponse[]}）で消費者 {@code ReservationUnavailabilityManager} に影響しない。</p>
     */
    @PutMapping("/business-hours")
    @Operation(summary = "営業時間一括更新（保存＝変更曜日の同期自動生成）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<BusinessHoursSaveResponse>> updateBusinessHours(
            @PathVariable Long teamId,
            @Valid @RequestBody BusinessHoursUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        // ① 保存 tx をコミットさせる（@Transactional 内）＋変更曜日を検出
        BusinessHoursUpdateOutcome outcome = businessHourService.updateBusinessHours(teamId, request);
        // ② 保存 tx コミット後・@Transactional の外側で変更曜日差分を同期生成（§3.2 の tx 境界）
        SlotGenerationResultDto generation = generateForChangedDays(teamId, outcome, userId);
        return ResponseEntity.ok(ApiResponse.of(new BusinessHoursSaveResponse(outcome.hours(), generation)));
    }

    /**
     * 変更曜日の同期自動生成を実行し、結果を包む（§3.2）。生成失敗は保存を壊さず {@code failed=true} で
     * 正直に報告する（握りつぶさず {@code log.error}・翌朝の日次バッチが自己修復）。
     */
    private SlotGenerationResultDto generateForChangedDays(
            Long teamId, BusinessHoursUpdateOutcome outcome, Long userId) {
        try {
            GenerateSlotsResponse generation =
                    templateService.generateForDaysOfWeek(teamId, outcome.changedDays(), userId);
            return SlotGenerationResultDto.of(generation);
        } catch (Exception e) {
            log.error("営業時間保存後の同期自動生成に失敗（保存は成立・翌日次バッチが自己修復）: "
                    + "teamId={}, changedDays={}", teamId, outcome.changedDays(), e);
            return SlotGenerationResultDto.ofFailure();
        }
    }

    /**
     * ブロック時間一覧を取得する。
     */
    @GetMapping("/blocked-times")
    @Operation(summary = "ブロック時間一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<BlockedTimeResponse>>> listBlockedTimes(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<BlockedTimeResponse> blockedTimes = businessHourService.listBlockedTimes(teamId, from, to);
        return ResponseEntity.ok(ApiResponse.of(blockedTimes));
    }

    /**
     * ブロック時間を作成する。
     */
    @PostMapping("/blocked-times")
    @Operation(summary = "ブロック時間作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<BlockedTimeResponse>> createBlockedTime(
            @PathVariable Long teamId,
            @Valid @RequestBody BlockedTimeRequest request) {
        BlockedTimeResponse response = businessHourService.createBlockedTime(teamId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * ブロック時間を更新する。
     */
    @PatchMapping("/blocked-times/{blockedId}")
    @Operation(summary = "ブロック時間更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<BlockedTimeResponse>> updateBlockedTime(
            @PathVariable Long teamId,
            @PathVariable Long blockedId,
            @Valid @RequestBody BlockedTimeRequest request) {
        BlockedTimeResponse response = businessHourService.updateBlockedTime(teamId, blockedId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 予約不可枠 登録前の影響プレビュー（機能B・§4.B）。
     *
     * <p>overlap する既存 active 予約（PENDING/CONFIRMED）の件数＋一覧（管理用・氏名込み）を返す。
     * 副作用ゼロ。ADMIN + DEPUTY_ADMIN（副管理者）許可。</p>
     */
    @GetMapping("/blocked-times/impact")
    @Operation(summary = "予約不可枠 登録前の影響プレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<BlockedTimeImpactResponse>> getBlockedTimeImpact(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "TEAM") ReservationBlockedResourceType resourceType,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime) {
        BlockedTimeImpactResponse response = businessHourService.getBlockedTimeImpact(
                teamId, date, resourceType, resourceId, startTime, endTime);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ブロック時間を削除する。
     */
    @DeleteMapping("/blocked-times/{blockedId}")
    @Operation(summary = "ブロック時間削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<Void> deleteBlockedTime(
            @PathVariable Long teamId,
            @PathVariable Long blockedId) {
        businessHourService.deleteBlockedTime(teamId, blockedId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 予約設定（チームポリシー）を取得する。
     *
     * <p>2 つの別テーブル（{@code reservation_team_settings}・{@code reservation_policies}）を
     * 1 レスポンスに統合して返す。policy レコードが存在しないチームは既定値（AUTO / 24 / "24,1"）を、
     * team_setting レコードが存在しないチームは既定値（allowPublicReservation=false /
     * resourceNameType=DEFAULT / resourceNameCustom=null・F03.4.5 §5）を返す。</p>
     */
    @GetMapping
    @Operation(summary = "予約設定（チームポリシー）取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ReservationSettingsResponse>> getSettings(
            @PathVariable Long teamId) {
        boolean hasBusinessHours = businessHourService.hasBusinessHours(teamId);
        ReservationTeamSettingEntity teamSetting = teamSettingService.getOrDefault(teamId);
        ReservationPolicyEntity policy = policyService.getOrDefault(teamId);
        ReservationSettingsResponse settings = ReservationSettingsResponse.builder()
                .teamId(teamId)
                .hasBusinessHours(hasBusinessHours)
                .allowPublicReservation(teamSetting.isAllowPublicReservation())
                .approvalMode(policy.getApprovalMode())
                .cancelDeadlineHours(policy.getCancelDeadlineHours())
                .remindBeforeHours(policy.getRemindBeforeHours())
                .resourceNameType(teamSetting.getResourceNameType())
                .resourceNameCustom(teamSetting.getResourceNameCustom())
                .build();
        return ResponseEntity.ok(ApiResponse.of(settings));
    }

    /**
     * 予約設定（チームポリシー）を更新する。管理者・副管理者（ADMIN + DEPUTY_ADMIN）限定。
     *
     * <p>PATCH の部分更新セマンティクス: null フィールドは据え置き。</p>
     *
     * <ul>
     *   <li>{@code allowPublicReservation} … {@code reservation_team_settings} を更新。
     *       true にするとログイン済みであればチーム所属者でなくても予約できるようになる（裏設定）。</li>
     *   <li>{@code approvalMode} / {@code cancelDeadlineHours} / {@code remindBeforeHours}
     *       … {@code reservation_policies} を upsert 更新。</li>
     *   <li>{@code resourceNameType} / {@code resourceNameCustom} … {@code reservation_team_settings}
     *       の呼称カラムを upsert 更新（F03.4.5 §5）。CUSTOM 必須検証・CUSTOM 以外への NULL 正規化は
     *       {@code ReservationTeamSettingService#updateResourceName} が担う。</li>
     * </ul>
     */
    @PatchMapping
    @Operation(summary = "予約設定（チームポリシー）の更新（管理者・副管理者限定）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<ReservationSettingsResponse>> updateReservationSetting(
            @PathVariable Long teamId,
            @Valid @RequestBody UpdateReservationSettingRequest request) {
        // allow_public_reservation（別テーブル）の更新。null は据え置き。
        if (request.getAllowPublicReservation() != null) {
            teamSettingService.updateAllowPublic(teamId, request.getAllowPublicReservation());
        }
        // reservation_policies（別テーブル）の upsert。全て null なら据え置き（既存レコードに影響なし）。
        if (request.getApprovalMode() != null
                || request.getCancelDeadlineHours() != null
                || request.getRemindBeforeHours() != null) {
            policyService.updatePolicy(
                    teamId,
                    request.getApprovalMode(),
                    request.getCancelDeadlineHours(),
                    request.getRemindBeforeHours());
        }
        // 予約対象の呼称（reservation_team_settings）の upsert。両方 null なら据え置き（F03.4.5 §5）。
        if (request.getResourceNameType() != null || request.getResourceNameCustom() != null) {
            teamSettingService.updateResourceName(
                    teamId, request.getResourceNameType(), request.getResourceNameCustom());
        }
        // 更新後の統合状態を返す（GET と同形）。
        return getSettings(teamId);
    }
}
