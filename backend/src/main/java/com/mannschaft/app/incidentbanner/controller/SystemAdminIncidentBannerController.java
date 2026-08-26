package com.mannschaft.app.incidentbanner.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.errorreport.service.ErrorReportQueryService;
import com.mannschaft.app.incidentbanner.dto.IncidentBannerRequest;
import com.mannschaft.app.incidentbanner.dto.IncidentBannerResponse;
import com.mannschaft.app.incidentbanner.dto.IncidentSuggestionResponse;
import com.mannschaft.app.incidentbanner.entity.IncidentBannerEntity;
import com.mannschaft.app.incidentbanner.entity.IncidentBannerTranslationEntity;
import com.mannschaft.app.incidentbanner.service.IncidentBannerService;
import com.mannschaft.app.incidentbanner.service.IncidentBannerTranslationOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * システム管理者向け障害告知バナー管理コントローラー。
 *
 * <p>{@code /api/v1/system-admin/**} 配下は SecurityConfig により自動的に
 * {@code ROLE_SYSTEM_ADMIN} が要求される。</p>
 *
 * <p>シスアドが原文（既定 ja）を手動オーサリングし、保存後に en/zh/ko/es/de へ
 * 自動翻訳される。検知候補 EP はエラーテレメトリからバナー化候補を提示する。</p>
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 8 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは、{@code SecurityConfig} のパス単位認可により
 * SYSTEM_ADMIN ロール保持者のみへ宣言的に予約されている。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig の requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")
 * </p>
 *
 * <p>Controller / Service 側に認可コードは存在しないが、フィルタチェーンで強制されるため
 * 無認可ではない。認可根治戦役 Wave5 監査済。パス定義を変更・削除する際は本注釈の根拠が
 * 失効するため、必ず併せて見直すこと。</p>
 */
@AuthorizedByPathConfig("/api/v1/system-admin/**")
@RestController
@RequestMapping("/api/v1/system-admin/incident-banners")
@Tag(name = "システム管理 - 障害告知バナー", description = "F12.5 障害告知バナー管理API（システム管理者向け）")
@RequiredArgsConstructor
public class SystemAdminIncidentBannerController {

    private static final String DEFAULT_ORIGINAL_LANGUAGE = "ja";
    private static final String DEFAULT_PAGE_PATTERN = "*";

    private final IncidentBannerService bannerService;
    private final IncidentBannerTranslationOrchestrator translationOrchestrator;
    private final ErrorReportQueryService errorReportQueryService;

    /**
     * バナー一覧を取得する（ページング）。
     */
    @GetMapping
    @Operation(summary = "障害告知バナー一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<IncidentBannerResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int cappedSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(page, cappedSize);
        Page<IncidentBannerEntity> result = bannerService.list(pageable);

        List<IncidentBannerResponse> data = result.getContent().stream()
                .map(this::toResponse)
                .toList();
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(data, meta));
    }

    /**
     * バナー詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "障害告知バナー詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<IncidentBannerResponse>> get(@PathVariable UUID id) {
        IncidentBannerEntity banner = bannerService.findById(id)
                .orElseThrow(() -> new com.mannschaft.app.common.BusinessException(
                        com.mannschaft.app.incidentbanner.IncidentBannerErrorCode.INCIDENT_BANNER_NOT_FOUND));
        return ResponseEntity.ok(ApiResponse.of(toResponse(banner)));
    }

    /**
     * バナーを新規作成する。原文（ja）を同期保存後、追加言語を自動翻訳する。
     */
    @PostMapping
    @Operation(summary = "障害告知バナー作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "作成成功")
    public ResponseEntity<ApiResponse<IncidentBannerResponse>> create(
            @Valid @RequestBody IncidentBannerRequest request) {
        Long adminId = SecurityUtils.getCurrentUserId();
        String originalLanguage = resolveOriginalLanguage(request.getOriginalLanguage());
        String pagePattern = resolvePagePattern(request.getPagePattern());

        IncidentBannerEntity banner = bannerService.create(
                request.getLevel(), pagePattern, originalLanguage,
                request.getStartsAt(), request.getEndsAt(), adminId);

        // 原文（ja）は確実に1言語入るよう同期保存する。
        bannerService.upsertTranslation(banner.getId(), originalLanguage, request.getMessage());

        // 追加言語は非同期で自動翻訳（同期PATHをブロックしない）。
        translationOrchestrator.generateAndStoreTranslations(
                banner.getId(), request.getMessage(), originalLanguage);

        return ResponseEntity.ok(ApiResponse.of(toResponse(banner)));
    }

    /**
     * バナーを更新する。原文（ja）を同期保存後、追加言語を再翻訳する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "障害告知バナー更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<IncidentBannerResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody IncidentBannerRequest request) {
        String originalLanguage = resolveOriginalLanguage(request.getOriginalLanguage());
        String pagePattern = resolvePagePattern(request.getPagePattern());

        IncidentBannerEntity banner = bannerService.update(
                id, request.getLevel(), pagePattern, originalLanguage,
                request.getStartsAt(), request.getEndsAt());

        // 原文（ja）を同期更新。
        bannerService.upsertTranslation(id, originalLanguage, request.getMessage());

        // 追加言語を非同期で再翻訳。
        translationOrchestrator.generateAndStoreTranslations(
                id, request.getMessage(), originalLanguage);

        return ResponseEntity.ok(ApiResponse.of(toResponse(banner)));
    }

    /**
     * バナーを公開する。
     */
    @PostMapping("/{id}/publish")
    @Operation(summary = "障害告知バナー公開")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "公開成功")
    public ResponseEntity<ApiResponse<IncidentBannerResponse>> publish(@PathVariable UUID id) {
        IncidentBannerEntity banner = bannerService.publish(id);
        return ResponseEntity.ok(ApiResponse.of(toResponse(banner)));
    }

    /**
     * バナーを非公開にする。
     */
    @PostMapping("/{id}/unpublish")
    @Operation(summary = "障害告知バナー非公開")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "非公開成功")
    public ResponseEntity<ApiResponse<IncidentBannerResponse>> unpublish(@PathVariable UUID id) {
        IncidentBannerEntity banner = bannerService.unpublish(id);
        return ResponseEntity.ok(ApiResponse.of(toResponse(banner)));
    }

    /**
     * バナーを論理削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "障害告知バナー削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "削除成功")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        bannerService.softDelete(id);
        return ResponseEntity.ok(ApiResponse.of(null));
    }

    /**
     * 検知候補を取得する（エラーテレメトリ由来。気づき用・公開は人が判断）。
     */
    @GetMapping("/suggestions")
    @Operation(summary = "障害告知バナー検知候補取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<IncidentSuggestionResponse>>> suggestions() {
        List<IncidentSuggestionResponse> data = errorReportQueryService.getIncidentSuggestions().stream()
                .map(s -> IncidentSuggestionResponse.builder()
                        .pagePattern(s.pagePattern())
                        .severity(s.severity())
                        .occurrenceCount(s.occurrenceCount())
                        .affectedUserCount(s.affectedUserCount())
                        .since(s.since())
                        .build())
                .toList();
        return ResponseEntity.ok(ApiResponse.of(data));
    }

    // =========================================================================
    // private ヘルパー
    // =========================================================================

    private String resolveOriginalLanguage(String requested) {
        return (requested != null && !requested.isBlank()) ? requested : DEFAULT_ORIGINAL_LANGUAGE;
    }

    private String resolvePagePattern(String requested) {
        return (requested != null && !requested.isBlank()) ? requested : DEFAULT_PAGE_PATTERN;
    }

    private IncidentBannerResponse toResponse(IncidentBannerEntity banner) {
        List<IncidentBannerResponse.TranslationDto> translations =
                bannerService.getTranslations(banner.getId()).stream()
                        .map(this::toTranslationDto)
                        .toList();

        return IncidentBannerResponse.builder()
                .id(banner.getId().toString())
                .level(banner.getLevel())
                .pagePattern(banner.getPagePattern())
                .published(banner.isPublished())
                .originalLanguage(banner.getOriginalLanguage())
                .startsAt(banner.getStartsAt())
                .endsAt(banner.getEndsAt())
                .createdBy(banner.getCreatedBy())
                .createdAt(banner.getCreatedAt())
                .updatedAt(banner.getUpdatedAt())
                .translations(translations)
                .build();
    }

    private IncidentBannerResponse.TranslationDto toTranslationDto(IncidentBannerTranslationEntity t) {
        return IncidentBannerResponse.TranslationDto.builder()
                .language(t.getLanguage())
                .message(t.getMessage())
                .build();
    }
}
