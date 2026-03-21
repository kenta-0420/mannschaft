package com.mannschaft.app.chart.controller;

import com.mannschaft.app.chart.dto.ChartFormulaResponse;
import com.mannschaft.app.chart.dto.CreateFormulaRequest;
import com.mannschaft.app.chart.dto.UpdateFormulaRequest;
import com.mannschaft.app.chart.service.ChartFormulaService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 薬剤レシピコントローラー。薬剤レシピのCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/charts")
@Tag(name = "薬剤レシピ", description = "F07.4 薬剤レシピCRUD")
@RequiredArgsConstructor
public class ChartFormulaController {

    private final ChartFormulaService formulaService;

    /**
     * 11. 薬剤レシピ一覧取得
     * GET /api/v1/teams/{teamId}/charts/{id}/formulas
     */
    @GetMapping("/{id}/formulas")
    @Operation(summary = "薬剤レシピ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ChartFormulaResponse>>> listFormulas(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        List<ChartFormulaResponse> response = formulaService.listFormulas(teamId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 12. 薬剤レシピ追加
     * POST /api/v1/teams/{teamId}/charts/{id}/formulas
     */
    @PostMapping("/{id}/formulas")
    @Operation(summary = "薬剤レシピ追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<ChartFormulaResponse>> createFormula(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody CreateFormulaRequest request) {
        ChartFormulaResponse response = formulaService.createFormula(teamId, id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 13. 薬剤レシピ更新
     * PUT /api/v1/teams/{teamId}/charts/formulas/{formulaId}
     */
    @PutMapping("/formulas/{formulaId}")
    @Operation(summary = "薬剤レシピ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ChartFormulaResponse>> updateFormula(
            @PathVariable Long teamId,
            @PathVariable Long formulaId,
            @Valid @RequestBody UpdateFormulaRequest request) {
        ChartFormulaResponse response = formulaService.updateFormula(teamId, formulaId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 14. 薬剤レシピ削除
     * DELETE /api/v1/teams/{teamId}/charts/formulas/{formulaId}
     */
    @DeleteMapping("/formulas/{formulaId}")
    @Operation(summary = "薬剤レシピ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteFormula(
            @PathVariable Long teamId,
            @PathVariable Long formulaId) {
        formulaService.deleteFormula(teamId, formulaId);
        return ResponseEntity.noContent().build();
    }
}
