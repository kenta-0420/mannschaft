package com.mannschaft.app.activity.controller;

import com.mannschaft.app.activity.service.PublicActivityQueryService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.publicview.dto.PublicActivityDetail;
import com.mannschaft.app.publicview.dto.PublicActivitySummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F06.4 公開活動記録コントローラー。<b>認証不要</b>の SSR / SNS シェア用 API を提供する。
 *
 * <p>本 Controller は薄い受け皿に徹し、可視性判定・親スコープ検証・公開 DTO への詰め替えは
 * すべて {@link PublicActivityQueryService} に集約する（金型: {@code PublicTeamPostController}
 * → {@code PublicPostQueryService}）。</p>
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・5 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig —
 * requestMatchers(GET, "/api/v1/public/activities/&#42;"
 * / "/api/v1/public/teams/&#42;/activities" / "/api/v1/public/teams/&#42;/activities/&#42;"
 * / "/api/v1/public/organizations/&#42;/activities"
 * / "/api/v1/public/organizations/&#42;/activities/&#42;").permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * F06.4 公開活動記録は「チーム / 組織が対外的に活動実績を見せる」ための機能であり、
 * 未ログインの閲覧者（保護者候補・スポンサー・検索エンジン）に読まれることが要件そのものである。
 * 無認可で公開してよい根拠は以下の 3 点で担保している:
 * <ol>
 *   <li><b>親スコープが PUBLIC のものだけ</b>を返す（非公開 / 凍結 / 停止チーム・組織は一律 404）</li>
 *   <li><b>記録自身が visibility=PUBLIC かつ status=PUBLISHED のものだけ</b>を返す
 *       （MEMBERS_ONLY・下書き・論理削除済みは一律 404。403 を返すと存在オラクルになるため
 *       ステータスもボディも区別しない）</li>
 *   <li>返却項目は公開専用 DTO（{@link PublicActivityDetail} / {@link PublicActivitySummary}）の
 *       <b>8 項目のみ</b>で、作成者ユーザー ID・テンプレート入力値・添付・開催場所・内部リソース ID
 *       といった個人データ / テナント固有データを<b>一切含まない</b></li>
 * </ol>
 * さらに未認証の総当りは {@code PublicApiRateLimitFilter}（60 req/min/IP）で抑止する。</p>
 *
 * <p>認可根治戦役 監査済。<b>レスポンス項目が将来増えた場合は公開の妥当性が崩れうる</b>ため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること
 * （契約テスト {@code ActivityPublicContractIT} がホワイトリスト方式で機械的に守っている）。</p>
 */
@IntentionallyPublic({
        "/api/v1/public/activities/*",
        "/api/v1/public/teams/*/activities",
        "/api/v1/public/teams/*/activities/*",
        "/api/v1/public/organizations/*/activities",
        "/api/v1/public/organizations/*/activities/*"
})
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "公開活動記録", description = "F06.4 公開活動記録（認証不要・SSR / SNS シェア用）")
@RequiredArgsConstructor
public class ActivityPublicController {

    private final PublicActivityQueryService publicActivityQueryService;

    /**
     * 活動記録を ID で取得する（スコープ不問）。
     *
     * <p>F06.4 SNS シェア用。フロントエンドがスコープ（team/org）を意識せずに
     * {@code /activity/{id}} 形式の公開 URL から直接詳細を取得するためのエンドポイント。</p>
     */
    @GetMapping("/activities/{id}")
    @Operation(summary = "公開活動記録詳細（ID直引き）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "存在しない / 非公開 / 下書き / 削除済み / 親スコープが非公開（区別しない）")
    public ResponseEntity<ApiResponse<PublicActivityDetail>> getPublicActivityById(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(
                publicActivityQueryService.getPublicActivityById(id)));
    }

    /**
     * チーム公開活動記録一覧を取得する。
     *
     * <p><b>ページング方式</b>: {@code page}（0始まり・既定 0）と {@code limit} を受け取り
     * {@link PublicActivityQueryService#toPageable(int, int)} で {@link org.springframework.data.domain.Pageable}
     * に変換するオフセットページング。{@code page} 未指定時は従来どおり 0 ページ目（後方互換）。
     * 先例 PR #2598（{@code ActivityController#listActivities}）と同じ流儀:
     * {@code page} に負値を渡すと {@code PageRequest.of} が
     * {@link IllegalArgumentException} を投げ 500 に化ける口が開くため {@code @Min(0)} で 400 に倒す。
     * <b>本クラスに {@code @Validated} を付けてはならない</b>（付けると Spring 6.1+ の組込みメソッド検証
     * ではなく AOP プロキシ経由の従来型検証に切り替わり、standalone 試験環境では {@code @Min} が
     * 素通りして 500 に戻る。PR #2598 の教訓と同一）。</p>
     *
     * @param limit 取得件数（上限 {@code 100}・0 以下は既定 {@code 20} に丸める）
     * @param page  ページ番号（0始まり・既定 0）
     */
    @GetMapping("/teams/{teamId}/activities")
    @Operation(summary = "チーム公開活動記録一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "チームが存在しない / 非公開（区別しない）")
    public ResponseEntity<ApiResponse<List<PublicActivitySummary>>> listTeamPublicActivities(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") @Min(0) int page) {
        return ResponseEntity.ok(ApiResponse.of(
                publicActivityQueryService.listPublicTeamActivities(teamId, limit, page)));
    }

    /**
     * チーム公開活動記録詳細を取得する。
     *
     * <p>パス変数 {@code teamId} と記録の実スコープが一致しない場合は 404（スコープ詐称拒否）。</p>
     */
    @GetMapping("/teams/{teamId}/activities/{id}")
    @Operation(summary = "チーム公開活動記録詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "非公開 / 不在 / スコープ不一致（区別しない）")
    public ResponseEntity<ApiResponse<PublicActivityDetail>> getTeamPublicActivity(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(
                publicActivityQueryService.getPublicTeamActivity(teamId, id)));
    }

    /**
     * 組織公開活動記録一覧を取得する。
     *
     * <p>ページング方式・{@code @Validated} 禁止の理由は {@link #listTeamPublicActivities} と同じ。</p>
     *
     * @param limit 取得件数（上限 {@code 100}・0 以下は既定 {@code 20} に丸める）
     * @param page  ページ番号（0始まり・既定 0）
     */
    @GetMapping("/organizations/{orgId}/activities")
    @Operation(summary = "組織公開活動記録一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "組織が存在しない / 非公開（区別しない）")
    public ResponseEntity<ApiResponse<List<PublicActivitySummary>>> listOrgPublicActivities(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") @Min(0) int page) {
        return ResponseEntity.ok(ApiResponse.of(
                publicActivityQueryService.listPublicOrganizationActivities(orgId, limit, page)));
    }

    /**
     * 組織公開活動記録詳細を取得する。
     *
     * <p>パス変数 {@code orgId} と記録の実スコープが一致しない場合は 404（スコープ詐称拒否）。</p>
     */
    @GetMapping("/organizations/{orgId}/activities/{id}")
    @Operation(summary = "組織公開活動記録詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "非公開 / 不在 / スコープ不一致（区別しない）")
    public ResponseEntity<ApiResponse<PublicActivityDetail>> getOrgPublicActivity(
            @PathVariable Long orgId,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(
                publicActivityQueryService.getPublicOrganizationActivity(orgId, id)));
    }
}
