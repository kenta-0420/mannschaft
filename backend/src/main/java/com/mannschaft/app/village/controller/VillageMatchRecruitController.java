package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.MatchApplicationCreateRequest;
import com.mannschaft.app.village.dto.MatchApplicationResponse;
import com.mannschaft.app.village.dto.MatchApplicationReviewRequest;
import com.mannschaft.app.village.dto.MatchRecruitCreateRequest;
import com.mannschaft.app.village.dto.MatchRecruitListResponse;
import com.mannschaft.app.village.dto.MatchRecruitResponse;
import com.mannschaft.app.village.dto.MatchRecruitUpdateRequest;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitCategory;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitStatus;
import com.mannschaft.app.village.service.VillageMatchRecruitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * F17.1 Phase 2 U9 — 練習試合・審判募集 Controller。
 *
 * <p>担当 API（出陣指示書 §4 U9 / 設計書 §4.5）:</p>
 *
 * <h3>募集本体</h3>
 * <ul>
 *   <li>{@code POST   /api/v1/villages/{villageId}/match-recruits}                       — 作成（村人）</li>
 *   <li>{@code GET    /api/v1/villages/{villageId}/match-recruits}                       — 一覧</li>
 *   <li>{@code GET    /api/v1/villages/{villageId}/match-recruits/{recruitId}}           — 詳細（村人）</li>
 *   <li>{@code PATCH  /api/v1/villages/{villageId}/match-recruits/{recruitId}}           — 更新（投稿者本人）</li>
 *   <li>{@code POST   /api/v1/villages/{villageId}/match-recruits/{recruitId}/close}     — 締切（投稿者/HEADMAN/ELDER）</li>
 *   <li>{@code POST   /api/v1/villages/{villageId}/match-recruits/{recruitId}/fulfill}   — 成立確定</li>
 *   <li>{@code POST   /api/v1/villages/{villageId}/match-recruits/{recruitId}/cancel}    — 取消し</li>
 * </ul>
 *
 * <h3>応募</h3>
 * <ul>
 *   <li>{@code POST   .../{recruitId}/applications}                                — 応募（村人）</li>
 *   <li>{@code GET    .../{recruitId}/applications}                                — 応募一覧（投稿者/HEADMAN/ELDER）</li>
 *   <li>{@code POST   .../{recruitId}/applications/{applicationId}/withdraw}       — 自主取下げ（応募者本人）</li>
 *   <li>{@code POST   .../{recruitId}/applications/{applicationId}/review}         — 承認/却下</li>
 * </ul>
 *
 * <h3>認可ポリシー</h3>
 * <p>権限検証は全て {@link VillageMatchRecruitService} 内で完結する（HEADMAN/ELDER / 投稿者本人 /
 * 応募者本人の判定をドメイン内に閉じる）。Controller は {@link SecurityUtils#getCurrentUserId()}
 * を取り出して委譲するのみ。</p>
 *
 * <h3>原則遵守</h3>
 * <ul>
 *   <li>原則 5: {@code @Transactional} は Service 側でのみ宣言。Controller は無トランザクション。</li>
 *   <li>クエリパラメータ検証（不正カテゴリ・不正ステータス）は Controller で受けて
 *       {@link VillageErrorCode#VILLAGE_FIELD_INVALID} を投げる。</li>
 * </ul>
 *
 * <h3>認可 Wave3（村ロットA）監査済マーカー</h3>
 * <p>全 11 EP は {@link VillageMatchRecruitService} 内で現役メンバーシップ（
 * {@code findActiveByVillageIdAndSubject}）を根拠に認可判定する（{@code AuthzControllerGuardArchTest}
 * の白名簿クラスを介さない別方式）。本戦役で、判定基準を村内の標準の流儀へ揃えた箇所は次の 2 つ:</p>
 * <ul>
 *   <li>{@code list}: 一覧も詳細取得（{@code get}）と同じく村人限定とする。
 *       {@code listRecruits} が {@code ensureVillager} で現役の村人であることを検証する。</li>
 *   <li>{@code update}: 投稿者本人判定（{@code ensureAuthor}）は、{@code close}/{@code fulfill}/
 *       {@code cancel}/応募審査が使う {@code ensureRecruitReviewer}（#2284）と同一の現役性述語
 *       {@code findActiveByVillageIdAndSubject} を本人一致より先に評価する。更新系と状態遷移系は
 *       同一基準で判定される。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/match-recruits")
@Tag(name = "村練習試合・審判募集 (F17.1)",
     description = "Phase 2: 練習試合・審判募集の CRUD と応募審査")
@RequiredArgsConstructor
@AuthorizedInService
public class VillageMatchRecruitController {

    private final VillageMatchRecruitService matchRecruitService;

    // ========================================================================
    // 募集本体
    // ========================================================================

    @PostMapping
    @Operation(summary = "練習試合・審判募集を作成（村人）")
    public ResponseEntity<ApiResponse<MatchRecruitResponse>> create(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody MatchRecruitCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MatchRecruitResponse response = matchRecruitService.createRecruit(villageId, request, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping
    @Operation(summary = "練習試合・審判募集一覧（category / status / 試合日範囲フィルタ可）")
    public ResponseEntity<ApiResponse<MatchRecruitListResponse>> list(
            @PathVariable("villageId") UUID villageId,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "fromDate", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(value = "toDate", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        VillageMatchRecruitCategory categoryEnum = parseCategoryOrNull(category);
        VillageMatchRecruitStatus statusEnum = parseStatusOrNull(status);
        MatchRecruitListResponse response = matchRecruitService.listRecruits(
                villageId, categoryEnum, statusEnum, fromDate, toDate, page, size, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping("/{recruitId}")
    @Operation(summary = "練習試合・審判募集の詳細（村人のみ）")
    public ResponseEntity<ApiResponse<MatchRecruitResponse>> get(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("recruitId") UUID recruitId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MatchRecruitResponse response = matchRecruitService.getRecruit(villageId, recruitId, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PatchMapping("/{recruitId}")
    @Operation(summary = "練習試合・審判募集を更新（投稿者本人のみ）")
    public ResponseEntity<ApiResponse<MatchRecruitResponse>> update(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("recruitId") UUID recruitId,
            @Valid @RequestBody MatchRecruitUpdateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MatchRecruitResponse response = matchRecruitService.updateRecruit(
                villageId, recruitId, request, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/{recruitId}/close")
    @Operation(summary = "練習試合・審判募集を締切（投稿者本人 / HEADMAN / ELDER）")
    public ResponseEntity<ApiResponse<MatchRecruitResponse>> close(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("recruitId") UUID recruitId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MatchRecruitResponse response = matchRecruitService.closeRecruit(villageId, recruitId, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/{recruitId}/fulfill")
    @Operation(summary = "練習試合・審判募集を成立確定（投稿者本人 / HEADMAN / ELDER）")
    public ResponseEntity<ApiResponse<MatchRecruitResponse>> fulfill(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("recruitId") UUID recruitId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MatchRecruitResponse response = matchRecruitService.fulfillRecruit(villageId, recruitId, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/{recruitId}/cancel")
    @Operation(summary = "練習試合・審判募集を取消（投稿者本人 / HEADMAN / ELDER）")
    public ResponseEntity<ApiResponse<MatchRecruitResponse>> cancel(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("recruitId") UUID recruitId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MatchRecruitResponse response = matchRecruitService.cancelRecruit(villageId, recruitId, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ========================================================================
    // 応募
    // ========================================================================

    @PostMapping("/{recruitId}/applications")
    @Operation(summary = "練習試合・審判募集に応募（村人 / 投稿者本人は応募不可）")
    public ResponseEntity<ApiResponse<MatchApplicationResponse>> apply(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("recruitId") UUID recruitId,
            @RequestBody(required = false) MatchApplicationCreateRequest request) {
        Long applicantUserId = SecurityUtils.getCurrentUserId();
        MatchApplicationResponse response = matchRecruitService.applyToRecruit(
                villageId, recruitId, request, applicantUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping("/{recruitId}/applications")
    @Operation(summary = "応募一覧（投稿者本人 / HEADMAN / ELDER のみ）")
    public ResponseEntity<ApiResponse<List<MatchApplicationResponse>>> listApplications(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("recruitId") UUID recruitId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        List<MatchApplicationResponse> response = matchRecruitService.listApplications(
                villageId, recruitId, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/{recruitId}/applications/{applicationId}/withdraw")
    @Operation(summary = "応募を自主取下げ（応募者本人のみ）")
    public ResponseEntity<ApiResponse<MatchApplicationResponse>> withdraw(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("recruitId") UUID recruitId,
            @PathVariable("applicationId") UUID applicationId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MatchApplicationResponse response = matchRecruitService.withdrawApplication(
                villageId, recruitId, applicationId, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/{recruitId}/applications/{applicationId}/review")
    @Operation(summary = "応募を承認/却下（投稿者本人 / HEADMAN / ELDER）")
    public ResponseEntity<ApiResponse<MatchApplicationResponse>> review(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("recruitId") UUID recruitId,
            @PathVariable("applicationId") UUID applicationId,
            @Valid @RequestBody MatchApplicationReviewRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MatchApplicationResponse response = matchRecruitService.reviewApplication(
                villageId, recruitId, applicationId, request, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ========================================================================
    // クエリ enum パース
    // ========================================================================

    private static VillageMatchRecruitCategory parseCategoryOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return VillageMatchRecruitCategory.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID);
        }
    }

    private static VillageMatchRecruitStatus parseStatusOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return VillageMatchRecruitStatus.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID);
        }
    }
}
