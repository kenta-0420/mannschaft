package com.mannschaft.app.circulation.controller;

import com.mannschaft.app.circulation.dto.CommentResponse;
import com.mannschaft.app.circulation.dto.CreateCommentRequest;
import com.mannschaft.app.circulation.service.CirculationCommentService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 回覧コメントコントローラー。回覧文書のコメント管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/circulations/{documentId}/comments")
@Tag(name = "回覧コメント", description = "F05.2 回覧文書のコメント管理")
@RequiredArgsConstructor
public class CirculationCommentController {

    private final CirculationCommentService commentService;


    /**
     * コメント一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "コメント一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<CommentResponse>> listComments(
            @PathVariable Long documentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<CommentResponse> result = commentService.listComments(documentId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * コメントを作成する。
     */
    @PostMapping
    @Operation(summary = "コメント作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<CommentResponse>> createComment(
            @PathVariable Long documentId,
            @Valid @RequestBody CreateCommentRequest request) {
        CommentResponse response = commentService.createComment(documentId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
