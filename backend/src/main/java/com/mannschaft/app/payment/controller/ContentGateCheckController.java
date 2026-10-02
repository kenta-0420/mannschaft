package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.ContentGateAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F08.9 P4: ペイウォール判定コントローラー（設計書 F08.9 02 §6 / 03_security §4）。
 *
 * <h3>認可方針（IDOR 防止）</h3>
 * <ul>
 *   <li>ログイン必須：viewer は {@code SecurityUtils.getCurrentUserId()} で確定する
 *       （未認証は {@code CommonErrorCode.COMMON_000} → 401）。</li>
 *   <li><b>P4 は「自分自身の閲覧可否」のみを判定する</b>。クエリで {@code beneficiaryUserId} を受け取らず、
 *       常にログインユーザーを受益者キーとする。他人（beneficiaryUserId）の支払い状態を問い合わせる経路は
 *       代理払い認可の框組み（P2/P3）が整うまで開けない＝<b>支払い状態の列挙による IDOR を防ぐ</b>
 *       （03_security §4「閲覧者自身の支払い状態のみ評価」）。</li>
 * </ul>
 *
 * <p>エンドポイント数: 1（GET check）</p>
 */
@RestController
@RequestMapping("/api/v1/content-gates")
@Tag(name = "ペイウォール判定", description = "F08.9 P4 受益者キー判定（自分自身の閲覧可否）")
@RequiredArgsConstructor
public class ContentGateCheckController {

    private final ContentGateAccessService contentGateAccessService;

    /**
     * 指定コンテンツに対するログインユーザー本人のペイウォール解錠可否を判定する（設計書 02 §6）。
     *
     * <p>viewer（受益者キー）は {@code SecurityUtils.getCurrentUserId()} で確定し、
     * クエリパラメータでは受け付けない（IDOR 防止・03_security §4）。</p>
     *
     * @param contentType コンテンツ種別（POST/FILE/ANNOUNCEMENT/SCHEDULE 等）
     * @param contentId   コンテンツ ID
     * @return 200 OK + {@link GateCheckResponse}（accessible / titleHidden / requiredItems）
     */
    @SelfScopedEndpoint("viewerUserId は SecurityUtils.getCurrentUserId() のみで決まり、"
            + "クエリで受け取らないため他人の受益者キーを指定する余地が構造的に無い（check メソッド本体）")
    @GetMapping("/check")
    @Operation(summary = "ペイウォール判定（F08.9 P4・自分自身の閲覧可否）")
    public ResponseEntity<ApiResponse<GateCheckResponse>> check(
            @RequestParam String contentType,
            @RequestParam Long contentId) {

        Long viewerUserId = SecurityUtils.getCurrentUserId();
        GateCheckResponse response = contentGateAccessService.check(contentType, contentId, viewerUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
