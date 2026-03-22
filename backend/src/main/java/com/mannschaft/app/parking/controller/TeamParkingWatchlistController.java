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

/**
 * チームウォッチリストコントローラー（3 EP）。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/parking/watchlist")
@Tag(name = "チームウォッチリスト", description = "F09.3 チーム駐車場ウォッチリスト")
@RequiredArgsConstructor
public class TeamParkingWatchlistController {

    private final ParkingWatchlistService watchlistService;

    private static final String SCOPE_TYPE = ParkingScopeType.TEAM.name();

    private Long getCurrentUserId() {
        return 1L;
    }

    @GetMapping
    @Operation(summary = "チームウォッチリスト一覧")
    public ResponseEntity<ApiResponse<List<WatchlistResponse>>> list(@PathVariable Long teamId) {
        List<WatchlistResponse> result = watchlistService.list(getCurrentUserId(), SCOPE_TYPE, teamId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PostMapping
    @Operation(summary = "チームウォッチリスト追加")
    public ResponseEntity<ApiResponse<WatchlistResponse>> create(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateWatchlistRequest request) {
        WatchlistResponse result = watchlistService.create(getCurrentUserId(), SCOPE_TYPE, teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "チームウォッチリスト削除")
    public ResponseEntity<Void> delete(@PathVariable Long teamId, @PathVariable Long id) {
        watchlistService.delete(getCurrentUserId(), SCOPE_TYPE, teamId, id);
        return ResponseEntity.noContent().build();
    }
}
