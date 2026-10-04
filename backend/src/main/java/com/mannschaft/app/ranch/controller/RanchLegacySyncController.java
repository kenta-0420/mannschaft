package com.mannschaft.app.ranch.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.PrivateSelfAccessGuard;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.ranch.dto.RanchLegacySyncRequest;
import com.mannschaft.app.ranch.dto.RanchLegacySyncResult;
import com.mannschaft.app.ranch.service.RanchLegacySyncFacade;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 本人が明示して旧取得行を100件ずつ承認済み記念品へ取り込む。 */
@RestController
@RequestMapping("/api/v1/me/ranch/collectibles")
@RequiredArgsConstructor
public class RanchLegacySyncController {
    private final PrivateSelfAccessGuard accessGuard;
    private final RanchLegacySyncFacade facade;

    @SelfScopedEndpoint("PrivateSelfAccessGuardの本人IDだけで旧取得行を記念品へ取り込む")
    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<RanchLegacySyncResult>> sync(
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody RanchLegacySyncRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(ApiResponse.of(facade.sync(userId, key, body)));
    }
}
