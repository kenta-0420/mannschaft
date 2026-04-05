package com.mannschaft.app.filesharing.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.filesharing.dto.CreateTagRequest;
import com.mannschaft.app.filesharing.dto.TagResponse;
import com.mannschaft.app.filesharing.service.SharedFileTagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * ファイルタグコントローラー。ファイルに対するタグ管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/files/{fileId}/tags")
@Tag(name = "ファイル共有 - タグ", description = "F05.5 ファイルタグ管理")
@RequiredArgsConstructor
public class FileTagController {

    private final SharedFileTagService tagService;


    /**
     * ファイルのタグ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "タグ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TagResponse>>> listTags(
            @PathVariable Long fileId) {
        List<TagResponse> response = tagService.listTags(fileId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ファイルにタグを追加する。
     */
    @PostMapping
    @Operation(summary = "タグ追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TagResponse>> addTag(
            @PathVariable Long fileId,
            @Valid @RequestBody CreateTagRequest request) {
        TagResponse response = tagService.addTag(fileId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * タグを削除する。
     */
    @DeleteMapping("/{tagId}")
    @Operation(summary = "タグ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> removeTag(
            @PathVariable Long fileId,
            @PathVariable Long tagId) {
        tagService.removeTag(tagId);
        return ResponseEntity.noContent().build();
    }
}
