package com.mannschaft.app.cms.controller;

import com.mannschaft.app.cms.dto.BlogTagResponse;
import com.mannschaft.app.cms.dto.CreateTagRequest;
import com.mannschaft.app.cms.dto.UpdateTagRequest;
import com.mannschaft.app.cms.service.BlogTagService;
import com.mannschaft.app.common.ApiResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ブログタグコントローラー。タグのCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/blog/tags")
@Tag(name = "ブログタグ", description = "F06.1 ブログタグCRUD")
@RequiredArgsConstructor
public class BlogTagController {

    private final BlogTagService tagService;

    /**
     * タグ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "タグ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<BlogTagResponse>>> listTags(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long organizationId) {
        return ResponseEntity.ok(ApiResponse.of(tagService.listTags(teamId, organizationId)));
    }

    /**
     * タグを作成する。
     */
    @PostMapping
    @Operation(summary = "タグ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<BlogTagResponse>> createTag(
            @Valid @RequestBody CreateTagRequest request) {
        BlogTagResponse response = tagService.createTag(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * タグを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "タグ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<BlogTagResponse>> updateTag(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTagRequest request) {
        return ResponseEntity.ok(ApiResponse.of(tagService.updateTag(id, request)));
    }

    /**
     * タグを削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "タグ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTag(@PathVariable Long id) {
        tagService.deleteTag(id);
        return ResponseEntity.noContent().build();
    }
}
