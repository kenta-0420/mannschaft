package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.dashboard.dto.PersonalAdminActionRequiredResponse;
import com.mannschaft.app.dashboard.service.PersonalAdminActionRequiredService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 個人ダッシュボード: 全チーム・組織横断「承認待ち」集計コントローラー（司令塔第二弾）。
 *
 * <p>複数チーム/組織を管理するユーザー（ADMIN/DEPUTY_ADMIN）向けに、自身が管理する全スコープの
 * 承認待ちアイテム（予約承認/シフトリクエスト/マッチング応募/未収請求）をスコープ情報付きの
 * フラットリストで返す。{@link PersonalActionRequiredController}（「私が回答/確認すべきこと」・
 * 全メンバー向け）とは<b>別エンドポイント・別サービス・別認可</b>。</p>
 *
 * <p>認証必須。未認証の場合は {@link com.mannschaft.app.common.SecurityUtils#getCurrentUserId()}
 * が {@code BusinessException(COMMON_000)} を投げ、{@link com.mannschaft.app.common.GlobalExceptionHandler}
 * が 401 を返す。ADMIN/DEPUTY_ADMIN のスコープを 1 つも持たないユーザーは空配列 200（AC-B1-3）。</p>
 *
 * <p>受け入れ条件: AC-B1-1 〜 AC-B1-6（ADHD-UX戦役第四陣第二弾「承認待ち横断集約」）</p>
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "ダッシュボード（管理者承認待ち）")
@RequiredArgsConstructor
public class PersonalAdminActionRequiredController {

    private final PersonalAdminActionRequiredService personalAdminActionRequiredService;

    /**
     * 個人横断「承認待ち」集計を取得する。
     *
     * <p>ユーザーが ADMIN/DEPUTY_ADMIN として管理する全チーム・全組織の承認待ちアイテムを
     * スコープ情報付きで返す。特定スコープでエラーが起きても他スコープのデータは正常に返す
     * （縮退設計・AC-B1-4）。</p>
     *
     * @return 全管理スコープの承認待ちアイテムフラットリストと実合計件数
     */
    @GetMapping("/admin-action-required")
    @Operation(
            summary = "個人横断「承認待ち」集計",
            description = "ADMIN/DEPUTY_ADMIN として管理する全チーム・組織の予約承認/シフトリクエスト/"
                    + "マッチング応募/未収請求の承認待ちアイテムをスコープ情報付きで返す。"
                    + "1スコープがエラーでも他スコープは返す縮退設計（AC-B1-4）。認証必須（未認証は401）。"
                    + "管理スコープを持たないユーザーは空配列200（AC-B1-3）。"
    )
    public ResponseEntity<ApiResponse<PersonalAdminActionRequiredResponse>> getPersonalAdminActionRequired() {
        Long userId = SecurityUtils.getCurrentUserId();
        PersonalAdminActionRequiredResponse response =
                personalAdminActionRequiredService.getPersonalAdminActionRequired(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
