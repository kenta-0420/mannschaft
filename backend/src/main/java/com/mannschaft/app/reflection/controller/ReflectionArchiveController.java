package com.mannschaft.app.reflection.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reflection.dto.ArchiveFolderResponse;
import com.mannschaft.app.reflection.dto.BulkArchiveRequest;
import com.mannschaft.app.reflection.dto.BulkArchiveResult;
import com.mannschaft.app.reflection.dto.ReflectionThemeResponse;
import com.mannschaft.app.reflection.service.ReflectionArchiveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F06.5 Phase 3: アーカイブ＆分類コントローラー（EP #17/#18/#21・§12.4）。
 *
 * <p>EP #19（archive）・EP #20（restore）は {@link ReflectionThemeController} に追加（{@code {id}} がテーマID）。</p>
 */
@RestController
@RequestMapping("/api/v1/me/reflections/archive")
@Tag(name = "振り返りアーカイブ", description = "F06.5 Phase 3 アーカイブ＆分類機能")
@RequiredArgsConstructor
public class ReflectionArchiveController {

    private final ReflectionArchiveService archiveService;

    /**
     * EP #17: アーカイブ済みテーマのフォルダ集計（学年×学期×教科 GROUP BY）（AC-42）。
     */
    @GetMapping("/folders")
    @Operation(summary = "アーカイブフォルダ一覧取得")
    public ResponseEntity<ApiResponse<List<ArchiveFolderResponse>>> getFolders() {
        List<ArchiveFolderResponse> result =
                archiveService.getFolders(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * EP #18: アーカイブ済みテーマ横断検索（学年/学期/教科/キーワード AND・AC-43）。
     *
     * @param archived     true=archived のみ / false=active のみ（省略時は true）
     * @param academicYear 学年度フィルタ（省略時は絞りなし）
     * @param termLabel    学期フィルタ（省略時は絞りなし）
     * @param subjectName  教科フィルタ（省略時は絞りなし）
     * @param keyword      タイトル/説明 LIKE キーワード（省略時は絞りなし）
     * @param page         ページ番号（0始まり・省略時は 0）
     * @param size         1ページサイズ（省略時は 20・上限 50）
     */
    @GetMapping("/search")
    @Operation(summary = "アーカイブテーマ横断検索")
    public ResponseEntity<ApiResponse<Page<ReflectionThemeResponse>>> search(
            @RequestParam(required = false) Boolean archived,
            @RequestParam(required = false) Integer academicYear,
            @RequestParam(required = false) String termLabel,
            @RequestParam(required = false) String subjectName,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ReflectionThemeResponse> result = archiveService.search(
                SecurityUtils.getCurrentUserId(), archived, academicYear, termLabel,
                subjectName, keyword, page, size);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * EP #21: 条件に合致するアクティブテーマを一括アーカイブする。
     */
    @PostMapping("/bulk-archive")
    @Operation(summary = "テーマ一括アーカイブ")
    public ResponseEntity<ApiResponse<BulkArchiveResult>> bulkArchive(
            @Valid @RequestBody BulkArchiveRequest request) {
        BulkArchiveResult result =
                archiveService.bulkArchive(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
