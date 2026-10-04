package com.mannschaft.app.ranch.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.PrivateSelfAccessGuard;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.ranch.dto.AssignmentResult;
import com.mannschaft.app.ranch.dto.RanchAssignmentRequest;
import com.mannschaft.app.ranch.service.RanchAssignmentFacade;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 本人の卵選定を一回だけ確定する。外部対象userIdやraw出生情報を受け付けない。 */
@RestController
@RequestMapping("/api/v1/me/ranch")
@RequiredArgsConstructor
public class RanchAssignmentController {
    private final RanchAssignmentFacade facade;
    private final PrivateSelfAccessGuard accessGuard;

    @SelfScopedEndpoint("PrivateSelfAccessGuardが認証本人IDと管理者変身拒否を固定する")
    @PutMapping("/assignment")
    public ResponseEntity<ApiResponse<AssignmentResult>> assign(
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RanchAssignmentRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        AssignmentResult result = facade.assign(userId, key, body);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(ApiResponse.of(result));
    }
}
