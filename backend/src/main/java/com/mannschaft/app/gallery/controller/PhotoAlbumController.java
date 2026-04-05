package com.mannschaft.app.gallery.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.gallery.dto.AlbumResponse;
import com.mannschaft.app.gallery.dto.CreateAlbumRequest;
import com.mannschaft.app.gallery.dto.DownloadResponse;
import com.mannschaft.app.gallery.dto.PhotoResponse;
import com.mannschaft.app.gallery.dto.UpdateAlbumRequest;
import com.mannschaft.app.gallery.dto.UploadPhotosRequest;
import com.mannschaft.app.gallery.dto.UploadPhotosResponse;
import com.mannschaft.app.gallery.service.PhotoAlbumService;
import com.mannschaft.app.gallery.service.PhotoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
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

import java.time.LocalDate;
import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 写真アルバムコントローラー。アルバムのCRUD・写真アップロード・ダウンロードAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/gallery/albums")
@Tag(name = "写真アルバム", description = "F06.2 ギャラリーアルバムCRUD・写真管理")
@RequiredArgsConstructor
public class PhotoAlbumController {

    private final PhotoAlbumService albumService;
    private final PhotoService photoService;


    /**
     * アルバム一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "アルバム一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<AlbumResponse>> listAlbums(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String visibility,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AlbumResponse> result = albumService.listAlbums(teamId, organizationId, q, from, to, visibility, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * アルバム詳細を取得する（写真リスト含む）。
     */
    @GetMapping("/{id}")
    @Operation(summary = "アルバム詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<AlbumResponse>> getAlbum(@PathVariable Long id) {
        AlbumResponse response = albumService.getAlbum(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * アルバムを作成する。
     */
    @PostMapping
    @Operation(summary = "アルバム作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<AlbumResponse>> createAlbum(
            @Valid @RequestBody CreateAlbumRequest request) {
        AlbumResponse response = albumService.createAlbum(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * アルバムを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "アルバム更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<AlbumResponse>> updateAlbum(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAlbumRequest request) {
        AlbumResponse response = albumService.updateAlbum(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * アルバムを削除する（論理削除）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "アルバム削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteAlbum(@PathVariable Long id) {
        albumService.deleteAlbum(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 写真をアップロードする。
     */
    @PostMapping("/{id}/photos")
    @Operation(summary = "写真アップロード")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "アップロード成功")
    public ResponseEntity<ApiResponse<UploadPhotosResponse>> uploadPhotos(
            @PathVariable Long id,
            @Valid @RequestBody UploadPhotosRequest request) {
        UploadPhotosResponse response = photoService.uploadPhotos(id, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * アルバム内写真一覧を取得する。
     */
    @GetMapping("/{id}/photos")
    @Operation(summary = "写真一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<PhotoResponse>> listPhotos(
            @PathVariable Long id,
            @RequestParam(defaultValue = "sort_order") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        Page<PhotoResponse> result = photoService.listPhotos(id, sort, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * アルバム一括ダウンロード（ZIP）。
     */
    @GetMapping("/{id}/download")
    @Operation(summary = "アルバム一括ダウンロード")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ダウンロードURL生成成功")
    public ResponseEntity<ApiResponse<DownloadResponse>> downloadAlbum(
            @PathVariable Long id,
            @RequestParam(required = false) List<Long> photoIds,
            @RequestParam(defaultValue = "100") int limit) {
        DownloadResponse response = photoService.getAlbumDownloadUrl(id, photoIds, limit);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
