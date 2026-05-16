package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.village.dto.ChronicleResponse;
import com.mannschaft.app.village.service.VillageChronicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * F17.1 Phase 3-β — 村史（月次ダイジェスト）Controller。
 *
 * <p>read-only API のみ提供する（生成は月初バッチ
 * {@link com.mannschaft.app.village.batch.VillageChronicleBatchService} が担当）。</p>
 *
 * <ul>
 *   <li>{@code GET /api/v1/villages/{villageId}/chronicles} — 村史一覧</li>
 *   <li>{@code GET /api/v1/villages/{villageId}/chronicles/{yearMonth}} — 月別詳細
 *       （yearMonth は ISO 形式 {@code YYYY-MM-01} を受け付ける）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/chronicles")
@Tag(name = "村史 (F17.1)",
     description = "Phase 3-β: 月次ダイジェスト（投稿数 / 新メンバー数 / TOP3 トピック）")
@RequiredArgsConstructor
public class VillageChronicleController {

    private final VillageChronicleService chronicleService;

    /**
     * 村史一覧（年月降順）を取得する。
     */
    @GetMapping
    @Operation(summary = "村の村史一覧（月次ダイジェスト）を取得する")
    public ApiResponse<List<ChronicleResponse>> list(
            @PathVariable("villageId") UUID villageId) {
        return ApiResponse.of(chronicleService.listChronicles(villageId));
    }

    /**
     * 指定月の村史を取得する。
     *
     * @param yearMonth ISO {@code YYYY-MM-DD} 形式。当該月の 1 日以外でも内部で月初へ正規化する。
     */
    @GetMapping("/{yearMonth}")
    @Operation(summary = "指定月の村史を取得する（YYYY-MM-DD 形式）")
    public ApiResponse<ChronicleResponse> get(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("yearMonth")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate yearMonth) {
        return ApiResponse.of(chronicleService.getChronicle(villageId, yearMonth));
    }
}
