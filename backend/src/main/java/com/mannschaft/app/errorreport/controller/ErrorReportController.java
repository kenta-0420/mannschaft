package com.mannschaft.app.errorreport.controller;

import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.errorreport.dto.ActiveIncidentResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportRequest;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import com.mannschaft.app.incidentbanner.service.IncidentBannerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * フロントエンドエラーレポート受信コントローラー。
 * 認証不要エンドポイント。
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 2 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig — requestMatchers(POST, "/api/v1/error-reports").permitAll()
 * / SecurityConfig — requestMatchers(GET, "/api/v1/active-incidents").permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * F12.5 フロントエンドエラー追跡。<b>未ログイン画面（LP・ログイン）で発生したエラーも収集する必要</b>があるため受信側は公開必須。
 * {@code active-incidents} は全ユーザー共通の障害告知バナー情報のみを返し、個人データ・テナント固有データを含まない。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic({
        "/api/v1/error-reports",
        "/api/v1/active-incidents"
})
@RestController
@RequestMapping("/api/v1")
@Tag(name = "エラーレポート", description = "F12.5 フロントエンドエラー追跡API")
@RequiredArgsConstructor
public class ErrorReportController {

    private final ErrorReportService errorReportService;
    private final IncidentBannerService incidentBannerService;

    /**
     * フロントエンドからのエラーレポートを受信する。
     * 同一エラーハッシュの場合は既存レコードに集約される。
     */
    @PostMapping("/error-reports")
    @Operation(summary = "エラーレポート送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "新規作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "既存レポートに集約")
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody ErrorReportRequest request,
            HttpServletRequest httpRequest) {
        String ipAddress = httpRequest.getRemoteAddr();
        ErrorReportEntity entity = errorReportService.createOrAggregate(request, ipAddress);
        Map<String, Object> body = Map.of(
                "id", entity.getId(),
                "status", entity.getStatus().name());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * 現在公開中の障害告知バナーを取得する（permitAll）。
     *
     * <p>F12.5 転換: 旧実装はエラー自動集計からインシデントを生成していたが、
     * シスアド手動オーサリングのバナー（incidentbanner ドメイン）へ差し替えた。
     * 後方互換のためレスポンス形は既存の
     * {@code { incidents:[{ pagePattern, message, severity, since }] }} を維持する。
     * severity←banner.level、since←startsAt（無ければ createdAt 相当として startsAt）でマッピングする。</p>
     *
     * <p>言語解決: {@code ?lang=} を優先し、無ければ Accept-Language ヘッダの先頭、
     * いずれも無ければ "ja"。</p>
     */
    @GetMapping("/active-incidents")
    @Operation(summary = "アクティブインシデント（公開バナー）一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ActiveIncidentResponse> getActiveIncidents(
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {
        String language = resolveLanguage(lang, acceptLanguage);

        List<ActiveIncidentResponse.Incident> incidents =
                incidentBannerService.getActivePublic(language).stream()
                        .map(b -> ActiveIncidentResponse.Incident.builder()
                                .pagePattern(b.pagePattern())
                                .message(b.message())
                                .severity(b.level())
                                .since(b.startsAt() != null ? b.startsAt() : b.createdAt())
                                .build())
                        .toList();

        ActiveIncidentResponse response = ActiveIncidentResponse.builder()
                .incidents(incidents)
                .build();
        return ResponseEntity.ok(response);
    }

    /**
     * 表示言語を解決する。{@code lang} クエリ優先 → Accept-Language 先頭 → 既定 "ja"。
     */
    private String resolveLanguage(String lang, String acceptLanguage) {
        if (lang != null && !lang.isBlank()) {
            return normalizeLang(lang);
        }
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            // "ja,en-US;q=0.9,en;q=0.8" → 先頭の主言語サブタグを採用
            String first = acceptLanguage.split(",")[0].trim();
            String primary = first.split(";")[0].trim();
            if (!primary.isBlank()) {
                return normalizeLang(primary);
            }
        }
        return "ja";
    }

    /** "en-US" → "en" のように主言語サブタグへ正規化し小文字化する。 */
    private String normalizeLang(String value) {
        String primary = value.split("-")[0].trim().toLowerCase();
        return primary.isBlank() ? "ja" : primary;
    }
}
