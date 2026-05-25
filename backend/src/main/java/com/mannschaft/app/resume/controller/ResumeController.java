package com.mannschaft.app.resume.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.resume.dto.ResumeDetailResponse;
import com.mannschaft.app.resume.dto.ResumeFullSaveRequest;
import com.mannschaft.app.resume.dto.ResumeHeaderPatchRequest;
import com.mannschaft.app.resume.dto.ResumeSummaryResponse;
import com.mannschaft.app.resume.service.ResumeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
@Tag(name = "履歴書・職務経歴書", description = "F01.10 履歴書バージョン CRUD・複製")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;

    /**
     * 1. 履歴書バージョン一覧取得。
     * GET /api/v1/resumes
     */
    @GetMapping
    @Operation(summary = "履歴書バージョン一覧取得")
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
     */
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
    @PostMapping("/{id}/duplicate")
    @Operation(summary = "履歴書バージョン複製")
    public ResponseEntity<ApiResponse<ResumeDetailResponse>> duplicateResume(
            @PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        ResumeDetailResponse result = resumeService.duplicateResume(id, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }
}
