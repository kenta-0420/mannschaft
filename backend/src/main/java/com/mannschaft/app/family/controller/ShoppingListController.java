package com.mannschaft.app.family.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.family.dto.ShoppingItemRequest;
import com.mannschaft.app.family.dto.ShoppingItemResponse;
import com.mannschaft.app.family.dto.ShoppingListRequest;
import com.mannschaft.app.family.dto.ShoppingListResponse;
import com.mannschaft.app.family.service.ShoppingListService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * お買い物リストコントローラー。共有お買い物リストのCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/shopping-lists")
@Tag(name = "お買い物リスト", description = "F01.4 お買い物リスト")
@RequiredArgsConstructor
public class ShoppingListController {

    private final ShoppingListService shoppingListService;


    @GetMapping
    @Operation(summary = "お買い物リスト一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ShoppingListResponse>>> getLists(
            @PathVariable Long teamId, @RequestParam(required = false) String status) {
        return ResponseEntity.ok(shoppingListService.getLists(teamId, status));
    }

    @PostMapping
    @Operation(summary = "お買い物リスト作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ShoppingListResponse>> createList(
            @PathVariable Long teamId, @Valid @RequestBody ShoppingListRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(shoppingListService.createList(teamId, SecurityUtils.getCurrentUserId(), request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "お買い物リスト名変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ShoppingListResponse>> updateList(
            @PathVariable Long teamId, @PathVariable Long id, @Valid @RequestBody ShoppingListRequest request) {
        return ResponseEntity.ok(shoppingListService.updateList(teamId, id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "お買い物リスト削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteList(@PathVariable Long teamId, @PathVariable Long id) {
        shoppingListService.deleteList(teamId, id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/archive")
    @Operation(summary = "お買い物リストアーカイブ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ成功")
    public ResponseEntity<ApiResponse<ShoppingListResponse>> archiveList(
            @PathVariable Long teamId, @PathVariable Long id) {
        return ResponseEntity.ok(shoppingListService.archiveList(teamId, id));
    }

    @PostMapping("/{id}/copy-from-template")
    @Operation(summary = "テンプレートからコピー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "コピー成功")
    public ResponseEntity<ApiResponse<List<ShoppingItemResponse>>> copyFromTemplate(
            @PathVariable Long teamId, @PathVariable Long id, @RequestParam Long templateId) {
        return ResponseEntity.ok(shoppingListService.copyFromTemplate(teamId, id, templateId, SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/{id}/items")
    @Operation(summary = "アイテム一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ShoppingItemResponse>>> getItems(
            @PathVariable Long teamId, @PathVariable Long id) {
        return ResponseEntity.ok(shoppingListService.getItems(teamId, id));
    }

    @PostMapping("/{id}/items")
    @Operation(summary = "アイテム追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<ShoppingItemResponse>> addItem(
            @PathVariable Long teamId, @PathVariable Long id, @Valid @RequestBody ShoppingItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(shoppingListService.addItem(teamId, id, SecurityUtils.getCurrentUserId(), request));
    }

    @PutMapping("/{id}/items/{itemId}")
    @Operation(summary = "アイテム更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ShoppingItemResponse>> updateItem(
            @PathVariable Long teamId, @PathVariable Long id, @PathVariable Long itemId,
            @Valid @RequestBody ShoppingItemRequest request) {
        return ResponseEntity.ok(shoppingListService.updateItem(teamId, id, itemId, request));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @Operation(summary = "アイテム削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteItem(
            @PathVariable Long teamId, @PathVariable Long id, @PathVariable Long itemId) {
        shoppingListService.deleteItem(teamId, id, itemId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/items/{itemId}/check")
    @Operation(summary = "購入済みチェック")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ShoppingItemResponse>> toggleCheck(
            @PathVariable Long teamId, @PathVariable Long id, @PathVariable Long itemId) {
        return ResponseEntity.ok(shoppingListService.toggleCheck(teamId, id, itemId, SecurityUtils.getCurrentUserId()));
    }

    @DeleteMapping("/{id}/items/checked")
    @Operation(summary = "チェック済み一括削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "削除成功")
    public ResponseEntity<ApiResponse<Integer>> deleteCheckedItems(
            @PathVariable Long teamId, @PathVariable Long id) {
        return ResponseEntity.ok(shoppingListService.deleteCheckedItems(teamId, id));
    }

    @PatchMapping("/{id}/items/uncheck-all")
    @Operation(summary = "全チェック一括解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<Integer>> uncheckAll(
            @PathVariable Long teamId, @PathVariable Long id) {
        return ResponseEntity.ok(shoppingListService.uncheckAll(teamId, id));
    }
}
