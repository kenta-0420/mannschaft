package com.mannschaft.app.postal;

import com.mannschaft.app.common.ApiResponse;
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
 */
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
