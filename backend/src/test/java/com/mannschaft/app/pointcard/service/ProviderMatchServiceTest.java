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
    private static final UUID MCDONALD_ID = UUID.fromString("00000000-0000-7000-8000-000000000004");
    private static final UUID DOCOMO_SYN_OWNER_ID = UUID.fromString("00000000-0000-7000-8000-000000000005");

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

    /**
     * マクドナルド プロバイダー（Levenshtein テスト用、距離 1 マッチ検証）。
     * normalize 後 = まくどなるど（6 文字）。「まくどなど」（5 文字、距離 1）でヒットさせる用。
     */
    private PointCardProviderEntity buildMcdonald() {
        PointCardProviderEntity p = PointCardProviderEntity.builder()
                .code("mcdonalds")
                .displayName("マクドナルド")
                .category(PointCardCategory.RETAIL)
                .type(PointCardProviderType.EXTERNAL)
                .brandColor("#FFC72C")
                .active(Boolean.TRUE)
                .build();
        ReflectionTestUtils.setField(p, "id", MCDONALD_ID);
        return p;
    }

    @Nested
    @DisplayName("Levenshtein 距離マッチ (Phase 5 P5-S3)")
    class LevenshteinFallback {

        /**
         * 各テストで明示的に Levenshtein 設定を投入する（@Value 注入は MockitoExtension では効かないため）。
         * 既定: enabled=true, max-distance=1, min-input-length=5（application.yml と同値）。
         */
        @BeforeEach
        void enableLevenshtein() {
            ReflectionTestUtils.setField(providerMatchService, "levenshteinEnabled", true);
            ReflectionTestUtils.setField(providerMatchService, "levenshteinMaxDistance", 1);
            ReflectionTestUtils.setField(providerMatchService, "levenshteinMinInputLength", 5);

            // マクドナルドを含む 3 件のマスタで再ロード
            given(providerRepository.findAllByActiveTrueOrderByCategoryAscDisplayNameAsc())
                    .willReturn(List.of(buildDpoint(), buildTokyu(), buildMcdonald()));
            given(synonymRepository.findAllByOrderByProviderIdAscSynonymNormalizedAsc())
                    .willReturn(Collections.emptyList());
            providerMatchService.onProviderCacheRefresh(new ProviderCacheRefreshEvent());
        }

        @Test
        @DisplayName("enabled=true + 距離 1 でマクドナルドにマッチする（『まくどなど』）")
        void enabled_distance1_matchesMcdonald() {
            // normalize 後: 入力「まくどなど」(5 文字) vs マスタ「まくどなるど」(6 文字) = 距離 1
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("まくどなど");
            assertThat(result).isPresent();
            assertThat(result.get().getCode()).isEqualTo("mcdonalds");
        }

        @Test
        @DisplayName("enabled=true でも距離 2 はマッチしない（『まくどなるもの』）")
        void enabled_distance2_doesNotMatch() {
            // normalize 後: 入力「まくどなるもの」(7 文字) vs マスタ「まくどなるど」(6 文字) = 距離 2
            // （末尾「ど」→「もの」で 1 置換 + 1 挿入 = 2）
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("まくどなるもの");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("enabled=false なら距離 1 候補があっても Levenshtein スキップ")
        void disabled_skipsLevenshtein() {
            ReflectionTestUtils.setField(providerMatchService, "levenshteinEnabled", false);
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("まくどなど");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("入力長 < min-input-length なら Levenshtein スキップ（min=5 で 4 文字入力）")
        void shortInput_skipsLevenshtein() {
            // min-input-length=5 のため 4 文字入力は近似マッチ対象外
            // 「とぽいん」(4 文字)。マスタ「dぽいんと」(5 文字)、「とうきゅうぽいんと」(9 文字) いずれも候補だが
            // ガードでスキップされ Optional.empty が返る。
            ReflectionTestUtils.setField(providerMatchService, "levenshteinMinInputLength", 5);
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("ぽいん");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("1 段目 normalized で hit する入力は完全一致を返す（Levenshtein 段は呼ばれない）")
        void exactMatch_skipsLevenshtein() {
            // 完全一致入力。Levenshtein 段に到達せず確実に dpoint を返す。
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("dポイント");
            assertThat(result).isPresent();
            assertThat(result.get().getCode()).isEqualTo("dpoint");
        }

        @Test
        @DisplayName("2 段目 synonym で hit する入力はシノニム経由を返す（Levenshtein 段は呼ばれない）")
        void synonymExactMatch_skipsLevenshtein() {
            // synonym「ドコモポイント」を dpoint に紐付け
            String normalizedKey = ProviderMatchService.normalize("ドコモポイント");
            given(synonymRepository.findAllByOrderByProviderIdAscSynonymNormalizedAsc())
                    .willReturn(List.of(buildSynonym(DPOINT_ID, "ドコモポイント", normalizedKey)));
            given(providerRepository.findById(DPOINT_ID))
                    .willReturn(Optional.of(buildDpoint()));
            providerMatchService.onProviderCacheRefresh(new ProviderCacheRefreshEvent());

            // 完全一致の synonym 入力は 2 段目で hit する
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("ドコモポイント");
            assertThat(result).isPresent();
            assertThat(result.get().getCode()).isEqualTo("dpoint");
        }

        @Test
        @DisplayName("synonym 経由の距離 1 マッチ（『どこもぽいんお』→ dpoint）")
        void synonymLevenshteinMatch() {
            // synonym normalized = 「どこもぽいんと」(7 文字)、入力「どこもぽいんお」(7 文字) → 距離 1
            String normalizedKey = ProviderMatchService.normalize("ドコモポイント"); // = どこもぽいんと
            given(synonymRepository.findAllByOrderByProviderIdAscSynonymNormalizedAsc())
                    .willReturn(List.of(buildSynonym(DPOINT_ID, "ドコモポイント", normalizedKey)));
            given(providerRepository.findById(DPOINT_ID))
                    .willReturn(Optional.of(buildDpoint()));
            providerMatchService.onProviderCacheRefresh(new ProviderCacheRefreshEvent());

            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("どこもぽいんお");
            assertThat(result).isPresent();
            assertThat(result.get().getCode()).isEqualTo("dpoint");
        }

        @Test
        @DisplayName("優先度順: 距離 1 で normalized 由来と synonym 由来が両方 hit したら normalized を返す")
        void priority_normalizedBeatsSynonym() {
            // normalized「まくどなるど」(6) と入力「まくどなるも」(6) は距離 1（末尾「ど→も」置換）
            // 同時に別 provider (DOCOMO_SYN_OWNER_ID) に synonym「まくどなるみ」を紐付け → 入力との距離も 1
            // 期待: normalized 由来の mcdonalds が返る。
            PointCardProviderEntity decoy = PointCardProviderEntity.builder()
                    .code("decoy_provider")
                    .displayName("囮プロバイダー")
                    .category(PointCardCategory.OTHER)
                    .type(PointCardProviderType.EXTERNAL)
                    .active(Boolean.TRUE)
                    .build();
            ReflectionTestUtils.setField(decoy, "id", DOCOMO_SYN_OWNER_ID);

            given(providerRepository.findAllByActiveTrueOrderByCategoryAscDisplayNameAsc())
                    .willReturn(List.of(buildDpoint(), buildTokyu(), buildMcdonald(), decoy));
            given(synonymRepository.findAllByOrderByProviderIdAscSynonymNormalizedAsc())
                    .willReturn(List.of(buildSynonym(DOCOMO_SYN_OWNER_ID, "まくどなるみ", "まくどなるみ")));
            // 実装上は normalizedIndex を先に走査するため、normalized 由来が best に入った時点で
            // synonym 候補の findById は呼ばれない（早期 continue で省略）。
            // したがって providerRepository.findById(DOCOMO_SYN_OWNER_ID) の stub は不要。
            providerMatchService.onProviderCacheRefresh(new ProviderCacheRefreshEvent());

            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("まくどなるも");
            assertThat(result).isPresent();
            assertThat(result.get().getCode())
                    .as("normalized 由来 (mcdonalds) が synonym 由来 (decoy_provider) より優先される")
                    .isEqualTo("mcdonalds");
        }

        @Test
        @DisplayName("半角全角混在 + Levenshtein（全角『ｍａｃｄｏｎａｄ』→ mcdonalds の code 経由）")
        void fullWidthAlphabet_withLevenshtein() {
            // NFKC 後: 「macdonad」(8 文字)。マスタ code「mcdonalds」normalize→「mcdonalds」(9 文字)
            // 距離: m-c-d-o-n-a-d  vs  m-c-d-o-n-a-l-d-s
            // 「macdonad」(8) vs 「mcdonalds」(9) は実際の距離が複雑なので分かりやすい例に変更:
            // 全角「ｍｃｄｏｎａｌｄｓ」入力 → NFKC で「mcdonalds」完全一致（1 段目 hit）になってしまう。
            // ここでは「全角入力 + 1 文字違い」を検証するため、全角『ｍｃｄｏｎａｌｄ』(末尾 s 抜き) を入れる
            // → NFKC「mcdonald」(8 文字) vs マスタ「mcdonalds」(9 文字) = 距離 1（末尾 s 削除のみ）
            Optional<PointCardProviderEntity> result =
                    providerMatchService.matchProvider("ｍｃｄｏｎａｌｄ");
            assertThat(result).isPresent();
            assertThat(result.get().getCode()).isEqualTo("mcdonalds");
        }

        @Test
        @DisplayName("null 入力は Levenshtein 段に到達せず Optional.empty を返す")
        void nullInput_returnsEmptyBeforeLevenshtein() {
            assertThat(providerMatchService.matchProvider(null)).isEmpty();
        }

        @Test
        @DisplayName("空白のみ入力は Levenshtein 段に到達せず Optional.empty を返す")
        void blankInput_returnsEmptyBeforeLevenshtein() {
            assertThat(providerMatchService.matchProvider("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("levenshteinDistance ヘルパー")
    class LevenshteinDistanceHelper {

        @Test
        @DisplayName("完全一致は距離 0")
        void identical_returnsZero() {
            assertThat(ProviderMatchService.levenshteinDistance("abc", "abc")).isZero();
        }

        @Test
        @DisplayName("1 文字置換は距離 1")
        void substitution_returnsOne() {
            assertThat(ProviderMatchService.levenshteinDistance("abc", "abd")).isEqualTo(1);
        }

        @Test
        @DisplayName("1 文字挿入は距離 1")
        void insertion_returnsOne() {
            assertThat(ProviderMatchService.levenshteinDistance("abc", "abcd")).isEqualTo(1);
        }

        @Test
        @DisplayName("1 文字削除は距離 1")
        void deletion_returnsOne() {
            assertThat(ProviderMatchService.levenshteinDistance("abcd", "abc")).isEqualTo(1);
        }

        @Test
        @DisplayName("空文字 vs 長さ N は距離 N")
        void emptyVsN_returnsN() {
            assertThat(ProviderMatchService.levenshteinDistance("", "abcd")).isEqualTo(4);
        }
    }
}
