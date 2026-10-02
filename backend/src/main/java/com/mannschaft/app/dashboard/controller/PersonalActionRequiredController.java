package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.dashboard.dto.PersonalActionRequiredResponse;
import com.mannschaft.app.dashboard.service.PersonalActionRequiredService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 個人ダッシュボード: 全チーム・組織横断「要対応」集計コントローラー。
 *
 * <p>ユーザーが所属する全チーム・全組織の未処理アイテム（回覧板/アンケート/出席確認）を
 * スコープ情報（scopeType/scopeSlug/scopeName）付きのフラットリストで返す。</p>
 *
 * <p>認証必須。未認証の場合は {@link com.mannschaft.app.common.SecurityUtils#getCurrentUserId()}
 * が {@code BusinessException(COMMON_000)} を投げ、{@link com.mannschaft.app.common.GlobalExceptionHandler}
 * が 401 を返す。</p>
 *
 * <p>設計書: docs/features/F22.1_swipe_scope_dashboard / 個人横断「要対応」API 仕様
 * 受け入れ条件: AC-10 〜 AC-15</p>
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "ダッシュボード")
@RequiredArgsConstructor
public class PersonalActionRequiredController {

    private final PersonalActionRequiredService personalActionRequiredService;

    /**
     * 個人横断「要対応」集計を取得する。
     *
     * <p>ユーザーが所属する全チーム・全組織の未処理アイテムをスコープ情報付きで返す。
     * 特定スコープでエラーが起きても他スコープのデータは正常に返す（縮退設計・AC-12）。</p>
     *
     * @return 全スコープの要対応アイテムフラットリストと合計件数
     */
    @GetMapping("/action-required")
    @Operation(
            summary = "個人横断「要対応」集計",
            description = "所属する全チーム・組織の回覧板/アンケート/出席確認の未対応アイテムをスコープ情報付きで返す。"
                    + "1スコープがエラーでも他スコープは返す縮退設計（AC-12）。認証必須（未認証は401）。"
    )
    public ResponseEntity<ApiResponse<PersonalActionRequiredResponse>> getPersonalActionRequired() {
        Long userId = SecurityUtils.getCurrentUserId();
        PersonalActionRequiredResponse response =
                personalActionRequiredService.getPersonalActionRequired(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
