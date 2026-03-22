package com.mannschaft.app.parking.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.parking.ParkingScopeType;
import com.mannschaft.app.parking.dto.CreateWatchlistRequest;
import com.mannschaft.app.parking.dto.WatchlistResponse;
import com.mannschaft.app.parking.service.ParkingWatchlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 組織ウォッチリストコントローラー（3 EP）。
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/parking/watchlist")
@Tag(name = "組織ウォッチリスト", description = "F09.3 組織駐車場ウォッチリスト")
@RequiredArgsConstructor
public class OrgParkingWatchlistController {

    private final ParkingWatchlistService watchlistService;

    private static final String SCOPE_TYPE = ParkingScopeType.ORGANIZATION.name();

    @GetMapping
    @Operation(summary = "組織ウォッチリスト一覧")
    public ResponseEntity<ApiResponse<List<WatchlistResponse>>> list(@PathVariable Long organizationId) {
        List<WatchlistResponse> result = watchlistService.list(SecurityUtils.getCurrentUserId(), SCOPE_TYPE, organizationId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PostMapping
    @Operation(summary = "組織ウォッチリスト追加")
    public ResponseEntity<ApiResponse<WatchlistResponse>> create(
            @PathVariable Long organizationId,
            @Valid @RequestBody CreateWatchlistRequest request) {
        WatchlistResponse result = watchlistService.create(SecurityUtils.getCurrentUserId(), SCOPE_TYPE, organizationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "組織ウォッチリスト削除")
    public ResponseEntity<Void> delete(@PathVariable Long organizationId, @PathVariable Long id) {
        watchlistService.delete(SecurityUtils.getCurrentUserId(), SCOPE_TYPE, organizationId, id);
        return ResponseEntity.noContent().build();
    }
}
