package com.mannschaft.app.moderation.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.moderation.ModerationMapper;
import com.mannschaft.app.moderation.dto.ReportResponse;
import com.mannschaft.app.moderation.entity.ContentReportEntity;
import com.mannschaft.app.moderation.service.ReportActionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * システム管理者向け全通報一覧コントローラー。
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 1 EP）</b>:
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
@RequestMapping("/api/v1/system-admin/reports")
@Tag(name = "システム管理 - 通報", description = "F10.1 システム管理者向け通報一覧")
@RequiredArgsConstructor
public class SystemAdminReportController {

    private final ReportActionService reportActionService;
    private final ModerationMapper moderationMapper;

    /**
     * 全通報一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "全通報一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ReportResponse>> getAllReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ContentReportEntity> reportPage = reportActionService.getAllReports(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<ReportResponse> responses = moderationMapper.toReportResponseList(reportPage.getContent());
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                reportPage.getTotalElements(), page, size, reportPage.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(responses, meta));
    }
}
