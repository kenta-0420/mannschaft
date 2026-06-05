package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.GuardianshipHandoverInitiateRequest;
import com.mannschaft.app.auth.dto.GuardianshipSwitchRequest;
import com.mannschaft.app.auth.dto.IndependenceStatusResponse;
import com.mannschaft.app.auth.dto.SwitchableChildrenResponse;
import com.mannschaft.app.auth.guardianship.GuardianshipHandoverService;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final GuardianshipHandoverService guardianshipHandoverService;

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

    // ========================================
    // 自立移行（F08.9 P3c-2・02_api_design §2.3）
    // ========================================

    /**
     * 子の自立移行ステータスを取得する（02_api_design §2.3）。
     *
     * <p>保護者が「子がいつ自立段階に入るか（封印境界日）」「引き継ぎ（パスワード設定）が済んでいるか」を
     * 把握するために用いる。呼び出し元が当該子の有効な保護者でない場合は 403（IDOR 防止）。</p>
     *
     * @param childUserId 対象の子のユーザーID（パス）
     * @throws com.mannschaft.app.common.BusinessException
     *         有効な保護者リンクなし（{@code GUARDIANSHIP_LINK_NOT_FOUND} / 403）
     */
    @GetMapping("/children/{childUserId}/independence-status")
    @Operation(summary = "自立移行ステータス取得",
            description = "子の現在段階・封印境界日・パスワード設定有無を返す（有効な保護者のみ・IDOR 防止）")
    public ResponseEntity<ApiResponse<IndependenceStatusResponse>> getIndependenceStatus(
            @PathVariable Long childUserId) {
        Long guardianUserId = SecurityUtils.getCurrentUserId();
        IndependenceStatusResponse response =
                guardianshipSwitchService.getIndependenceStatus(guardianUserId, childUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 自立移行の引き継ぎを開始する（子のメールへパスワード設定リンクを送付・02_api_design §2.3）。
     *
     * <p>本操作は保護者本人の権原で行うため、後見切替セッション（acting-as）中は 403 で拒否する
     * （03_security §3.2 の精神）。子がメール未登録の場合のみ {@code childEmail} を指定して登録できる。</p>
     *
     * @param childUserId 対象の子のユーザーID（パス）
     * @param request     子メール（任意・body 全体も省略可）
     * @param httpRequest レートリミット用 IP 取得
     * @throws com.mannschaft.app.common.BusinessException
     *         リンクなし / acting-as 中（403）・メール解決不能 / 上書き要求 / 重複（400）
     */
    @PostMapping("/children/{childUserId}/handover/initiate")
    @Operation(summary = "自立移行の引き継ぎ開始",
            description = "子のメール宛にパスワード設定リンクを送付する（有効な保護者のみ・acting-as 中は不可）")
    public ResponseEntity<Void> initiateHandover(
            @PathVariable Long childUserId,
            @Valid @RequestBody(required = false) GuardianshipHandoverInitiateRequest request,
            HttpServletRequest httpRequest) {
        Long guardianUserId = SecurityUtils.getCurrentUserId();
        String childEmail = request != null ? request.childEmail() : null;
        String ipAddress = httpRequest.getRemoteAddr();
        guardianshipHandoverService.initiateHandover(guardianUserId, childUserId, childEmail, ipAddress);
        return ResponseEntity.noContent().build();
    }
}
