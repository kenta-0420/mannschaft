package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.village.dto.PinListResponse;
import com.mannschaft.app.village.dto.PinOrderUpdateRequest;
import com.mannschaft.app.village.dto.PinResponse;
import com.mannschaft.app.village.service.VillagePinService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F17.1 B8 — お気に入り村ピン留めコントローラ（設計書 §4.8）。
 *
 * <p>ユーザー単位の {@code /me} エンドポイントとして提供する。
 * 上限 30 件、並び替えは現在のピン集合との完全一致を要求。</p>
 */
@RestController
@RequestMapping("/api/v1/me/village-pins")
@Tag(name = "村ピン留め", description = "F17.1 Phase 1 お気に入り村ピン留め")
@RequiredArgsConstructor
public class VillagePinController {

    private final VillagePinService pinService;

    /**
     * 自分のピン一覧を取得する（sort_order 昇順）。
     */
    @SelfScopedEndpoint("検索条件が SecurityUtils.getCurrentUserId() のみ"
            + "（VillagePinService#listMyPins の findByUserIdOrderBySortOrderAsc が認証主体に束縛される）")
    @GetMapping
    @Operation(summary = "自分のピン一覧取得")
    public ResponseEntity<ApiResponse<PinListResponse>> listMyPins() {
        Long userId = SecurityUtils.getCurrentUserId();
        PinListResponse response = pinService.listMyPins(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 村をピン留めする。
     * <ul>
     *   <li>404 VILLAGE_NOT_FOUND: 村が存在しない / 削除 / 凍結</li>
     *   <li>409 VILLAGE_PIN_ALREADY_EXISTS: 既にピン済み</li>
     *   <li>422 VILLAGE_PIN_LIMIT_EXCEEDED: 30 件超過</li>
     * </ul>
     */
    @SelfScopedEndpoint("作成されるピン行の所有者は常に SecurityUtils.getCurrentUserId() で、"
            + "他ユーザーのピンには到達しない（VillagePinService#pin が userId を認証主体で固定する）。"
            + "villageId は公開の村識別子であり、ピン留め自体は村外からも行える仕様")
    @PostMapping("/{villageId}")
    @Operation(summary = "村をピン留め")
    public ResponseEntity<ApiResponse<PinResponse>> pin(@PathVariable UUID villageId) {
        Long userId = SecurityUtils.getCurrentUserId();
        PinResponse response = pinService.pin(userId, villageId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 村のピンを解除する。
     * <ul>
     *   <li>404 VILLAGE_PIN_NOT_FOUND: ピンが存在しない</li>
     * </ul>
     */
    @SelfScopedEndpoint("削除対象は (SecurityUtils.getCurrentUserId(), villageId) で一意に解決され、"
            + "他ユーザーのピン行には到達しない"
            + "（VillagePinService#unpin の findByUserIdAndVillageId が認証主体に束縛される）")
    @DeleteMapping("/{villageId}")
    @Operation(summary = "村のピン解除")
    public ResponseEntity<Void> unpin(@PathVariable UUID villageId) {
        Long userId = SecurityUtils.getCurrentUserId();
        pinService.unpin(userId, villageId);
        return ResponseEntity.noContent().build();
    }

    /**
     * ピンの並び順を更新する。
     * <ul>
     *   <li>422 VILLAGE_PIN_ORDER_MISMATCH: 並び替え対象が現在のピン集合と一致しない</li>
     * </ul>
     */
    @SelfScopedEndpoint("並び替え対象は認証主体のピン集合に限定され、リクエストの villageId 列は"
            + "現在の自分のピン集合と完全一致でなければ 422 で拒否される"
            + "（VillagePinService#reorder が findByUserIdOrderBySortOrderAsc の結果とのみ突き合わせる）")
    @PatchMapping("/order")
    @Operation(summary = "ピン並び替え")
    public ResponseEntity<ApiResponse<PinListResponse>> reorder(
            @Valid @RequestBody PinOrderUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        PinListResponse response = pinService.reorder(userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
