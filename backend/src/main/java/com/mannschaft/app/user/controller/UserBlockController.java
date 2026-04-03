package com.mannschaft.app.user.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.user.dto.BlockRequest;
import com.mannschaft.app.user.dto.UserBlockResponse;
import com.mannschaft.app.user.service.UserBlockService;
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

/**
 * ユーザーブロックコントローラー。ブロック登録・解除・一覧取得のエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/users/blocks")
@Tag(name = "Users")
@RequiredArgsConstructor
public class UserBlockController {

    private final UserBlockService userBlockService;

    /**
     * ユーザーをブロックする。
     */
    @PostMapping
    @Operation(summary = "ユーザーブロック")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "ブロック成功")
    public ResponseEntity<Void> block(@Valid @RequestBody BlockRequest req) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        userBlockService.block(currentUserId, req.getBlockedId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * ユーザーブロックを解除する。
     */
    @DeleteMapping("/{blockedId}")
    @Operation(summary = "ユーザーブロック解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "ブロック解除成功")
    public ResponseEntity<Void> unblock(@PathVariable Long blockedId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        userBlockService.unblock(currentUserId, blockedId);
        return ResponseEntity.noContent().build();
    }

    /**
     * ブロック一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "ブロック一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<UserBlockResponse>>> listBlocks() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        List<UserBlockResponse> blocks = userBlockService.listBlocks(currentUserId);
        return ResponseEntity.ok(ApiResponse.of(blocks));
    }
}
