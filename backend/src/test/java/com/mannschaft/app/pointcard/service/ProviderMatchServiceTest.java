package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.PointCardProviderSynonymEntity;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import com.mannschaft.app.pointcard.event.ProviderCacheRefreshEvent;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import com.mannschaft.app.pointcard.repository.PointCardProviderSynonymRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link ProviderMatchService} の単体テスト。
 *
 * <p>設計書 §7.6.3 のマッチ例を中心に、表記揺れの正規化が同一プロバイダーに
 * 紐づくこと、キャッシュ更新イベントで再ロードされること、
 * Phase 4 P4-S2A の 2 段フォールバック（同義語辞書）が機能することを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProviderMatchService 単体テスト")
class ProviderMatchServiceTest {

    @Mock
    private PointCardProviderRepository providerRepository;

    @Mock
    private PointCardProviderSynonymRepository synonymRepository;

    @InjectMocks
    private ProviderMatchService providerMatchService;

    /**
     * 「dポイント」プロバイダーを作る共通ヘルパー。
     * code は半角英小文字、display_name は半角英大文字＋カタカナで保存される想定。
     */
    private PointCardProviderEntity buildDpoint() {
        PointCardProviderEntity p = PointCardProviderEntity.builder()
                .code("dpoint")
                .displayName("dポイント")
                .category(PointCardCategory.OTHER)
                .type(PointCardProviderType.EXTERNAL)
                .brandColor("#CC0033")
                .active(Boolean.TRUE)
                .build();
        // UuidV7CharEntity の id は通常 @PrePersist で発番されるが、
        // テストでは ReflectionTestUtils で直接セットして findById のキーと一致させる。
        ReflectionTestUtils.setField(p, "id", DPOINT_ID);
        return p;
    }

    private PointCardProviderEntity buildTokyu() {
        PointCardProviderEntity p = PointCardProviderEntity.builder()
                .code("tokyu_point")
                .displayName("東急ポイント")
                .category(PointCardCategory.RETAIL)
                .type(PointCardProviderType.EXTERNAL)
                .brandColor("#E60012")
                .active(Boolean.TRUE)
                .build();
        ReflectionTestUtils.setField(p, "id", TOKYU_ID);
        return p;
    }

    /** 固定 ID（テスト内でシノニムの参照先と一致させるため）。 */
    private static final UUID DPOINT_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID TOKYU_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID VPOINT_ID = UUID.fromString("00000000-0000-7000-8000-000000000003");

    /**
     * シノニム生成ヘルパー。
     */
    private PointCardProviderSynonymEntity buildSynonym(UUID providerId, String display, String normalized) {
        return PointCardProviderSynonymEntity.builder()
                .providerId(providerId)
                .synonymDisplay(display)
                .synonymNormalized(normalized)
                .build();
    }

    @BeforeEach
    void setUp() {
        given(providerRepository.findAllByActiveTrueOrderByCategoryAscDisplayNameAsc())
                .willReturn(List.of(buildDpoint(), buildTokyu()));
        given(synonymRepository.findAllByOrderByProviderIdAscSynonymNormalizedAsc())
                .willReturn(Collections.emptyList());
        providerMatchService.init();
    }

    @Nested
    @DisplayName("正規化マッチング: 表記揺れが同一プロバイダーに紐づくこと")
    class FuzzyMatching {

        @Test
        @DisplayName("全角『Ｄポイント』は dポイントにマッチする")
        void fullWidthD_matchesDpoint() {
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("Ｄポイント");
            assertThat(result).isPresent();
            assertThat(result.get().getCode()).isEqualTo("dpoint");
        }

        @Test
        @DisplayName("半角『dポイント』は dポイントにマッチする")
        void halfWidthD_matchesDpoint() {
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("dポイント");
            assertThat(result).isPresent();
            assertThat(result.get().getCode()).isEqualTo("dpoint");
        }

        @Test
        @DisplayName("『D-Point』はハイフン削除＋小文字化で code に直接マッチする")
        void hyphenated_matchesViaCode() {
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("D-Point");
            assertThat(result).isPresent();
            assertThat(result.get().getCode()).isEqualTo("dpoint");
        }

        @Test
        @DisplayName("ひらがな『ｄぽいんと』（全角英）は dポイントにマッチする")
        void halfWidthKana_matchesDpoint() {
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("ｄぽいんと");
            assertThat(result).isPresent();
            assertThat(result.get().getCode()).isEqualTo("dpoint");
        }

        @Test
        @DisplayName("『D point』は空白削除で dポイントにマッチする")
        void spaceSeparated_matchesDpoint() {
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("D point");
            assertThat(result).isPresent();
            assertThat(result.get().getCode()).isEqualTo("dpoint");
        }

        @Test
        @DisplayName("未登録カード『未登録カード』は Optional.empty を返す")
        void unknownInput_returnsEmpty() {
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("未登録カード");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("null 入力は Optional.empty を返す")
        void nullInput_returnsEmpty() {
            assertThat(providerMatchService.matchProvider(null)).isEmpty();
        }

        @Test
        @DisplayName("空白のみの入力は Optional.empty を返す")
        void blankInput_returnsEmpty() {
            assertThat(providerMatchService.matchProvider("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("正規化関数 normalize")
    class NormalizeMethod {

        @Test
        @DisplayName("NFKC + カタカナ→ひらがな + 記号削除 + lower の順序適用")
        void normalize_appliesAllStepsInOrder() {
            // Ｄポイント (全角 D + カタカナ) → NFKC で D + ポイント → 半角小文字 d + ひらがな ぽいんと
            assertThat(ProviderMatchService.normalize("Ｄポイント")).isEqualTo("dぽいんと");
            assertThat(ProviderMatchService.normalize("D-Point")).isEqualTo("dpoint");
            assertThat(ProviderMatchService.normalize("D ポイント")).isEqualTo("dぽいんと");
        }

        @Test
        @DisplayName("null は空文字列を返す")
        void normalize_nullReturnsEmpty() {
            assertThat(ProviderMatchService.normalize(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("キャッシュ更新")
    class CacheRefresh {

        @Test
        @DisplayName("ProviderCacheRefreshEvent 受信時にキャッシュが再構築される")
        void onProviderCacheRefresh_reloadsCache() {
            // 初期状態は dpoint と tokyu_point の 2 件
            assertThat(providerMatchService.matchProvider("dポイント")).isPresent();
            assertThat(providerMatchService.matchProvider("楽天ポイント")).isEmpty();

            // 新しい楽天プロバイダーが追加された状態に変更
            PointCardProviderEntity rakuten = PointCardProviderEntity.builder()
                    .code("rakuten")
                    .displayName("楽天ポイント")
                    .category(PointCardCategory.RETAIL)
                    .type(PointCardProviderType.EXTERNAL)
                    .active(Boolean.TRUE)
                    .build();
            given(providerRepository.findAllByActiveTrueOrderByCategoryAscDisplayNameAsc())
                    .willReturn(List.of(buildDpoint(), buildTokyu(), rakuten));

            // イベント発火でリビルド
            providerMatchService.onProviderCacheRefresh(new ProviderCacheRefreshEvent());

            // 楽天が引けるようになる
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("楽天ポイント");
            assertThat(result).isPresent();
            assertThat(result.get().getCode()).isEqualTo("rakuten");
        }

        @Test
        @DisplayName("無効化されたプロバイダーはキャッシュから消える")
        void onProviderCacheRefresh_removesInactiveProviders() {
            // dpoint がマッチする状態から…
            assertThat(providerMatchService.matchProvider("dポイント")).isPresent();

            // 残ったのは tokyu_point のみ
            given(providerRepository.findAllByActiveTrueOrderByCategoryAscDisplayNameAsc())
                    .willReturn(List.of(buildTokyu()));
            providerMatchService.onProviderCacheRefresh(new ProviderCacheRefreshEvent());

            assertThat(providerMatchService.matchProvider("dポイント")).isEmpty();
            assertThat(providerMatchService.matchProvider("東急ポイント")).isPresent();
        }
    }

    @Nested
    @DisplayName("同義語辞書フォールバック (Phase 4 P4-S2A)")
    class SynonymFallback {

        /**
         * シノニム経由マッチ: 「ドコモポイント」→ dpoint プロバイダー返却。
         */
        @Test
        @DisplayName("シノニム『ドコモポイント』は dpoint にマッチする")
        void synonymDocomoPoint_matchesDpoint() {
            // 「ドコモポイント」の正規化 = どこもぽいんと
            String normalizedKey = ProviderMatchService.normalize("ドコモポイント");
            given(synonymRepository.findAllByOrderByProviderIdAscSynonymNormalizedAsc())
                    .willReturn(List.of(buildSynonym(DPOINT_ID, "ドコモポイント", normalizedKey)));
            given(providerRepository.findById(DPOINT_ID))
                    .willReturn(Optional.of(buildDpoint()));

            providerMatchService.onProviderCacheRefresh(new ProviderCacheRefreshEvent());

            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("ドコモポイント");
            assertThat(result).isPresent();
            assertThat(result.get().getCode()).isEqualTo("dpoint");
        }

        /**
         * シノニム経由マッチ: 「Tポイント」→ vpoint プロバイダー返却。
         * Tポイントは 2024 年に V ポイント統合済みのため運営プロバイダーは vpoint。
         */
        @Test
        @DisplayName("シノニム『Tポイント』は vpoint にマッチする")
        void synonymTPoint_matchesVpoint() {
            // vpoint プロバイダーは正規化インデックスには含まれない（synonyms 経由のみで解決）
            PointCardProviderEntity vpoint = PointCardProviderEntity.builder()
                    .code("vpoint")
                    .displayName("Vポイント")
                    .category(PointCardCategory.OTHER)
                    .type(PointCardProviderType.EXTERNAL)
                    .active(Boolean.TRUE)
                    .build();
            ReflectionTestUtils.setField(vpoint, "id", VPOINT_ID);

            given(providerRepository.findAllByActiveTrueOrderByCategoryAscDisplayNameAsc())
                    .willReturn(List.of(buildDpoint(), buildTokyu(), vpoint));
            String normalizedKey = ProviderMatchService.normalize("Tポイント");
            given(synonymRepository.findAllByOrderByProviderIdAscSynonymNormalizedAsc())
                    .willReturn(List.of(buildSynonym(VPOINT_ID, "Tポイント", normalizedKey)));
            given(providerRepository.findById(VPOINT_ID))
                    .willReturn(Optional.of(vpoint));

            providerMatchService.onProviderCacheRefresh(new ProviderCacheRefreshEvent());

            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("Tポイント");
            assertThat(result).isPresent();
            assertThat(result.get().getCode()).isEqualTo("vpoint");
        }

        /**
         * シノニム未登録: 「適当な単語」→ Optional.empty()
         */
        @Test
        @DisplayName("シノニム未登録の入力は Optional.empty を返す")
        void unknownSynonym_returnsEmpty() {
            given(synonymRepository.findAllByOrderByProviderIdAscSynonymNormalizedAsc())
                    .willReturn(List.of(buildSynonym(DPOINT_ID, "ドコモポイント",
                            ProviderMatchService.normalize("ドコモポイント"))));
            providerMatchService.onProviderCacheRefresh(new ProviderCacheRefreshEvent());

            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("適当な未登録カード");
            assertThat(result).isEmpty();
        }

        /**
         * is_active=false プロバイダーへのシノニム → Optional.empty()
         */
        @Test
        @DisplayName("is_active=false プロバイダーへのシノニムマッチは除外される")
        void synonymToInactiveProvider_returnsEmpty() {
            // is_active=false の dpoint を返す
            PointCardProviderEntity inactiveDpoint = PointCardProviderEntity.builder()
                    .code("dpoint")
                    .displayName("dポイント")
                    .category(PointCardCategory.OTHER)
                    .type(PointCardProviderType.EXTERNAL)
                    .active(Boolean.FALSE)
                    .build();
            ReflectionTestUtils.setField(inactiveDpoint, "id", DPOINT_ID);

            String normalizedKey = ProviderMatchService.normalize("ドコモポイント");
            given(synonymRepository.findAllByOrderByProviderIdAscSynonymNormalizedAsc())
                    .willReturn(List.of(buildSynonym(DPOINT_ID, "ドコモポイント", normalizedKey)));
            given(providerRepository.findById(DPOINT_ID))
                    .willReturn(Optional.of(inactiveDpoint));

            providerMatchService.onProviderCacheRefresh(new ProviderCacheRefreshEvent());

            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("ドコモポイント");
            assertThat(result).isEmpty();
        }

        /**
         * ProviderCacheRefreshEvent 後にシノニムキャッシュもリビルド。
         */
        @Test
        @DisplayName("ProviderCacheRefreshEvent で synonym キャッシュも再構築される")
        void onRefresh_rebuildsSynonymIndex() {
            // 初期は synonyms 空
            assertThat(providerMatchService.matchProvider("ドコモポイント")).isEmpty();

            // 新たに synonyms を投入してリフレッシュ
            String normalizedKey = ProviderMatchService.normalize("ドコモポイント");
            given(synonymRepository.findAllByOrderByProviderIdAscSynonymNormalizedAsc())
                    .willReturn(List.of(buildSynonym(DPOINT_ID, "ドコモポイント", normalizedKey)));
            given(providerRepository.findById(DPOINT_ID))
                    .willReturn(Optional.of(buildDpoint()));

            providerMatchService.onProviderCacheRefresh(new ProviderCacheRefreshEvent());

            // synonym 経由でマッチするようになる
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("ドコモポイント");
            assertThat(result).isPresent();
            assertThat(result.get().getCode()).isEqualTo("dpoint");
        }

        /**
         * 完全一致 > シノニム の優先順位。
         * provider.display_name と同義語が衝突した場合は provider 直マッチ優先。
         */
        @Test
        @DisplayName("provider 直マッチがシノニムより優先される")
        void directMatchTakesPriorityOverSynonym() {
            // dpoint と衝突するシノニム（誤って tokyu に紐付け）を登録
            String normalizedDpoint = ProviderMatchService.normalize("dポイント");
            given(synonymRepository.findAllByOrderByProviderIdAscSynonymNormalizedAsc())
                    .willReturn(List.of(buildSynonym(TOKYU_ID, "dポイント (誤紐付け)", normalizedDpoint)));

            providerMatchService.onProviderCacheRefresh(new ProviderCacheRefreshEvent());

            // 「dポイント」は provider 直マッチで dpoint を返す（tokyu ではない）
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("dポイント");
            assertThat(result).isPresent();
            assertThat(result.get().getCode()).isEqualTo("dpoint");
        }
    }
}
