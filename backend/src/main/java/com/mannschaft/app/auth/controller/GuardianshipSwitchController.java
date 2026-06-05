package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.GuardianshipSwitchRequest;
import com.mannschaft.app.auth.dto.SwitchableChildrenResponse;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F08.9 P3a/P3c 後見切替コントローラー。
 *
 * <p>認証ユーザー（保護者）が後見切替できる子の取得（P3a）と、後見切替セッションの
 * 開始/終了（P3c・02_api_design §2.2）を提供する。</p>
 *
 * <ul>
 *   <li>{@code GET    /api/v1/me/guardianship/switchable-children}（P3a §2.1）</li>
 *   <li>{@code POST   /api/v1/me/guardianship/switch}（P3c §2.2 切替開始）</li>
 *   <li>{@code DELETE /api/v1/me/guardianship/switch}（P3c §2.2 切替終了）</li>
 * </ul>
 *
 * <p>払い手（保護者）は常に {@code SecurityUtils.getCurrentUserId()}（自分）に固定し、
 * 他人の保護者一覧を覗く / 他人になりすます経路は提供しない（IDOR 防止・03_security §2/§3）。
 * 切替はサーバ側ステートレス（セッションテーブルを持たない）。開始/終了は検証＋監査記録のみで、
 * 以降クライアントが {@code X-Proxy-For-User-Id} を保持し、毎リクエストを
 * {@link com.mannschaft.app.proxy.ProxyInputContextFilter} の後見切替拡張が再検証する。</p>
 */
@RestController
@RequestMapping("/api/v1/me/guardianship")
@Tag(name = "後見切替")
@RequiredArgsConstructor
public class GuardianshipSwitchController {

    private final GuardianshipSwitchService guardianshipSwitchService;

    /**
     * 認証ユーザー（保護者）が後見切替できる子の一覧を取得する。
     * 保護者リンクはあるが年齢ポリシーで封印された子は {@code blockedChildren} に分離して返す。
     */
    @GetMapping("/switchable-children")
    @Operation(summary = "切替可能な子の一覧取得",
            description = "認証ユーザー（保護者）が後見切替できる子と、年齢到達で封印された子を取得する")
    public ResponseEntity<ApiResponse<SwitchableChildrenResponse>> getSwitchableChildren() {
        Long guardianUserId = SecurityUtils.getCurrentUserId();
        SwitchableChildrenResponse response =
                guardianshipSwitchService.listSwitchableChildren(guardianUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 後見切替セッションを開始する（02_api_design §2.2）。
     * 保護者リンク有効性と年齢ゲート（{@code switchAllowed}）を検証し、監査を二重記録する。
     * 成功で 204（以降クライアントが {@code X-Proxy-For-User-Id=childUserId} を保持）。
     *
     * @throws com.mannschaft.app.common.BusinessException
     *         リンクなし（{@code GUARDIANSHIP_LINK_NOT_FOUND} / 403）
     *         または年齢封印（{@code GUARDIANSHIP_SWITCH_AGE_LOCKED} / 403）
     */
    @PostMapping("/switch")
    @Operation(summary = "後見切替開始",
            description = "保護者リンクと年齢ゲートを検証し、子としての代理セッションを開始する（サーバ側ステートレス）")
    public ResponseEntity<Void> startSwitch(@Valid @RequestBody GuardianshipSwitchRequest request) {
        Long guardianUserId = SecurityUtils.getCurrentUserId();
        guardianshipSwitchService.startSwitch(guardianUserId, request.childUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 後見切替セッションを終了する（本人へ復帰・02_api_design §2.2）。
     * サーバ側ステートレスのため監査記録のみ。常に 204。
     *
     * @param childUserId 切替を終了する子のユーザーID（クエリパラメータ）
     */
    @DeleteMapping("/switch")
    @Operation(summary = "後見切替終了",
            description = "後見切替セッションを終了し本人へ復帰する（監査記録のみ・サーバ側ステートレス）")
    public ResponseEntity<Void> endSwitch(
            @org.springframework.web.bind.annotation.RequestParam("childUserId") Long childUserId) {
        Long guardianUserId = SecurityUtils.getCurrentUserId();
        guardianshipSwitchService.endSwitch(guardianUserId, childUserId);
        return ResponseEntity.noContent().build();
    }
}
