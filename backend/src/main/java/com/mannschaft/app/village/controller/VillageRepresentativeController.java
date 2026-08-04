package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.dto.RepresentativeGrantRequest;
import com.mannschaft.app.village.dto.RepresentativeResponse;
import com.mannschaft.app.village.dto.RepresentativeRevokeRequest;
import com.mannschaft.app.village.service.VillageRepresentativeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F17 Phase 2 U7 — 村代表委任 Controller（設計書 §5.4 / §13.2）。
 *
 * <p>「村代表」は、TEAM / ORGANIZATION 主体として村に参加しているメンバーシップに対し、
 * HEADMAN / ELDER が個別ユーザーに代表権を委任する機能。投稿主体（{@code postedAs}）の
 * 検証で利用される（U10 PostingIdentity 連携想定）。</p>
 *
 * <h2>エンドポイント一覧</h2>
 * <ul>
 *   <li>{@code POST   /api/v1/villages/{villageId}/representatives}                        — 代表委任の付与（HEADMAN/ELDER）</li>
 *   <li>{@code DELETE /api/v1/villages/{villageId}/representatives/{representativeId}}     — 代表委任の取消し（HEADMAN/ELDER）</li>
 *   <li>{@code GET    /api/v1/villages/{villageId}/representatives}                        — 代表委任一覧</li>
 * </ul>
 *
 * <p>認証は全 API で必須。実体的なロール検証 / 整合性検証は {@link VillageRepresentativeService}
 * に閉じる（@Transactional 含む）。本 Controller は SecurityUtils によるユーザーID取得と
 * DTO ↔ Service の橋渡しのみを担う。</p>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/representatives")
@Tag(name = "村代表委任 (F17.1 Phase 2)", description = "村代表権限の委任 CRUD")
@RequiredArgsConstructor
public class VillageRepresentativeController {

    private final VillageRepresentativeService representativeService;

    /**
     * 認可は {@link VillageRepresentativeService#grantRepresentative} 内で実施する。
     * 村の存在確認ののち {@code ensureModerator} が現役の HEADMAN / ELDER であることを
     * 正準述語 {@code findActiveByVillageIdAndSubject} で検証する。対象メンバーシップは
     * 実体を取得したうえでその {@code villageId} とパスの村 ID の一致を照合し、
     * 不一致・退村済みは {@code NOT_MEMBER}（404）で存在を秘匿する。
     */
    @AuthorizedInService
    @PostMapping
    @Operation(summary = "村代表委任の付与（HEADMAN/ELDER）")
    public ResponseEntity<ApiResponse<RepresentativeResponse>> grant(
            @PathVariable UUID villageId,
            @Valid @RequestBody RepresentativeGrantRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        RepresentativeResponse dto = representativeService.grantRepresentative(villageId, request, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(dto));
    }

    /**
     * 認可は {@link VillageRepresentativeService#revokeRepresentative} 内で実施する。
     * 現役の HEADMAN / ELDER であることを検証したうえで、委任レコードの実体を取得し
     * その {@code villageId} がパスの村 ID と一致し未取消であることを照合する。
     * 不一致は 404 で存在を秘匿する。
     */
    @AuthorizedInService
    @DeleteMapping("/{representativeId}")
    @Operation(summary = "村代表委任の取消し（HEADMAN/ELDER）")
    public ResponseEntity<ApiResponse<RepresentativeResponse>> revoke(
            @PathVariable UUID villageId,
            @PathVariable UUID representativeId,
            @RequestBody(required = false) RepresentativeRevokeRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        RepresentativeResponse dto = representativeService.revokeRepresentative(
                villageId, representativeId, request, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(dto));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村代表委任一覧（村人のみ・任意で取消し済みも含む）")
    public ResponseEntity<ApiResponse<List<RepresentativeResponse>>> list(
            @PathVariable UUID villageId,
            @RequestParam(name = "includeRevoked", defaultValue = "false") boolean includeRevoked) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        List<RepresentativeResponse> dtos =
                representativeService.listRepresentatives(villageId, includeRevoked, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(dtos));
    }
}
