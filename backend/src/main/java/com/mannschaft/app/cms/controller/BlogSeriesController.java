package com.mannschaft.app.cms.controller;

import com.mannschaft.app.cms.dto.BlogSeriesResponse;
import com.mannschaft.app.cms.dto.CreateSeriesRequest;
import com.mannschaft.app.cms.dto.UpdateSeriesRequest;
import com.mannschaft.app.cms.service.BlogSeriesService;
import com.mannschaft.app.common.ApiResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ブログシリーズコントローラー。連載シリーズのCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/blog/series")
@Tag(name = "ブログシリーズ", description = "F06.1 連載シリーズCRUD")
@RequiredArgsConstructor
public class BlogSeriesController {

    private final BlogSeriesService seriesService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * シリーズ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "シリーズ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<BlogSeriesResponse>>> listSeries(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long organizationId) {
        return ResponseEntity.ok(ApiResponse.of(seriesService.listSeries(teamId, organizationId)));
    }

    /**
     * シリーズを作成する。
     */
    @PostMapping
    @Operation(summary = "シリーズ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<BlogSeriesResponse>> createSeries(
            @Valid @RequestBody CreateSeriesRequest request) {
        BlogSeriesResponse response = seriesService.createSeries(getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * シリーズを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "シリーズ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<BlogSeriesResponse>> updateSeries(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSeriesRequest request) {
        return ResponseEntity.ok(ApiResponse.of(seriesService.updateSeries(id, request)));
    }

    /**
     * シリーズを削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "シリーズ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteSeries(@PathVariable Long id) {
        seriesService.deleteSeries(id);
        return ResponseEntity.noContent().build();
    }
}
