package com.mannschaft.app.ranch.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.security.PrivateSelfAccessGuard;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.ranch.dto.CommandResult;
import com.mannschaft.app.ranch.dto.RanchInventoryItem;
import com.mannschaft.app.ranch.dto.RanchRecord;
import com.mannschaft.app.ranch.dto.RanchShopItem;
import com.mannschaft.app.ranch.service.RanchPrivateQueryFacade;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 本人の成功commandと私的履歴だけを公開する。 */
@RestController
@RequestMapping("/api/v1/me/ranch")
@RequiredArgsConstructor
public class RanchPrivateQueryController {
    private final RanchPrivateQueryFacade facade;
    private final PrivateSelfAccessGuard accessGuard;

    @GetMapping("/commands/{commandId}")
    public ResponseEntity<ApiResponse<CommandResult>> command(@PathVariable UUID commandId,
                                                                HttpServletRequest request,
                                                                HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        return noStore(ApiResponse.of(facade.command(userId, commandId)), response);
    }

    @SelfScopedEndpoint("PrivateSelfAccessGuardの本人IDだけで牧場記録を検索")
    @GetMapping("/records")
    public ResponseEntity<CursorPagedResponse<RanchRecord>> records(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        return noStore(facade.records(userId, cursor, limit), response);
    }

    @SelfScopedEndpoint("PrivateSelfAccessGuardの本人IDだけで所有置物を検索")
    @GetMapping("/collectibles")
    public ResponseEntity<CursorPagedResponse<RanchInventoryItem>> collectibles(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        return noStore(facade.collectibles(userId, cursor, limit), response);
    }

    @SelfScopedEndpoint("PrivateSelfAccessGuardの本人IDだけで現行商品を投影")
    @GetMapping("/shop")
    public ResponseEntity<ApiResponse<List<RanchShopItem>>> shop(HttpServletRequest request,
                                                                  HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        return noStore(ApiResponse.of(facade.shop(userId)), response);
    }

    private <T> ResponseEntity<T> noStore(T data, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(data);
    }
}
