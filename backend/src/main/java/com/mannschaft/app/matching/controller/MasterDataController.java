package com.mannschaft.app.matching.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.matching.dto.CityResponse;
import com.mannschaft.app.matching.dto.PrefectureResponse;
import com.mannschaft.app.matching.service.MasterDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * マスタデータコントローラー。都道府県・市区町村マスタAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/master")
@Tag(name = "マスタデータ", description = "都道府県・市区町村マスタ")
@RequiredArgsConstructor
public class MasterDataController {

    private final MasterDataService masterDataService;

    /**
     * 都道府県マスタ一覧。
     */
    @GetMapping("/prefectures")
    @Operation(summary = "都道府県マスタ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PrefectureResponse>>> listPrefectures() {
        List<PrefectureResponse> response = masterDataService.listPrefectures();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 都道府県内の市区町村一覧。
     */
    @GetMapping("/prefectures/{code}/cities")
    @Operation(summary = "市区町村一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CityResponse>>> listCities(@PathVariable String code) {
        List<CityResponse> response = masterDataService.listCities(code);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
