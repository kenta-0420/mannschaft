package com.mannschaft.app.parking.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.parking.dto.CreateVehicleRequest;
import com.mannschaft.app.parking.dto.UpdateVehicleRequest;
import com.mannschaft.app.parking.dto.VehicleResponse;
import com.mannschaft.app.parking.service.RegisteredVehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 車両管理コントローラー。ユーザー自身の車両CRUD（4 EP）。
 */
@RestController
@RequestMapping("/api/v1/users/me/vehicles")
@Tag(name = "車両管理", description = "F09.3 ユーザー車両CRUD")
@RequiredArgsConstructor
public class VehicleController {

    private final RegisteredVehicleService vehicleService;


    @GetMapping
    @Operation(summary = "車両一覧")
    public ResponseEntity<ApiResponse<List<VehicleResponse>>> list() {
        List<VehicleResponse> result = vehicleService.listByUser(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PostMapping
    @Operation(summary = "車両登録")
    public ResponseEntity<ApiResponse<VehicleResponse>> create(@Valid @RequestBody CreateVehicleRequest request) {
        VehicleResponse result = vehicleService.create(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @PutMapping("/{id}")
    @Operation(summary = "車両更新")
    public ResponseEntity<ApiResponse<VehicleResponse>> update(@PathVariable Long id,
                                                                @Valid @RequestBody UpdateVehicleRequest request) {
        VehicleResponse result = vehicleService.update(SecurityUtils.getCurrentUserId(), id, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "車両削除")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        vehicleService.delete(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
