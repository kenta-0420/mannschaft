package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import com.mannschaft.app.pointcard.event.ProviderCacheRefreshEvent;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link ProviderMatchService} の単体テスト。
 *
 * <p>設計書 §7.6.3 のマッチ例を中心に、表記揺れの正規化が同一プロバイダーに
 * 紐づくこと、キャッシュ更新イベントで再ロードされることを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProviderMatchService 単体テスト")
class ProviderMatchServiceTest {

    @Mock
    private PointCardProviderRepository providerRepository;

    @InjectMocks
    private ProviderMatchService providerMatchService;

    /**
     * 「dポイント」プロバイダーを作る共通ヘルパー。
     * code は半角英小文字、display_name は半角英大文字＋カタカナで保存される想定。
     */
    private PointCardProviderEntity buildDpoint() {
        return PointCardProviderEntity.builder()
                .code("dpoint")
                .displayName("dポイント")
                .category(PointCardCategory.OTHER)
                .type(PointCardProviderType.EXTERNAL)
                .brandColor("#CC0033")
                .active(Boolean.TRUE)
                .build();
    }

    private PointCardProviderEntity buildTokyu() {
        return PointCardProviderEntity.builder()
                .code("tokyu_point")
                .displayName("東急ポイント")
                .category(PointCardCategory.RETAIL)
                .type(PointCardProviderType.EXTERNAL)
                .brandColor("#E60012")
                .active(Boolean.TRUE)
                .build();
    }

    @BeforeEach
    void setUp() {
        given(providerRepository.findAllByActiveTrueOrderByCategoryAscDisplayNameAsc())
                .willReturn(List.of(buildDpoint(), buildTokyu()));
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
}
