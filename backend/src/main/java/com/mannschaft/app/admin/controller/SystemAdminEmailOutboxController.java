package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.EmailOutboxDetailResponse;
import com.mannschaft.app.admin.dto.EmailOutboxMetricsResponse;
import com.mannschaft.app.admin.dto.EmailOutboxSummaryResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.mail.outbox.EmailOutboxAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * F09.18 Phase 18-d: SYSTEM_ADMIN 向けメール outbox 管理コントローラー。
 *
 * <p>{@code /api/v1/system-admin/**} は SecurityConfig で SYSTEM_ADMIN ロールに制限済み。
 * 本コントローラーでは @PreAuthorize を重複付与しない（既存 SystemAdmin コントローラーの慣習に準拠）。</p>
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 5 EP）</b>:
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
@RequestMapping("/api/v1/system-admin/email-outbox")
@Tag(name = "システム管理 - メール outbox", description = "F09.18 Phase 18-d メール配信 outbox 管理")
@RequiredArgsConstructor
public class SystemAdminEmailOutboxController {

    private final EmailOutboxAdminService emailOutboxAdminService;

    /**
     * outbox 一覧取得（フィルタリング対応）。
     *
     * @param status       ステータスフィルター（null=全件）
     * @param sourceDomain 送信元ドメインフィルター（null=全件）
     * @param fromDate     作成日時の下限（null=制限なし）
     * @param toDate       作成日時の上限（null=制限なし）
     */
    @GetMapping
    @Operation(summary = "メール outbox 一覧取得", description = "フィルタリング・ページング対応。PII は含まない。")
    public ResponseEntity<ApiResponse<Page<EmailOutboxSummaryResponse>>> listOutbox(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceDomain,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            Pageable pageable) {
        Page<EmailOutboxSummaryResponse> page =
                emailOutboxAdminService.listOutbox(status, sourceDomain, fromDate, toDate, pageable);
        return ResponseEntity.ok(ApiResponse.of(page));
    }

    /**
     * outbox メトリクス取得。
     *
     * <p>/{id} より先にマッピングしないと Spring が "metrics" を UUID として解釈する。</p>
     */
    @GetMapping("/metrics")
    @Operation(summary = "メール outbox メトリクス取得", description = "キューの深さ・24h 成功率・最古 PENDING 経過秒を返す。")
    public ResponseEntity<ApiResponse<EmailOutboxMetricsResponse>> getMetrics() {
        return ResponseEntity.ok(ApiResponse.of(emailOutboxAdminService.getMetrics()));
    }

    /**
     * outbox 詳細取得（PII 含む）。閲覧時に監査ログを記録する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "メール outbox 詳細取得", description = "to_address / payload を復号して返す。閲覧ログを記録する。")
    public ResponseEntity<ApiResponse<EmailOutboxDetailResponse>> getDetail(
            @PathVariable UUID id, HttpServletRequest request) {
        try {
            Long operatorId = SecurityUtils.getCurrentUserId();
            String ip = request.getRemoteAddr();
            String ua = request.getHeader("User-Agent");
            EmailOutboxDetailResponse response =
                    emailOutboxAdminService.getOutboxDetail(id, operatorId, ip, ua);
            return ResponseEntity.ok(ApiResponse.of(response));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * DEAD_LETTER をリトライキューに戻す。
     */
    @PostMapping("/{id}/retry")
    @Operation(summary = "DEAD_LETTER リトライ", description = "DEAD_LETTER 状態のエントリを PENDING に戻す。")
    public ResponseEntity<Void> retryDeadLetter(
            @PathVariable UUID id, HttpServletRequest request) {
        try {
            Long operatorId = SecurityUtils.getCurrentUserId();
            String ip = request.getRemoteAddr();
            String ua = request.getHeader("User-Agent");
            emailOutboxAdminService.retryDeadLetter(id, operatorId, ip, ua);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    /**
     * PENDING メールをキャンセルする。
     */
    @PostMapping("/{id}/cancel")
    @Operation(summary = "PENDING キャンセル", description = "PENDING 状態のエントリを CANCELLED に遷移させる。")
    public ResponseEntity<Void> cancelPending(
            @PathVariable UUID id, HttpServletRequest request) {
        try {
            Long operatorId = SecurityUtils.getCurrentUserId();
            String ip = request.getRemoteAddr();
            String ua = request.getHeader("User-Agent");
            emailOutboxAdminService.cancelPending(id, operatorId, ip, ua);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }
}
