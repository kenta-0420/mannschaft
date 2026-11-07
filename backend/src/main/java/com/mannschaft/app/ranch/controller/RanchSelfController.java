package com.mannschaft.app.ranch.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.PrivateSelfAccessGuard;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.ranch.dto.EmptyRanchRequest;
import com.mannschaft.app.ranch.dto.RanchState;
import com.mannschaft.app.ranch.service.RanchSelfFacade;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** 本人IDを認証主体からのみ取得する牧場の初期HTTP境界。 */
@RestController
@RequestMapping("/api/v1/me/ranch")
@RequiredArgsConstructor
public class RanchSelfController {
    private final RanchSelfFacade facade;
    private final PrivateSelfAccessGuard accessGuard;

    @SelfScopedEndpoint("PrivateSelfAccessGuardで認証本人IDと変身拒否を固定")
    @GetMapping
    public ResponseEntity<ApiResponse<RanchState>> read(HttpServletRequest request,
                                                       HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        RanchState result = facade.read(userId);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(ApiResponse.of(result));
    }

    @SelfScopedEndpoint("PrivateSelfAccessGuardで認証本人IDと変身拒否を固定")
    @PostMapping
    public ResponseEntity<ApiResponse<RanchState>> enroll(
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody EmptyRanchRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        var result = facade.enroll(userId, key);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        ResponseEntity.BodyBuilder responseBuilder = result.createdNow()
                ? ResponseEntity.created(URI.create("/api/v1/me/ranch"))
                : ResponseEntity.ok();
        return responseBuilder.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(ApiResponse.of(result.snapshot()));
    }
}
