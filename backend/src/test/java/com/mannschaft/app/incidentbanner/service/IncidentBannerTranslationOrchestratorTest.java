package com.mannschaft.app.incidentbanner.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link IncidentBannerTranslationOrchestrator} の単体テスト。
 *
 * <p>Provider をモックして「ja 原文 → en/zh/ko/es/de の 5 言語が upsert される」流れを検証する。
 * {@code @Async} は単体テストでは直接メソッド呼び出し（同期）で検証する。
 * upsert は {@link IncidentBannerService#upsertTranslation} 内の {@code @CacheEvict} により
 * 翻訳保存ごとにキャッシュが無効化される（本テストは upsert 呼び出しの発火を verify する）。</p>
 */
@DisplayName("IncidentBannerTranslationOrchestrator 単体テスト")
@ExtendWith(MockitoExtension.class)
class IncidentBannerTranslationOrchestratorTest {

    @Mock
    private IncidentBannerTranslationProvider translationProvider;

    @Mock
    private IncidentBannerService bannerService;

    @InjectMocks
    private IncidentBannerTranslationOrchestrator orchestrator;

    @Test
    @DisplayName("ja 原文から en/zh/ko/es/de の 5 言語が upsert される")
    void ja原文から5言語upsertされる() {
        UUID bannerId = UUID.randomUUID();
        Map<String, String> translations = new LinkedHashMap<>();
        translations.put("en", "Disruption.");
        translations.put("zh", "故障。");
        translations.put("ko", "장애.");
        translations.put("es", "Interrupción.");
        translations.put("de", "Störung.");
        when(translationProvider.translate(eq("障害です"), eq("ja"), anyList()))
                .thenReturn(translations);

        orchestrator.generateAndStoreTranslations(bannerId, "障害です", "ja");

        // 原文(ja)を除く 5 言語ぶん upsert される
        verify(translationProvider).translate(eq("障害です"), eq("ja"),
                eq(List.of("en", "zh", "ko", "es", "de")));
        verify(bannerService).upsertTranslation(bannerId, "en", "Disruption.");
        verify(bannerService).upsertTranslation(bannerId, "zh", "故障。");
        verify(bannerService).upsertTranslation(bannerId, "ko", "장애.");
        verify(bannerService).upsertTranslation(bannerId, "es", "Interrupción.");
        verify(bannerService).upsertTranslation(bannerId, "de", "Störung.");
        verify(bannerService, times(5)).upsertTranslation(any(), any(), any());
    }

    @Test
    @DisplayName("原文言語が en の場合は対象から en を除外して翻訳依頼する")
    void 原文言語enは対象から除外される() {
        UUID bannerId = UUID.randomUUID();
        when(translationProvider.translate(any(), any(), anyList())).thenReturn(Map.of());

        orchestrator.generateAndStoreTranslations(bannerId, "Disruption.", "en");

        verify(translationProvider).translate(eq("Disruption."), eq("en"),
                eq(List.of("zh", "ko", "es", "de")));
    }

    @Test
    @DisplayName("Provider が空 Map を返したら upsert は呼ばれない（翻訳失敗時はフォールバック任せ）")
    void 翻訳結果が空ならupsertされない() {
        UUID bannerId = UUID.randomUUID();
        when(translationProvider.translate(any(), any(), anyList())).thenReturn(Map.of());

        orchestrator.generateAndStoreTranslations(bannerId, "障害です", "ja");

        verify(bannerService, never()).upsertTranslation(any(), any(), any());
    }

    @Test
    @DisplayName("原文が空白なら Provider も upsert も呼ばれない")
    void 原文空白なら何もしない() {
        UUID bannerId = UUID.randomUUID();

        orchestrator.generateAndStoreTranslations(bannerId, "   ", "ja");
        orchestrator.generateAndStoreTranslations(bannerId, null, "ja");

        verifyNoInteractions(translationProvider);
        verifyNoInteractions(bannerService);
    }

    @Test
    @DisplayName("1 言語の upsert が例外を投げても他言語の保存は継続される（症状は記録しつつ握る）")
    void 一部upsert失敗でも他言語は継続() {
        UUID bannerId = UUID.randomUUID();
        Map<String, String> translations = new LinkedHashMap<>();
        translations.put("en", "Disruption.");
        translations.put("zh", "故障。");
        when(translationProvider.translate(any(), any(), anyList())).thenReturn(translations);
        when(bannerService.upsertTranslation(bannerId, "en", "Disruption."))
                .thenThrow(new RuntimeException("db down"));

        orchestrator.generateAndStoreTranslations(bannerId, "障害です", "ja");

        // en で失敗しても zh の upsert は試みられる
        verify(bannerService).upsertTranslation(bannerId, "zh", "故障。");
    }
}
