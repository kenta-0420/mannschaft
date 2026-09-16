package com.mannschaft.app.gdpr.controller;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.gdpr.dto.DataExportRequest;
import com.mannschaft.app.gdpr.dto.DataExportResponse;
import com.mannschaft.app.gdpr.dto.DeletionPreviewResponse;
import com.mannschaft.app.gdpr.dto.UserEmailInfo;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.service.DataExportService;
import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GDPRコントローラー。データエクスポート・削除プレビューAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/account")
@Tag(name = "GDPR/個人情報管理", description = "F12.3 データエクスポート・削除プレビュー")
@RequiredArgsConstructor
public class GdprController {

    private final DataExportService dataExportService;
    private final ChartRecordRepository chartRecordRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MemberPaymentRepository memberPaymentRepository;
    private final UserRepository userRepository;
    private final com.mannschaft.app.role.service.RoleSuccessionService roleSuccessionService;

    /**
     * POST /api/v1/account/data-export
     * データエクスポートをリクエストする。
     * パスワードユーザー: password再認証 + 非同期処理開始
     * OAuthユーザー: OTPをメール送信して202を返す
     *
     * <p><b>自己スコープ</b>: 対象ユーザーは {@code SecurityUtils.getCurrentUserId()} のみから
     * 解決する。{@link DataExportRequest} はカテゴリと再認証情報だけを持ち、ユーザー識別子を
     * 一切受け取らないため、他人のエクスポートを発注する経路が構造的に存在しない。</p>
     */
    @SelfScopedEndpoint("エクスポート対象ユーザーが SecurityUtils.getCurrentUserId() に束縛される"
            + "（DataExportRequest はカテゴリと再認証情報のみでユーザー識別子を持たない）")
    @AlwaysReachable(category = AlwaysReachableCategory.PUBLIC_LIFELINE,
            reason = "本人のデータ可搬権をGate状態にかかわらず保障するため")
    @PostMapping("/data-export")
    @Operation(summary = "データエクスポートリクエスト")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "エクスポートリクエスト受付")
    public ResponseEntity<ApiResponse<DataExportResponse>> requestExport(
            @Valid @RequestBody DataExportRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        DataExportEntity entity = dataExportService.requestExport(userId, request.getCategories());
        // ユーザー情報をコントローラー層で先取り（DataExportService から auth.UserRepository を呼ばないドメイン境界設計）
        UserEmailInfo userInfo = userRepository.findById(userId)
                .map(u -> new UserEmailInfo(u.getId(), u.getEmail(), u.getLastName(), u.getFirstName()))
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_015));
        dataExportService.processExportAsync(entity.getId(), userInfo, request.getCategories());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(toResponse(entity)));
    }

    /**
     * GET /api/v1/account/data-export/status
     * エクスポートの現在ステータスを取得する。
     *
     * <p><b>自己スコープ</b>: {@code DataExportService#getExportStatus} の検索条件は
     * {@code findTopByUserIdOrderByCreatedAtDesc(userId)} のみで、その {@code userId} は
     * {@code SecurityUtils.getCurrentUserId()} 由来である。リクエストは引数を取らない。</p>
     */
    @SelfScopedEndpoint("検索条件が findTopByUserIdOrderByCreatedAtDesc(認証主体の userId) のみ"
            + "（DataExportService#getExportStatus・エンドポイントは引数を取らない）")
    @AlwaysReachable(category = AlwaysReachableCategory.PUBLIC_LIFELINE,
            reason = "本人がデータ出力処理の進捗を継続確認できるようにするため")
    @GetMapping("/data-export/status")
    @Operation(summary = "エクスポートステータス取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<DataExportResponse>> getExportStatus() {
        Long userId = SecurityUtils.getCurrentUserId();
        DataExportEntity entity = dataExportService.getExportStatus(userId);
        return ResponseEntity.ok(ApiResponse.of(toResponse(entity)));
    }

    /**
     * GET /api/v1/account/data-export/download
     * 完了済みZIPのダウンロードURLを返す。
     *
     * <p><b>自己スコープ</b>: 署名 URL を発行する対象レコードは
     * {@code findTopByUserIdOrderByCreatedAtDesc(認証主体の userId)} で引き当てる。
     * リクエストは引数を取らず、他人のエクスポート ID を指す経路が存在しない。</p>
     */
    @SelfScopedEndpoint("署名URLの対象レコードを findTopByUserIdOrderByCreatedAtDesc(認証主体の userId) で引き当てる"
            + "（DataExportService#getDownloadUrl・エンドポイントは引数を取らない）")
    @AlwaysReachable(category = AlwaysReachableCategory.PUBLIC_LIFELINE,
            reason = "完了済みの本人データ出力をGate状態にかかわらず取得可能にするため")
    @GetMapping("/data-export/download")
    @Operation(summary = "エクスポートダウンロードURL取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "URL取得成功")
    public ResponseEntity<ApiResponse<Map<String, String>>> getDownloadUrl() {
        Long userId = SecurityUtils.getCurrentUserId();
        String url = dataExportService.getDownloadUrl(userId);
        return ResponseEntity.ok(ApiResponse.of(Map.of("downloadUrl", url)));
    }

    /**
     * GET /api/v1/account/deletion-preview
     * 退会時に削除/匿名化されるデータの件数サマリーを返す。
     * 再認証不要（読み取り専用）。
     *
     * <p><b>自己スコープ</b>: 集計に用いる 3 つの件数はすべて認証主体の userId を唯一の
     * 検索キーとする（{@code countByCustomerUserId} / {@code countBySenderId} /
     * {@code findByUserId}）。エンドポイントは引数を取らない。</p>
     */
    @SelfScopedEndpoint("件数集計の検索キーが認証主体の userId のみ"
            + "（buildDeletionPreview の countByCustomerUserId / countBySenderId / findByUserId）")
    @AlwaysReachable(category = AlwaysReachableCategory.PUBLIC_LIFELINE,
            reason = "本人が退会前に削除影響を確認できる権利を保障するため")
    @GetMapping("/deletion-preview")
    @Operation(summary = "退会時削除データプレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "プレビュー取得成功")
    public ResponseEntity<ApiResponse<DeletionPreviewResponse>> getDeletionPreview() {
        Long userId = SecurityUtils.getCurrentUserId();
        DeletionPreviewResponse response = buildDeletionPreview(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // -------------------------------------------------------------------------
    // private helpers
    // -------------------------------------------------------------------------

    /**
     * DataExportEntity を DataExportResponse に変換する。
     */
    private DataExportResponse toResponse(DataExportEntity entity) {
        return DataExportResponse.builder()
                .exportId(entity.getId())
                .status(entity.getStatus())
                .progressPercent(entity.getProgressPercent())
                .currentStep(entity.getProgressStep())
                .fileSizeBytes("COMPLETED".equals(entity.getStatus()) ? entity.getFileSize() : null)
                .expiresAt("COMPLETED".equals(entity.getStatus()) ? entity.getExpiresAt() : null)
                .createdAt(entity.getCreatedAt())
                .completedAt(entity.getCompletedAt())
                .build();
    }

    /**
     * 各カテゴリの件数を取得して DeletionPreviewResponse を構築する。
     */
    private DeletionPreviewResponse buildDeletionPreview(Long userId) {
        long chartCount = chartRecordRepository.countByCustomerUserId(userId);
        long chatCount = chatMessageRepository.countBySenderId(userId);
        long paymentCount = memberPaymentRepository.findByUserId(userId).size();

        Map<String, Long> dataSummary = new LinkedHashMap<>();
        dataSummary.put("charts", chartCount);
        dataSummary.put("chatMessages", chatCount);
        dataSummary.put("payments", paymentCount);

        List<DeletionPreviewResponse.AnonymizedItem> anonymized = new ArrayList<>();
        if (chartCount > 0) {
            anonymized.add(DeletionPreviewResponse.AnonymizedItem.builder()
                    .category("charts")
                    .count(chartCount)
                    .note("カルテ情報は匿名化されてスタッフ側に残ります")
                    .build());
        }
        if (chatCount > 0) {
            anonymized.add(DeletionPreviewResponse.AnonymizedItem.builder()
                    .category("chatMessages")
                    .count(chatCount)
                    .note("チャットメッセージは論理削除されます")
                    .build());
        }

        List<String> warnings = new ArrayList<>();
        warnings.add("退会後30日以内であれば取り消しが可能です");
        warnings.add("ダウンロード済みのデータエクスポートは引き続き有効です");

        // 柱①ADMINゼロ根治 AC1/§14: 他メンバー1人以上のスコープで唯一のADMINである一覧を追加する。
        java.util.List<com.mannschaft.app.role.dto.LastAdminScope> lastAdminScopes =
                roleSuccessionService.findBlockingLastAdminScopes(userId);

        return DeletionPreviewResponse.builder()
                .retentionDays(30)
                .dataSummary(dataSummary)
                .lastAdminScopes(lastAdminScopes)
                .anonymized(anonymized)
                .warnings(warnings)
                .build();
    }
}
