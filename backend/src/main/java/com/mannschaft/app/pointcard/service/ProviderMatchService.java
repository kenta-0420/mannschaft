package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.PointCardProviderSynonymEntity;
import com.mannschaft.app.pointcard.event.ProviderCacheRefreshEvent;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import com.mannschaft.app.pointcard.repository.PointCardProviderSynonymRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ユーザー入力 {@code display_name} を運営プロバイダーマスタと突き合わせる fuzzy match サービス。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §7.6
 *
 * <p>起動時に {@code is_active=true} のプロバイダーを全件取得し、
 * {@code code} と {@code display_name} の **正規化結果** をキーとして
 * {@code Map<String, PointCardProviderEntity>} を構築する。
 * 検索時は入力文字列を同じ正規化ルールにかけて完全一致でルックアップする
 * （O(1) かつメモリ数十 KB）。
 *
 * <h2>2 段フォールバック（Phase 4 P4-S2A 追加）</h2>
 * <ol>
 *   <li>第 1 段: provider 自体（{@code code} / {@code display_name}）の正規化キーで完全一致</li>
 *   <li>第 2 段: ヒットしなければ {@code point_card_provider_synonyms} の
 *       {@code synonym_normalized} を経由して provider を解決</li>
 * </ol>
 * <p>これにより「ドコモポイント」「Tポイント」のような口語・略称・旧称が
 * 運営登録の Provider に紐付けられる。
 *
 * <h2>正規化ステップ（順序固定、§7.6.1）</h2>
 * <ol>
 *   <li>NFKC 正規化（全角 → 半角統一）</li>
 *   <li>カタカナ → ひらがな変換</li>
 *   <li>記号・空白削除</li>
 *   <li>ASCII 英字を小文字化</li>
 * </ol>
 *
 * <h2>スレッド安全性</h2>
 * <p>{@code volatile} フィールドで読み取り側はロックフリー、書き込みは
 * {@code synchronized} で全置換する（読み取り 99.99% / 書き込み 0.01% の
 * ワークロードに最適化）。
 *
 * <h2>キャッシュ更新</h2>
 * <p>SYSTEM_ADMIN がプロバイダーを追加・編集・無効化した際に
 * {@link ProviderCacheRefreshEvent} が発火する。本サービスはそれを購読して
 * provider / synonym の両キャッシュを全件再ロードする。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderMatchService {

    private final PointCardProviderRepository providerRepository;
    private final PointCardProviderSynonymRepository synonymRepository;

    /** 正規化キー → プロバイダーのインデックス。書き込み時は synchronized で全置換。 */
    private volatile Map<String, PointCardProviderEntity> normalizedIndex = Map.of();

    /**
     * 同義語の正規化キー → プロバイダー ID のインデックス。
     * provider entity 自体ではなく ID を保持し、マッチ時に provider repository から
     * 解決する（provider 側の更新で stale な参照を持たないため）。
     */
    private volatile Map<String, UUID> synonymIndex = Map.of();

    /**
     * 起動時に有効化されているプロバイダーと同義語辞書を全件読み込み、
     * 正規化インデックスを構築する。
     */
    @PostConstruct
    public void init() {
        loadCache();
    }

    /**
     * プロバイダーマスタ更新通知を受信したら provider / synonym 両キャッシュを再構築する。
     */
    @EventListener(ProviderCacheRefreshEvent.class)
    public void onProviderCacheRefresh(ProviderCacheRefreshEvent event) {
        log.info("ProviderCacheRefreshEvent を受信しました。fuzzy match キャッシュを再構築します");
        loadCache();
    }

    /**
     * 全件再読込してインデックスを置き換える。
     * 読み取り側は volatile 経由で常に過去 or 新しいインデックスを参照する（中間状態は見えない）。
     */
    public synchronized void loadCache() {
        rebuildProviderIndex();
        rebuildSynonymIndex();
    }

    /**
     * provider マスタを再読込してインデックスを置き換える。
     */
    private void rebuildProviderIndex() {
        List<PointCardProviderEntity> all =
                providerRepository.findAllByActiveTrueOrderByCategoryAscDisplayNameAsc();
        Map<String, PointCardProviderEntity> idx = new HashMap<>(all.size() * 2);
        for (PointCardProviderEntity p : all) {
            String nCode = normalize(p.getCode());
            String nName = normalize(p.getDisplayName());
            // 衝突時は最初に登録された方を優先する（同じプロバイダー内の code / display_name 重複は無害）
            if (!nCode.isEmpty()) {
                idx.putIfAbsent(nCode, p);
            }
            if (!nName.isEmpty()) {
                idx.putIfAbsent(nName, p);
            }
        }
        this.normalizedIndex = Map.copyOf(idx);
        log.info("ProviderMatchService provider キャッシュ構築完了: providers={}, normalizedKeys={}",
                all.size(), this.normalizedIndex.size());
    }

    /**
     * 同義語辞書を再読込してインデックスを置き換える。
     *
     * <p>順序は {@code provider_id ASC, synonym_normalized ASC} で固定し、
     * 同一の正規化キーが複数登録されている場合も {@code putIfAbsent} により
     * 最古登録（決定論的）が採用される。
     */
    private void rebuildSynonymIndex() {
        List<PointCardProviderSynonymEntity> all =
                synonymRepository.findAllByOrderByProviderIdAscSynonymNormalizedAsc();
        Map<String, UUID> idx = new HashMap<>(all.size());
        for (PointCardProviderSynonymEntity syn : all) {
            String key = syn.getSynonymNormalized();
            if (key != null && !key.isEmpty()) {
                idx.putIfAbsent(key, syn.getProviderId());
            }
        }
        this.synonymIndex = Map.copyOf(idx);
        log.info("ProviderMatchService synonym キャッシュ構築完了: synonyms={}, normalizedKeys={}",
                all.size(), this.synonymIndex.size());
    }

    /**
     * ユーザー入力を正規化してマスタに対して fuzzy match する。
     *
     * <p>2 段フォールバック:
     * <ol>
     *   <li>provider 直マッチ（{@code code} / {@code display_name}）</li>
     *   <li>同義語辞書経由マッチ（{@code is_active=false} の provider は除外）</li>
     * </ol>
     *
     * @param userInput ユーザー入力（カード名）。null / 空文字は常に空で返す
     * @return マッチしたプロバイダー。マッチしない場合は {@link Optional#empty()}
     */
    public Optional<PointCardProviderEntity> matchProvider(String userInput) {
        if (userInput == null) {
            return Optional.empty();
        }
        String key = normalize(userInput);
        if (key.isEmpty()) {
            return Optional.empty();
        }

        // 第 1 段: provider 直マッチ（code or display_name）
        PointCardProviderEntity direct = normalizedIndex.get(key);
        if (direct != null) {
            return Optional.of(direct);
        }

        // 第 2 段: 同義語辞書経由
        UUID synonymProviderId = synonymIndex.get(key);
        if (synonymProviderId != null) {
            return providerRepository.findById(synonymProviderId)
                    .filter(p -> Boolean.TRUE.equals(p.getActive()));
        }

        return Optional.empty();
    }

    /**
     * 正規化処理（§7.6.1）。テストおよび他クラスからの呼び出し用に公開する。
     *
     * <p>順序: NFKC → カタカナ→ひらがな → 記号削除 → lower。
     */
    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        // 1. NFKC 正規化（全角英数記号 → 半角に統一）
        String n = Normalizer.normalize(input, Normalizer.Form.NFKC);
        // 2. カタカナ → ひらがな
        n = katakanaToHiragana(n);
        // 3. 記号・空白削除
        n = n.replaceAll("[\\s\\-_./:]", "");
        // 4. lower 化
        return n.toLowerCase(Locale.ROOT);
    }

    /**
     * カタカナ（U+30A1〜U+30F6）をひらがな（U+3041〜U+3096）に変換する。
     * それ以外の文字はそのまま残す。
     */
    private static String katakanaToHiragana(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x30A1 && c <= 0x30F6) {
                sb.append((char) (c - 0x60));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * テスト用: 現在の provider キャッシュサイズを返す。
     */
    int currentCacheSize() {
        return normalizedIndex.size();
    }

    /**
     * テスト用: 現在の synonym キャッシュサイズを返す。
     */
    int currentSynonymCacheSize() {
        return synonymIndex.size();
    }
}
