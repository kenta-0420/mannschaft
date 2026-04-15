package com.mannschaft.app.advertising.ranking.controller;

import com.mannschaft.app.advertising.entity.AffiliateConfigEntity;
import com.mannschaft.app.advertising.ranking.dto.ReplenishLinkResponse;
import com.mannschaft.app.advertising.repository.AffiliateConfigRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.equipment.EquipmentErrorCode;
import com.mannschaft.app.equipment.entity.EquipmentItemEntity;
import com.mannschaft.app.equipment.repository.EquipmentItemRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 備品補充リンクコントローラー。
 * チームスコープ・組織スコープの備品補充リンク生成APIを提供する。
 */
@RestController
@Tag(name = "備品補充リンク", description = "F09.12 Amazon 備品補充リンク生成")
@RequiredArgsConstructor
public class EquipmentReplenishLinkController {

    private final EquipmentItemRepository equipmentItemRepository;
    private final AffiliateConfigRepository affiliateConfigRepository;
    private final AccessControlService accessControlService;

    /**
     * チームスコープの備品補充リンクを取得する。
     *
     * @param teamId          チームID
     * @param equipmentItemId 備品ID
     */
    @GetMapping("/api/v1/teams/{teamId}/equipment/{equipmentItemId}/replenish-link")
    @Operation(summary = "チーム備品補充リンク取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ReplenishLinkResponse>> getReplenishLinkForTeam(
            @PathVariable Long teamId,
            @PathVariable Long equipmentItemId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, teamId, "TEAM");

        ReplenishLinkResponse response = buildReplenishLink(equipmentItemId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 組織スコープの備品補充リンクを取得する。
     *
     * @param orgId           組織ID
     * @param equipmentItemId 備品ID
     */
    @GetMapping("/api/v1/organizations/{orgId}/equipment/{equipmentItemId}/replenish-link")
    @Operation(summary = "組織備品補充リンク取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ReplenishLinkResponse>> getReplenishLinkForOrg(
            @PathVariable Long orgId,
            @PathVariable Long equipmentItemId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, orgId, "ORGANIZATION");

        ReplenishLinkResponse response = buildReplenishLink(equipmentItemId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ---- ヘルパー ----

    private ReplenishLinkResponse buildReplenishLink(Long equipmentItemId) {
        EquipmentItemEntity item = equipmentItemRepository.findById(equipmentItemId)
                .orElseThrow(() -> new BusinessException(EquipmentErrorCode.ITEM_NOT_FOUND));

        String amazonAsin = item.getAmazonAsin();
        if (amazonAsin == null) {
            return new ReplenishLinkResponse(false, null, null);
        }

        // Amazon アフィリエイトタグを取得
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Tokyo"));
        Optional<AffiliateConfigEntity> configOpt = affiliateConfigRepository.findActiveAmazonConfig(now);

        String replenishUrl;
        if (configOpt.isPresent()) {
            String tag = configOpt.get().getTagId();
            replenishUrl = "https://www.amazon.co.jp/dp/" + amazonAsin + "?tag=" + tag;
        } else {
            replenishUrl = "https://www.amazon.co.jp/dp/" + amazonAsin;
        }

        return new ReplenishLinkResponse(true, replenishUrl, "AMAZON");
    }
}
