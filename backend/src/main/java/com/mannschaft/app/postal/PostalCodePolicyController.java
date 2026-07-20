package com.mannschaft.app.postal;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.postal.dto.PostalCodePolicyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 郵便番号検証ポリシー公開 API（F02.10 §391）。
 *
 * <p>対応国の郵便番号フォーマット規則（正規表現・入力例）を返す<strong>認証不要</strong>
 * エンドポイント。新規登録画面（未ログイン）でも対応国判定・フォーマット検証に使うため
 * {@code permitAll}（SecurityConfig）で公開する。返すのは郵便番号フォーマット規則のみで
 * 機微情報は含まない。フロントエンドはこのレスポンスを単一の真実源とする。</p>
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 1 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig.java:227 — requestMatchers(GET, "/api/v1/postal-code/policies").permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * 返却するのは<b>郵便番号のフォーマット規則（バリデーションポリシー）のみ</b>で機微情報を含まない。未ログインの register
 * 画面が参照する FE の単一真実源のため公開必須。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic
@RestController
@Tag(name = "郵便番号検証ポリシー 公開 API (F02.10)")
@RequiredArgsConstructor
public class PostalCodePolicyController {

    private final PostalCodePolicyRegistry registry;

    /**
     * 対応国の郵便番号検証ポリシー一覧を返す。
     *
     * @return 対応国ポリシー一覧
     */
    @GetMapping("/api/v1/postal-code/policies")
    @Operation(
            summary = "郵便番号検証ポリシー一覧（未ログイン公開）",
            description = "郵便番号の検証に対応する国ごとのフォーマット規則（正規表現）と入力例を返す。"
                    + "対応国では郵便番号が必須・フォーマット検証あり。未対応国はリストに含まれない。")
    public ApiResponse<List<PostalCodePolicyResponse>> getPolicies() {
        List<PostalCodePolicyResponse> policies = registry.all().stream()
                .map(PostalCodePolicyResponse::from)
                .toList();
        return ApiResponse.of(policies);
    }
}
