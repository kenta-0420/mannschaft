package com.mannschaft.app.common.i18n;

import com.mannschaft.app.common.ApiResponse;
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
 */
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

        return ApiResponse.success(locales);
    }
}
