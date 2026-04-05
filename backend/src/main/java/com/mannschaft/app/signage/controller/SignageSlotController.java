package com.mannschaft.app.signage.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.signage.service.SignageSlotService;
import com.mannschaft.app.signage.service.SignageSlotService.AddSignageSlotRequest;
import com.mannschaft.app.signage.service.SignageSlotService.SignageSlotResponse;
import com.mannschaft.app.signage.service.SignageSlotService.UpdateSignageSlotRequest;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * デジタルサイネージ スロット管理コントローラー。
 * スロットの追加・一覧・更新・削除・並び替えを提供する。
 */
@RestController
@RequestMapping("/api/signage/screens/{screenId}/slots")
@RequiredArgsConstructor
public class SignageSlotController {

    private final SignageSlotService slotService;

    /**
     * スロットを追加する。
     * 認可: 認証済みユーザー
     * レスポンス: 201 Created
     */
    @PostMapping
    public ResponseEntity<ApiResponse<SignageSlotResponse>> addSlot(
            @PathVariable Long screenId,
            @RequestBody AddSignageSlotRequest request) {
        SignageSlotResponse response = slotService.addSlot(screenId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 画面に紐づくスロット一覧を取得する。
     * 認可: 認証済みユーザー
     * レスポンス: 200 OK
     */
    @GetMapping
    public ApiResponse<List<SignageSlotResponse>> listSlots(@PathVariable Long screenId) {
        return ApiResponse.of(slotService.listSlots(screenId));
    }

    /**
     * スロットを更新する。
     * 認可: 認証済みユーザー
     * レスポンス: 200 OK
     */
    @PutMapping("/{id}")
    public ApiResponse<SignageSlotResponse> updateSlot(
            @PathVariable Long screenId,
            @PathVariable Long id,
            @RequestBody UpdateSignageSlotRequest request) {
        return ApiResponse.of(slotService.updateSlot(id, request));
    }

    /**
     * スロットを物理削除する。
     * 認可: 認証済みユーザー
     * レスポンス: 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> removeSlot(
            @PathVariable Long screenId,
            @PathVariable Long id) {
        slotService.removeSlot(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * スロットの並び順を一括更新する。
     * リクエストボディのスロットIDリストの順番がそのまま表示順に反映される。
     * 認可: 認証済みユーザー
     * レスポンス: 200 OK
     */
    @PutMapping("/reorder")
    public ApiResponse<Void> reorderSlots(
            @PathVariable Long screenId,
            @RequestBody List<Long> orderedIds) {
        slotService.reorderSlots(screenId, orderedIds);
        return ApiResponse.of(null);
    }
}
