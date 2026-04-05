package com.mannschaft.app.corkboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.corkboard.dto.CorkboardGroupResponse;
import com.mannschaft.app.corkboard.dto.CreateGroupRequest;
import com.mannschaft.app.corkboard.dto.UpdateGroupRequest;
import com.mannschaft.app.corkboard.service.CorkboardGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * コルクボードセクションコントローラー。セクションのCRUDとカード紐付けAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/corkboards/{boardId}/groups")
@Tag(name = "コルクボードセクション", description = "F09.8 コルクボードセクションCRUD")
@RequiredArgsConstructor
public class CorkboardGroupController {

    private final CorkboardGroupService groupService;

    /**
     * セクションを作成する。
     */
    @PostMapping
    @Operation(summary = "セクション作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<CorkboardGroupResponse>> createGroup(
            @PathVariable Long boardId, @Valid @RequestBody CreateGroupRequest request) {
        CorkboardGroupResponse response = groupService.createGroup(boardId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * セクションを更新する。
     */
    @PutMapping("/{groupId}")
    @Operation(summary = "セクション更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<CorkboardGroupResponse>> updateGroup(
            @PathVariable Long boardId, @PathVariable Long groupId,
            @Valid @RequestBody UpdateGroupRequest request) {
        CorkboardGroupResponse response = groupService.updateGroup(boardId, groupId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * セクションを削除する。
     */
    @DeleteMapping("/{groupId}")
    @Operation(summary = "セクション削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long boardId, @PathVariable Long groupId) {
        groupService.deleteGroup(boardId, groupId);
        return ResponseEntity.noContent().build();
    }

    /**
     * カードをセクションに追加する。
     */
    @PostMapping("/{groupId}/cards/{cardId}")
    @Operation(summary = "カードをセクションに追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<Void> addCardToGroup(
            @PathVariable Long boardId, @PathVariable Long groupId, @PathVariable Long cardId) {
        groupService.addCardToGroup(boardId, groupId, cardId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * カードをセクションから削除する。
     */
    @DeleteMapping("/{groupId}/cards/{cardId}")
    @Operation(summary = "カードをセクションから削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> removeCardFromGroup(
            @PathVariable Long boardId, @PathVariable Long groupId, @PathVariable Long cardId) {
        groupService.removeCardFromGroup(boardId, groupId, cardId);
        return ResponseEntity.noContent().build();
    }
}
