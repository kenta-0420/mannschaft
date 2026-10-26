package com.mannschaft.app.incidentbanner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 障害告知バナーの自動翻訳オーケストレーター。
 *
 * <p>原文（ja）の同期保存後、管理者操作をブロックしないよう
 * 非同期（{@code @Async("event-pool")}）で各対象言語を翻訳し、
 * {@link IncidentBannerService#upsertTranslation} で個別 Tx 境界に保存する。</p>
 *
 * <p><b>設計上の注意（既知の地雷回避）:</b>
 * <ul>
 *   <li>{@code @Async} は自己呼び出し（self-invocation）ではプロキシが効かないため、
 *       翻訳生成を {@link IncidentBannerService} とは別の Bean に切り出している。</li>
 *   <li>AFTER_COMMIT で書き込む {@code @TransactionalEventListener} に素の
 *       {@code @Transactional(REQUIRED)} を付けると ApplicationContext がロード不能になり
 *       全 SpringBootTest を巻き添えにする事故があるため、本実装ではイベントリスナーを使わず
 *       同期メソッドからの明示的な非同期呼び出し方式を採る。各 upsert は
 *       {@link IncidentBannerService#upsertTranslation} 内の {@code @Transactional}
 *       により独立した Tx 境界で実行される。</li>
 * </ul>
 * </p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IncidentBannerTranslationOrchestrator {

    /** 自動翻訳の対象言語（原文 ja を除く 5 言語）。 */
    private static final List<String> TARGET_LANGUAGES = List.of("en", "zh", "ko", "es", "de");

    private final IncidentBannerTranslationProvider translationProvider;
    private final IncidentBannerService bannerService;

    /**
     * 指定バナーの原文を各対象言語へ非同期翻訳し、翻訳メッセージを保存する。
     *
     * <p>原文（ja）の保存は呼び出し側で同期完了済み。本メソッドは追加言語のみを扱う。
     * 翻訳に失敗した言語はスキップされ（読出時に originalLanguage へフォールバック）、
     * バナーは機能し続ける。</p>
     *
     * @param bannerId         バナーID
     * @param originalText     原文メッセージ
     * @param originalLanguage 原文の言語コード（通常 "ja"）
     */
    @Async("event-pool")
    public void generateAndStoreTranslations(UUID bannerId, String originalText,
                                             String originalLanguage) {
        if (originalText == null || originalText.isBlank()) {
            return;
        }

        List<String> targetLangs = TARGET_LANGUAGES.stream()
                .filter(lang -> !lang.equalsIgnoreCase(originalLanguage))
                .toList();
        if (targetLangs.isEmpty()) {
            return;
        }

        try {
            Map<String, String> translations =
                    translationProvider.translate(originalText, originalLanguage, targetLangs);

            translations.forEach((lang, message) -> {
                try {
                    bannerService.upsertTranslation(bannerId, lang, message);
                } catch (Exception e) {
                    // 1 言語の保存失敗が他言語を巻き込まないよう個別に握る（ただし記録は残す）。
                    log.warn("障害告知バナー翻訳の保存に失敗しました。bannerId={}, lang={}, error={}",
                            bannerId, lang, e.getMessage(), e);
                }
            });

            log.info("障害告知バナーの自動翻訳完了。bannerId={}, 保存言語={}",
                    bannerId, translations.keySet());

        } catch (Exception e) {
            // 翻訳全体の失敗も症状を隠さず記録する。バナー自体は原文で機能する。
            log.warn("障害告知バナーの自動翻訳処理でエラーが発生しました。bannerId={}, error={}",
                    bannerId, e.getMessage(), e);
        }
    }
}
