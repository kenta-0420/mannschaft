package com.mannschaft.app.safetycheck.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.safetycheck.FollowupStatus;
import com.mannschaft.app.safetycheck.SafetyCheckErrorCode;
import com.mannschaft.app.safetycheck.SafetyCheckMapper;
import com.mannschaft.app.safetycheck.dto.FollowupUpdateRequest;
import com.mannschaft.app.safetycheck.dto.SafetyFollowupResponse;
import com.mannschaft.app.safetycheck.entity.SafetyResponseFollowupEntity;
import com.mannschaft.app.safetycheck.repository.SafetyResponseFollowupRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * フォローアップコントローラー。フォローアップの更新APIを提供する。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/safety-checks/followups")
@Tag(name = "安否確認フォローアップ管理", description = "F03.6 フォローアップ更新")
@RequiredArgsConstructor
public class SafetyFollowupController {

    private final SafetyResponseFollowupRepository followupRepository;
    private final SafetyCheckMapper mapper;

    /**
     * フォローアップを更新する。
     */
    @PatchMapping("/{followupId}")
    @Operation(summary = "フォローアップ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SafetyFollowupResponse>> updateFollowup(
            @PathVariable Long followupId,
            @Valid @RequestBody FollowupUpdateRequest request) {
        SafetyResponseFollowupEntity entity = followupRepository.findById(followupId)
                .orElseThrow(() -> new BusinessException(SafetyCheckErrorCode.FOLLOWUP_NOT_FOUND));

        FollowupStatus status = request.getFollowupStatus() != null
                ? FollowupStatus.valueOf(request.getFollowupStatus()) : null;

        entity.update(status, request.getAssignedTo(), request.getNote());
        entity = followupRepository.save(entity);

        log.info("フォローアップ更新: id={}, status={}", followupId, entity.getFollowupStatus());
        return ResponseEntity.ok(ApiResponse.of(mapper.toFollowupResponse(entity)));
    }
}
