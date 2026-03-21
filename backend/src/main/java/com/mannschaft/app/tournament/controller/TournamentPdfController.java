package com.mannschaft.app.tournament.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PDF出力コントローラー。
 * 4 endpoints: standings PDF, bracket PDF, rankings PDF, matrix PDF
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tournaments/{tId}")
@Tag(name = "大会PDF出力", description = "F08.7 順位表・ブラケット・ランキング・マトリクスPDF")
@RequiredArgsConstructor
public class TournamentPdfController {

    @GetMapping("/divisions/{divId}/standings/pdf")
    @Operation(summary = "順位表PDF")
    public ResponseEntity<byte[]> getStandingsPdf(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        // TODO: PDF生成実装（OpenPDF / Flying Saucer）
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/bracket/pdf")
    @Operation(summary = "トーナメント表PDF")
    public ResponseEntity<byte[]> getBracketPdf(
            @PathVariable Long orgId, @PathVariable Long tId) {
        // TODO: PDF生成実装
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/rankings/{statKey}/pdf")
    @Operation(summary = "個人ランキングPDF")
    public ResponseEntity<byte[]> getRankingsPdf(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable String statKey) {
        // TODO: PDF生成実装
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/divisions/{divId}/matrix/pdf")
    @Operation(summary = "対戦マトリクスPDF")
    public ResponseEntity<byte[]> getMatrixPdf(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        // TODO: PDF生成実装
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
