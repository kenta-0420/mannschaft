package com.mannschaft.app.circulation.controller;

import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.dto.StampRequest;
import com.mannschaft.app.circulation.service.CirculationStampService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

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
}
