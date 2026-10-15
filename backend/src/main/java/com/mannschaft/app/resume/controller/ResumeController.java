package com.mannschaft.app.resume.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.RequireFeature;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.resume.dto.ResumeDetailResponse;
import com.mannschaft.app.resume.dto.ResumeExportResponse;
import com.mannschaft.app.resume.dto.ResumeFullSaveRequest;
import com.mannschaft.app.resume.dto.ResumeHeaderPatchRequest;
import com.mannschaft.app.resume.dto.ResumeSummaryResponse;
import com.mannschaft.app.resume.service.ResumeExportService;
import com.mannschaft.app.resume.service.ResumeExportService.DocumentType;
import com.mannschaft.app.resume.service.ResumeExportService.OutputFormat;
import com.mannschaft.app.resume.service.ResumePhotoService;
import com.mannschaft.app.resume.service.ResumeService;
import com.mannschaft.app.resume.ResumeErrorCode;
import com.mannschaft.app.common.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 履歴書・職務経歴書 CRUD コントローラー（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §5
 *
 * <p>全エンドポイントで認証必須（{@code SecurityConfig} にて設定済み）。
 * 他人の履歴書 ID を指定しても 404 を返す（IDOR 対策）。
 *
 * <p><b>認可の所在（ID を受け取る全 EP 共通）</b>: 対象履歴書は必ず
 * {@code ResumeRepository#findByIdAndUserId(id, 認証主体の userId)} の複合条件で引き当てる
 * （{@code ResumeService#findResumeOwnedBy}・{@code ResumeExportService#loadResume}・
 * {@code ResumePhotoService} の各メソッド冒頭）。所有者不一致は存在しない場合と同じ
 * {@code RESUME_001} を送出し、{@code GlobalExceptionHandler} が 404 へ写像することで
 * 履歴書の存在そのものを秘匿する。引き当ては更新・削除・ファイル入出力より<b>前</b>に位置する。
 *
 * <p>Phase 3 で追加予定のエンドポイント:
 * <ul>
 *   <li>POST /api/v1/resumes/{id}/photo — 証明写真アップロード</li>
 *   <li>DELETE /api/v1/resumes/{id}/photo — 証明写真削除</li>
 *   <li>POST /api/v1/resumes/{id}/export — PDF / Excel 出力</li>
 *   <li>GET /api/v1/resumes/{id}/preview — プレビュー表示</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/resumes")
@Tag(name = "履歴書・職務経歴書", description = "F01.10 履歴書バージョン CRUD・複製・出力")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;
    private final ResumePhotoService resumePhotoService;
    private final ResumeExportService resumeExportService;

    /**
     * 1. 履歴書バージョン一覧取得。
     * GET /api/v1/resumes
     *
     * <p><b>自己スコープ</b>: 検索条件は
     * {@code ResumeRepository#findByUserIdOrderByCreatedAtDesc(認証主体の userId)} のみで、
     * エンドポイントは引数を一切取らない。</p>
     */
    @SelfScopedEndpoint("検索条件が findByUserIdOrderByCreatedAtDesc(認証主体の userId) のみ"
            + "（ResumeService#listResumes・エンドポイントは引数を取らない）")
    @GetMapping
    @Operation(summary = "履歴書バージョン一覧取得")
    @RequireFeature("FEATURE_SKILL_RESUME_ENABLED")
    public ResponseEntity<ApiResponse<List<ResumeSummaryResponse>>> listResumes() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<ResumeSummaryResponse> result = resumeService.listResumes(userId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * 2. 履歴書バージョン新規作成。
     * POST /api/v1/resumes
     *
     * <p>リクエストボディの {@code title} は任意。省略・空文字の場合は
     * Service 層でタイトルを自動採番する（「下書き YYYY-MM-DD」形式）。
     *
     * <p><b>自己スコープ</b>: 作成される {@code resumes.user_id} は
     * {@code SecurityUtils.getCurrentUserId()} で固定され、リクエストボディは {@code title} のみを
     * 受け取る（ユーザー識別子を受け取らない）。上限判定も同 userId で行う。</p>
     */
    @SelfScopedEndpoint("作成される resumes.user_id が SecurityUtils.getCurrentUserId() で固定され、"
            + "リクエストボディは title のみでユーザー識別子を受け取らない（ResumeService#createResume）")
    @PostMapping
    @Operation(summary = "履歴書バージョン新規作成")
    public ResponseEntity<ApiResponse<ResumeDetailResponse>> createResume(
            @RequestBody(required = false) Map<String, String> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        String title = body != null ? body.get("title") : null;
        ResumeDetailResponse result = resumeService.createResume(userId, title);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    /**
     * 3. 履歴書フル取得。
     * GET /api/v1/resumes/{id}
     *
     * <p>他人の ID を指定した場合は 404 を返す（IDOR 対策）。
     */
    @AuthorizedInService
    @GetMapping("/{id}")
    @Operation(summary = "履歴書フル取得（学歴・職歴・資格・スキル含む）")
    public ResponseEntity<ApiResponse<ResumeDetailResponse>> getResume(
            @PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        ResumeDetailResponse result = resumeService.getResume(id, userId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * 4. 履歴書フル一括保存。
     * PUT /api/v1/resumes/{id}
     *
     * <p>ヘッダー情報と子要素（学歴・職歴・資格・スキル）をまとめて保存する
     * 宣言的置換・冪等操作。
     * 楽観ロック競合時は 409 を返す（RESUME_010）。
     */
    @AuthorizedInService
    @PutMapping("/{id}")
    @Operation(summary = "履歴書フル一括保存（ヘッダー + 子要素）")
    public ResponseEntity<ApiResponse<ResumeDetailResponse>> saveResume(
            @PathVariable UUID id,
            @RequestBody @Valid ResumeFullSaveRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        ResumeDetailResponse result = resumeService.saveResume(id, userId, req);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * 5. 履歴書ヘッダー部分更新。
     * PATCH /api/v1/resumes/{id}
     *
     * <p>送信された非 null フィールドのみ更新する。子要素は対象外。
     */
    @AuthorizedInService
    @PatchMapping("/{id}")
    @Operation(summary = "履歴書ヘッダー部分更新")
    public ResponseEntity<ApiResponse<ResumeDetailResponse>> patchResume(
            @PathVariable UUID id,
            @RequestBody @Valid ResumeHeaderPatchRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        ResumeDetailResponse result = resumeService.patchResume(id, userId, req);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * 6. 履歴書バージョン削除（論理削除）。
     * DELETE /api/v1/resumes/{id}
     */
    @AuthorizedInService
    @DeleteMapping("/{id}")
    @Operation(summary = "履歴書バージョン削除（論理削除）")
    public ResponseEntity<Void> deleteResume(@PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        resumeService.deleteResume(id, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 7. 履歴書バージョン複製。
     * POST /api/v1/resumes/{id}/duplicate
     *
     * <p>ヘッダー情報と子要素をまとめて複製する。
     * タイトルは「{元のタイトル} (コピー)」になる。
     * 証明写真の複製は Phase 3 で対応するため、現時点では photo = null で作成する。
     */
    @AuthorizedInService
    @PostMapping("/{id}/duplicate")
    @Operation(summary = "履歴書バージョン複製")
    public ResponseEntity<ApiResponse<ResumeDetailResponse>> duplicateResume(
            @PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        ResumeDetailResponse result = resumeService.duplicateResume(id, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    // =====================================================================
    // Phase 3: 証明写真・プレビュー・出力エンドポイント
    // =====================================================================

    /**
     * 8. 証明写真アップロード。
     * POST /api/v1/resumes/{id}/photo
     *
     * <p>JPEG / PNG のみ受付。Content-Type + マジックバイト検証後に
     * 再エンコード（EXIF/GPS 除去・寸法上限 2000px）して R2 に保存する。
     *
     * @param id   対象の履歴書 ID
     * @param file アップロードファイル（フィールド名 {@code file}）
     * @return presigned URL（TTL 5 分）
     */
    @AuthorizedInService
    @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "証明写真アップロード")
    public ResponseEntity<ApiResponse<String>> uploadPhoto(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {
        Long userId = SecurityUtils.getCurrentUserId();
        String photoUrl = resumePhotoService.uploadPhoto(id, userId, file);
        return ResponseEntity.ok(ApiResponse.of(photoUrl));
    }

    /**
     * 9. 証明写真削除。
     * DELETE /api/v1/resumes/{id}/photo
     *
     * <p>R2 オブジェクトを削除し {@code photo_key} を NULL にする。
     *
     * @param id 対象の履歴書 ID
     */
    @AuthorizedInService
    @DeleteMapping("/{id}/photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "証明写真削除")
    public void deletePhoto(@PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        resumePhotoService.deletePhoto(id, userId);
    }

    /**
     * 10. プレビュー（インライン返却・R2 非経由）。
     * GET /api/v1/resumes/{id}/preview
     *
     * <p>生成した PDF / Excel バイナリを {@code Content-Disposition: inline} で直接返す。
     * R2 には保存しない。レート制限 120 回 / 時 / ユーザー（ADHD 配慮）。
     *
     * @param id     対象の履歴書 ID
     * @param type   書類種別（{@code rirekisho} / {@code shokumukeirekisho}）
     * @param format 出力形式（{@code pdf} / {@code excel}）
     * @return バイナリ本体
     */
    @AuthorizedInService
    @GetMapping("/{id}/preview")
    @Operation(summary = "プレビュー（インライン・R2 非経由）")
    public ResponseEntity<byte[]> previewResume(
            @PathVariable UUID id,
            @RequestParam String type,
            @RequestParam String format) {
        Long userId = SecurityUtils.getCurrentUserId();
        DocumentType docType = parseDocumentType(type);
        OutputFormat outputFormat = parseOutputFormat(format);
        byte[] data = resumeExportService.generatePreview(id, userId, docType, outputFormat);

        String contentType = resolveResponseContentType(outputFormat);
        String fileName = resolvePreviewFileName(docType, outputFormat);
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + fileName + "\"; filename*=UTF-8''" + encodedFileName)
                .body(data);
    }

    /**
     * 11. 正式出力（R2 永続保存・presigned URL 返却）。
     * GET /api/v1/resumes/{id}/export
     *
     * <p>生成物を R2 に保存し、presigned URL（TTL 5 分）を返す。
     * レート制限 30 回 / 時 / ユーザー。監査ログ {@code RESUME_EXPORTED} を記録。
     *
     * @param id     対象の履歴書 ID
     * @param type   書類種別（{@code rirekisho} / {@code shokumukeirekisho}）
     * @param format 出力形式（{@code pdf} / {@code excel}）
     * @return presigned URL・ファイル名・有効期限
     */
    @AuthorizedInService
    @GetMapping("/{id}/export")
    @Operation(summary = "正式出力（R2 永続保存・presigned URL 返却）")
    public ResponseEntity<ApiResponse<ResumeExportResponse>> exportResume(
            @PathVariable UUID id,
            @RequestParam String type,
            @RequestParam String format) {
        Long userId = SecurityUtils.getCurrentUserId();
        DocumentType docType = parseDocumentType(type);
        OutputFormat outputFormat = parseOutputFormat(format);
        ResumeExportResponse result = resumeExportService.exportResume(id, userId, docType, outputFormat);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部ヘルパー
    // ──────────────────────────────────────────────────────────────────────

    /**
     * {@code type} クエリパラメータを {@link DocumentType} に変換する。
     * 不正値は RESUME_005 をスロー。
     */
    private DocumentType parseDocumentType(String type) {
        if (type == null) {
            throw new BusinessException(ResumeErrorCode.RESUME_005);
        }
        return switch (type.toLowerCase()) {
            case "rirekisho"          -> DocumentType.RIREKISHO;
            case "shokumukeirekisho"  -> DocumentType.SHOKUMUKEIREKISHO;
            default -> throw new BusinessException(ResumeErrorCode.RESUME_005);
        };
    }

    /**
     * {@code format} クエリパラメータを {@link OutputFormat} に変換する。
     * 不正値は RESUME_005 をスロー。
     */
    private OutputFormat parseOutputFormat(String format) {
        if (format == null) {
            throw new BusinessException(ResumeErrorCode.RESUME_005);
        }
        return switch (format.toLowerCase()) {
            case "pdf"   -> OutputFormat.PDF;
            case "excel" -> OutputFormat.EXCEL;
            default -> throw new BusinessException(ResumeErrorCode.RESUME_005);
        };
    }

    /**
     * Content-Type ヘッダー値を返す。
     */
    private String resolveResponseContentType(OutputFormat format) {
        return (format == OutputFormat.PDF)
                ? "application/pdf"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    /**
     * プレビュー用の仮ファイル名を返す（Date なし・単純な名称）。
     */
    private String resolvePreviewFileName(DocumentType type, OutputFormat format) {
        String docLabel = (type == DocumentType.RIREKISHO) ? "履歴書" : "職務経歴書";
        String ext = (format == OutputFormat.PDF) ? ".pdf" : ".xlsx";
        return "preview_" + docLabel + ext;
    }
}
