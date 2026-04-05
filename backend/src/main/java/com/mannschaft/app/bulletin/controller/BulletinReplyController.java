package com.mannschaft.app.bulletin.controller;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CreateReplyRequest;
import com.mannschaft.app.bulletin.dto.ReplyResponse;
import com.mannschaft.app.bulletin.dto.UpdateReplyRequest;
import com.mannschaft.app.bulletin.service.BulletinReplyService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 掲示板返信コントローラー。返信のCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/replies")
@Tag(name = "掲示板返信", description = "F05.1 掲示板返信CRUD")
@RequiredArgsConstructor
public class BulletinReplyController {

    private final BulletinReplyService replyService;


    /**
     * 返信一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "返信一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ReplyResponse>> listReplies(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long threadId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        Page<ReplyResponse> result = replyService.listReplies(type, scopeId, threadId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 返信を作成する。
     */
    @PostMapping
    @Operation(summary = "返信作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ReplyResponse>> createReply(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long threadId,
            @Valid @RequestBody CreateReplyRequest request) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        ReplyResponse response = replyService.createReply(type, scopeId, threadId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 返信を更新する。
     */
    @PutMapping("/{replyId}")
    @Operation(summary = "返信更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ReplyResponse>> updateReply(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long threadId,
            @PathVariable Long replyId,
            @Valid @RequestBody UpdateReplyRequest request) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        ReplyResponse response = replyService.updateReply(type, scopeId, threadId, replyId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 返信を削除する。
     */
    @DeleteMapping("/{replyId}")
    @Operation(summary = "返信削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteReply(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long threadId,
            @PathVariable Long replyId) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        replyService.deleteReply(type, scopeId, threadId, replyId);
        return ResponseEntity.noContent().build();
    }
}
