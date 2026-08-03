package com.mannschaft.app.common.i18n;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.translation.SupportedLanguage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 対応言語一覧を返す公開 API。
 * 認証不要（SecurityConfig の permitAll 対象エンドポイントに追加すること）。
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 1 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig — requestMatchers("/api/i18n/**").permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * 返却するのは<b>全ユーザー共通の対応言語マスタ</b>のみ。未ログインのランディング／ログイン画面が言語切替に使うため公開が必要で、
 * 個人データ・テナント固有データを一切含まない。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic("/api/i18n/**")
@RestController
@RequestMapping("/api/i18n")
public class SupportedLocalesController {

    /**
     * F11.3 UI i18n で対応する言語一覧を返す。
     * フロントエンドの言語選択 UI で使用する。
     *
     * GET /api/i18n/supported-locales
     */
    @GetMapping("/supported-locales")
    public ApiResponse<List<Map<String, String>>> getSupportedLocales() {
        // F11.3 対応6言語（pt は F11.2 の多言語コンテンツ用のため除外）
        List<String> f113Locales = List.of("ja", "en", "zh", "ko", "es", "de");

        List<Map<String, String>> locales = Arrays.stream(SupportedLanguage.values())
                .filter(lang -> f113Locales.contains(lang.getCode()))
                .map(lang -> Map.of(
                        "code", lang.getCode(),
                        "nativeName", lang.getDisplayName()
                ))
                .toList();

        return ApiResponse.of(locales);
    }
}
