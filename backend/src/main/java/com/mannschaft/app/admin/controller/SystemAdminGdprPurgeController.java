package com.mannschaft.app.admin.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.gdpr.dto.PurgeStatusRow;
import com.mannschaft.app.gdpr.dto.PurgeStatusSummaryData;
import com.mannschaft.app.gdpr.dto.RetryResultResponse;
import com.mannschaft.app.gdpr.service.GdprPurgeRetryService;
import com.mannschaft.app.gdpr.service.GdprPurgeStatusQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * システム管理者向け GDPR パージ状況管理コントローラー（Phase E/F）。
 *
 * <p>{@code /api/v1/system-admin/gdpr/**} 配下のエンドポイントを提供する。
 * SecurityConfig で {@code SYSTEM_ADMIN} ロール限定に設定されているため、
 * Controller 側でのロールチェック再記述は不要（既存 SystemAdmin Controller の慣習に従う）。</p>
 *
 * <h2>提供エンドポイント</h2>
 * <ul>
 *   <li>{@code GET /api/v1/system-admin/gdpr/purge-status} — 一覧取得（ページネーション + 動的フィルタ）</li>
 *   <li>{@code GET /api/v1/system-admin/gdpr/purge-status/summary} — サマリー集計</li>
 *   <li>{@code GET /api/v1/system-admin/gdpr/purge-status/{userId}} — ユーザー詳細</li>
 *   <li>{@code GET /api/v1/system-admin/gdpr/purge-status/export.csv} — CSV エクスポート</li>
 *   <li>{@code POST /api/v1/system-admin/gdpr/purge-status/{userId}/retry/{domainName}} — 手動 retry（Phase F）</li>
 * </ul>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md} §4 Phase E / Phase F</p>
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 5 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは、{@code SecurityConfig} のパス単位認可により
 * SYSTEM_ADMIN ロール保持者のみへ宣言的に予約されている。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig の requestMatchers("/api/v1/system-admin/gdpr/**").hasRole("SYSTEM_ADMIN")（併せて
 * requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN") でも二重に予約される）
 * </p>
 *
 * <p>Controller / Service 側に認可コードは存在しないが、フィルタチェーンで強制されるため
 * 無認可ではない。認可根治戦役 Wave5 監査済。パス定義を変更・削除する際は本注釈の根拠が
 * 失効するため、必ず併せて見直すこと。</p>
 */
@AuthorizedByPathConfig({"/api/v1/system-admin/gdpr/**", "/api/v1/system-admin/**"})
@Slf4j
@RestController
@RequestMapping("/api/v1/system-admin/gdpr/purge-status")
@Tag(name = "システム管理 - GDPR パージ状況", description = "GDPR パージ状況管理 API（Phase E 読み取り + Phase F retry）")
@RequiredArgsConstructor
public class SystemAdminGdprPurgeController {

    private final GdprPurgeStatusQueryService queryService;
    private final GdprPurgeRetryService retryService;

    /**
     * GDPR パージ状況一覧を取得する（ページネーション + 動的フィルタ）。
     *
     * <p>全クエリパラメータは省略可能。省略時は全件対象。</p>
     *
     * @param status   ステータスフィルタ（PENDING / SUCCESS）
     * @param domain   ドメイン名フィルタ（role / team / payment / chart / proxy / errorreport）
     * @param dateFrom attemptedAt の開始日時（ISO 8601 形式: {@code yyyy-MM-dd'T'HH:mm:ss}）
     * @param dateTo   attemptedAt の終了日時（ISO 8601 形式: {@code yyyy-MM-dd'T'HH:mm:ss}）
     * @param page     ページ番号（0 始まり、既定: 0）
     * @param size     1 ページあたり件数（既定: 20）
     * @return ページネーション済みの GDPR パージ状況リスト
     */
    @GetMapping
    @Operation(summary = "GDPR パージ状況一覧取得", description = "status/domain/dateFrom/dateTo でフィルタ可能。全て省略で全件対象。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<Page<PurgeStatusRow>>> listPurgeStatus(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("attemptedAt").descending());
        Page<PurgeStatusRow> result = queryService.list(status, domain, dateFrom, dateTo, pageable);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * GDPR パージ状況サマリーを取得する。
     *
     * <p>ドメイン別の PENDING / SUCCESS 集計と GDPR Art.17 監視アラート件数（PENDING かつ 30 分超過）を返す。</p>
     *
     * @return サマリーデータ
     */
    @GetMapping("/summary")
    @Operation(summary = "GDPR パージ状況サマリー取得", description = "ドメイン別集計とアラート件数を返す。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<PurgeStatusSummaryData>> getSummary() {
        PurgeStatusSummaryData summary = queryService.summary();
        return ResponseEntity.ok(ApiResponse.of(summary));
    }

    /**
     * 指定ユーザーの GDPR パージ状況詳細を取得する。
     *
     * @param userId 対象ユーザー ID
     * @return 対象ユーザーの全 per-domain レコード（ドメイン名昇順）
     */
    @GetMapping("/{userId}")
    @Operation(summary = "ユーザー別 GDPR パージ状況詳細取得", description = "userId に紐づく全ドメインの消去状況を返す。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PurgeStatusRow>>> getUserDetail(
            @PathVariable Long userId) {
        List<PurgeStatusRow> rows = queryService.detail(userId);
        return ResponseEntity.ok(ApiResponse.of(rows));
    }

    /**
     * GDPR パージ状況を CSV でエクスポートする（全件ストリーミング出力）。
     *
     * <p>UTF-8 BOM 付きで出力し、Excel 等での文字化けを防ぐ。<br>
     * CSV 列: userId, emailHash, domainName, status, attemptedAt, completedAt, isAlert</p>
     *
     * @return StreamingResponseBody として CSV データを返す
     */
    @GetMapping("/export.csv")
    @Operation(summary = "GDPR パージ状況 CSV エクスポート", description = "全件を CSV 形式でダウンロード。UTF-8 BOM 付き。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "CSV エクスポート成功")
    public ResponseEntity<StreamingResponseBody> exportCsv() {
        String filename = "gdpr-purge-status-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".csv";

        StreamingResponseBody body = outputStream -> {
            try {
                queryService.writeCsv(outputStream);
            } catch (Exception e) {
                log.error("GDPR パージ状況 CSV エクスポートでエラーが発生しました", e);
                throw e;
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.setContentDispositionFormData("attachment", filename);

        return ResponseEntity.ok()
                .headers(headers)
                .body(body);
    }

    /**
     * 指定ユーザー × ドメインの GDPR パージを手動 retry する（Phase F）。
     *
     * <p>PENDING 状態のドメインパージを再実行する。既に SUCCESS の場合は即座に返す。
     * retry_count と last_retried_at は成功・失敗いずれの場合も必ず更新する。</p>
     *
     * @param userId     retry 対象ユーザー ID
     * @param domainName retry 対象ドメイン（role / team / payment / chart / proxy / errorreport）
     * @return retry 結果（succeeded=true の場合は SUCCESS に遷移、false の場合は PENDING 継続）
     */
    @PostMapping("/{userId}/retry/{domainName}")
    @Operation(
            summary = "GDPR パージ手動 retry",
            description = "PENDING 状態のドメインパージを手動で再実行する。SYSTEM_ADMIN 限定。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "retry 実行（succeeded フラグで成否を判別）")
    public ResponseEntity<ApiResponse<RetryResultResponse>> retryDomainPurge(
            @PathVariable Long userId,
            @PathVariable String domainName) {
        RetryResultResponse result = retryService.retryDomainPurge(userId, domainName);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
