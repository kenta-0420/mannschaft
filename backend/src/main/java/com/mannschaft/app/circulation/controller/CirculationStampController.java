package com.mannschaft.app.circulation.controller;

import com.mannschaft.app.circulation.dto.AdminSkipRecipientRequest;
import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.dto.StampCorrectionRequest;
import com.mannschaft.app.circulation.dto.StampDelegationRequest;
import com.mannschaft.app.circulation.dto.StampDelegationResponse;
import com.mannschaft.app.circulation.dto.StampRequest;
import com.mannschaft.app.circulation.service.CirculationStampService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 押印コントローラー。回覧文書への押印・スキップ・拒否APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/circulations/{documentId}/stamp")
@Tag(name = "回覧押印", description = "F05.2 回覧文書への押印管理")
@RequiredArgsConstructor
public class CirculationStampController {

    private final CirculationStampService stampService;


    /**
     * 押印する。
     */
    @PostMapping
    @Operation(summary = "押印")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "押印成功")
    public ResponseEntity<ApiResponse<RecipientResponse>> stamp(
            @PathVariable Long documentId,
            @Valid @RequestBody StampRequest request) {
        RecipientResponse response = stampService.stamp(documentId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スキップする。
     */
    @PostMapping("/skip")
    @Operation(summary = "スキップ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "スキップ成功")
    public ResponseEntity<ApiResponse<RecipientResponse>> skip(
            @PathVariable Long documentId) {
        RecipientResponse response = stampService.skip(documentId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 拒否する。
     */
    @PostMapping("/reject")
    @Operation(summary = "拒否")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "拒否成功")
    public ResponseEntity<ApiResponse<RecipientResponse>> reject(
            @PathVariable Long documentId) {
        RecipientResponse response = stampService.reject(documentId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 押印を訂正する（受信者本人）。
     *
     * <p>F05.2 Phase 11 第三陣 3-B: 押印済みの自分の押印を訂正する。
     * 押印後 24 時間以内のみ可能。訂正後は status=PENDING に戻り、再押印可能。</p>
     */
    @PostMapping("/correct")
    @Operation(summary = "押印訂正")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "訂正成功")
    public ResponseEntity<ApiResponse<RecipientResponse>> correctStamp(
            @PathVariable Long documentId,
            @Valid @RequestBody(required = false) StampCorrectionRequest request) {
        RecipientResponse response = stampService.correctStamp(documentId,
                SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 押印を委任する。
     *
     * <p>F05.2 Phase 11 第三陣 3-B: 受信者本人 (delegator) が別ユーザー (delegatee) に
     * 押印を委任する。同一文書に対し1委任者あたり1件のみ。</p>
     */
    @PostMapping("/delegate")
    @Operation(summary = "押印委任")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "委任成功")
    public ResponseEntity<ApiResponse<StampDelegationResponse>> delegateStamp(
            @PathVariable Long documentId,
            @Valid @RequestBody StampDelegationRequest request) {
        StampDelegationResponse response = stampService.delegateStamp(documentId,
                SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
