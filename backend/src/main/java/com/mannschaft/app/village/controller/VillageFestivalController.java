package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.village.dto.FestivalCreateRequest;
import com.mannschaft.app.village.dto.FestivalResponse;
import com.mannschaft.app.village.dto.FestivalUpdateRequest;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
import com.mannschaft.app.village.service.VillageFestivalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * F17.1 Phase 2 U8 — 村お祭り Controller（設計書 §2.2 / §13.2）。
 *
 * <p>期間付き notice として動作するお祭りエンティティの CRUD を提供する。
 * 状態遷移（SCHEDULED → ACTIVE → ENDED）は別バッチが自動更新し、本 Controller では
 * 作成・更新・中止・取得のみ扱う。認可・整合性検証は {@link VillageFestivalService} 側で完結。</p>
 *
 * <h2>認可</h2>
 * <p>全エンドポイントに {@code @PreAuthorize("isAuthenticated()")} を付与し、未認証を弾く。
 * そのうえで村スコープの実効認可は Service（public 入口）で行う:</p>
 * <ul>
 *   <li>作成・更新・中止: 村ロール HEADMAN / ELDER を {@code requireHeadmanOrElder} で検証</li>
 *   <li>一覧・詳細: 村掲示板と同一の閲覧認可を
 *       {@code VillageBulletinAccessService.checkVillageBulletinViewAccess} へ委譲する
 *       （村の {@code bulletin_visibility} と村メンバーシップで判定）</li>
 * </ul>
 * <p>認可ガードを Controller ではなく Service の public 入口へ置くのは、バッチ等の別経路から
 * 呼ばれても認可が抜けないようにするため（村史 Controller と同じ方針）。</p>
 *
 * <p>担当 API:</p>
 * <ul>
 *   <li>{@code POST   /api/v1/villages/{villageId}/festivals} — 作成（HEADMAN / ELDER）</li>
 *   <li>{@code GET    /api/v1/villages/{villageId}/festivals?status=} — 一覧</li>
 *   <li>{@code GET    /api/v1/villages/{villageId}/festivals/{festivalId}} — 詳細</li>
 *   <li>{@code PATCH  /api/v1/villages/{villageId}/festivals/{festivalId}} — 更新（HEADMAN / ELDER）</li>
 *   <li>{@code POST   /api/v1/villages/{villageId}/festivals/{festivalId}/cancel} — 中止（HEADMAN / ELDER）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/festivals")
@Tag(name = "村お祭り (F17.1)",
     description = "Phase 2 U5/U8: 期間付きお祭り（SCHEDULED → ACTIVE → ENDED）の CRUD と中止")
@RequiredArgsConstructor
public class VillageFestivalController {

    /** デフォルトページサイズ（Service 側の上限 100 と同期）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final VillageFestivalService festivalService;

    /**
     * 村のお祭りを作成する（HEADMAN / ELDER のみ）。
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村のお祭りを作成する（HEADMAN / ELDER のみ）")
    public ResponseEntity<ApiResponse<FestivalResponse>> create(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody FestivalCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        FestivalResponse response = festivalService.createFestival(villageId, request, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 村のお祭り一覧を取得する。
     * {@code status} 指定時はその状態のみ。未指定なら全状態を開始日時降順で返す。
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村のお祭り一覧を取得する")
    public ApiResponse<List<FestivalResponse>> list(
            @PathVariable("villageId") UUID villageId,
            @RequestParam(name = "status", required = false) VillageFestivalStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                size <= 0 ? DEFAULT_PAGE_SIZE : size,
                Sort.by(Sort.Direction.DESC, "startsAt"));
        Long actorUserId = SecurityUtils.getCurrentUserId();
        List<FestivalResponse> list = festivalService.listFestivals(villageId, status, pageable, actorUserId);
        return ApiResponse.of(list);
    }

    /**
     * 村のお祭り詳細を取得する。
     */
    @GetMapping("/{festivalId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村のお祭り詳細を取得する")
    public ApiResponse<FestivalResponse> get(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("festivalId") UUID festivalId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(festivalService.getFestival(villageId, festivalId, actorUserId));
    }

    /**
     * 村のお祭りを部分更新する（HEADMAN / ELDER のみ）。
     */
    @PatchMapping("/{festivalId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村のお祭りを部分更新する（HEADMAN / ELDER のみ）")
    public ApiResponse<FestivalResponse> update(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("festivalId") UUID festivalId,
            @Valid @RequestBody FestivalUpdateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        FestivalResponse response = festivalService.updateFestival(villageId, festivalId, request, actorUserId);
        return ApiResponse.of(response);
    }

    /**
     * 村のお祭りを中止する（HEADMAN / ELDER のみ）。既に ENDED/CANCELLED の場合は冪等 no-op。
     */
    @PostMapping("/{festivalId}/cancel")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村のお祭りを中止する（HEADMAN / ELDER のみ）")
    public ApiResponse<FestivalResponse> cancel(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("festivalId") UUID festivalId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        FestivalResponse response = festivalService.cancelFestival(villageId, festivalId, actorUserId);
        return ApiResponse.of(response);
    }
}
