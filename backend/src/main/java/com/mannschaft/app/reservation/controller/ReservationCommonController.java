package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.reservation.dto.CancelReservationRequest;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 予約共通コントローラー。ログインユーザー自身の予約管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/reservations")
@Tag(name = "マイ予約", description = "F03.4 ログインユーザーの予約管理")
@RequiredArgsConstructor
public class ReservationCommonController {

    private final ReservationService reservationService;


    /**
     * 自分の予約一覧を取得する。
     *
     * <p><b>認可（{@link AuthorizedInService} 付与の根拠・認可根治戦役 Wave7 監査済）</b>:
     * 本 EP は引数を取らず、{@code ReservationService#listMyReservations(Long)} が
     * {@code ReservationRepository#findByUserIdAndIsGroupPrimaryTrueOrderByBookedAtDesc} で
     * <b>呼び出しユーザー自身の userId のみ</b>を検索条件に固定して一覧を返す構造的な自己スコープ EP
     * である。他人の userId を差し込む余地がなく、権限昇格は発生しない。
     * データ依存でない構造的な自己スコープ認可のため白名簿クラス呼び出しを持たず、
     * 本マーカーで監査済であることを明示する。</p>
     */
    @GetMapping("/my")
    @Operation(summary = "マイ予約一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<List<ReservationResponse>>> listMyReservations() {
        List<ReservationResponse> reservations = reservationService.listMyReservations(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(reservations));
    }

    /**
     * 直近の予約一覧を取得する。
     *
     * <p><b>認可（{@link AuthorizedInService} 付与の根拠・認可根治戦役 Wave7 監査済）</b>:
     * 本 EP は引数を取らず、{@code ReservationService#listUpcomingReservations(Long)} が
     * {@code ReservationRepository#findUpcomingByUserId} で<b>呼び出しユーザー自身の userId のみ</b>を
     * 検索条件に固定して一覧を返す構造的な自己スコープ EP である。他人の userId を差し込む余地がなく、
     * 権限昇格は発生しない。データ依存でない構造的な自己スコープ認可のため白名簿クラス呼び出しを持たず、
     * 本マーカーで監査済であることを明示する。</p>
     */
    @GetMapping("/upcoming")
    @Operation(summary = "直近の予約一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<List<ReservationResponse>>> listUpcomingReservations() {
        List<ReservationResponse> reservations = reservationService.listUpcomingReservations(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(reservations));
    }

    /**
     * 自分の予約をキャンセルする。
     *
     * <p><b>認可（{@link AuthorizedInService} 付与の根拠・認可根治戦役 Wave7 監査済）</b>:
     * パス変数 {@code reservationId} はそれ単体ではスコープ判定に用いない。認可の実体は
     * {@code ReservationService#cancelByUser(Long, Long, CancelReservationRequest)} が
     * {@code ReservationRepository#findByIdAndUserId} で<b>呼び出しユーザー自身が所有する予約のみ</b>を
     * 特定し、該当しなければ {@code RESERVATION_NOT_FOUND} を投げる構造にある。他人の予約
     * reservationId を差し込んでも他人の予約はキャンセルできない自己スコープ EP であり、
     * 権限昇格は発生しない。データ依存でない構造的な自己スコープ認可のため白名簿クラス呼び出しを
     * 持たず、本マーカーで監査済であることを明示する。</p>
     */
    @PostMapping("/{reservationId}/cancel")
    @Operation(summary = "マイ予約キャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<ReservationResponse>> cancelMyReservation(
            @PathVariable Long reservationId,
            @Valid @RequestBody CancelReservationRequest request) {
        ReservationResponse response = reservationService.cancelByUser(SecurityUtils.getCurrentUserId(), reservationId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
