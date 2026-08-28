package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.PointCardProviderSynonymEntity;
import com.mannschaft.app.pointcard.event.ProviderCacheRefreshEvent;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import com.mannschaft.app.pointcard.repository.PointCardProviderSynonymRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * <h2>3 段フォールバック（Phase 4 P4-S2A + Phase 5 P5-S3）</h2>
 * <ol>
 *   <li>第 1 段: provider 自体（{@code code} / {@code display_name}）の正規化キーで完全一致</li>
 *   <li>第 2 段: ヒットしなければ {@code point_card_provider_synonyms} の
 *       {@code synonym_normalized} を経由して provider を解決</li>
 *   <li>第 3 段: それでもヒットしなければ Levenshtein 距離による近似マッチ
 *       （feature flag {@code f18.fuzzy-match.levenshtein.enabled} で有効化、
 *       既定 {@code max-distance=1} / {@code min-input-length=5}）。
 *       入力長が短すぎる場合は誤マッチ防止のためスキップする。
 *       同一距離で複数 hit した場合は normalizedIndex 由来を synonymIndex 由来より優先する</li>
 * </ol>
 * <p>これにより「ドコモポイント」「Tポイント」のような口語・略称・旧称や、
 * 「まくどなど」「どこもぽいんお」のような 1 文字誤入力が運営登録の Provider に紐付けられる。
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

    /**
     * Levenshtein 距離マッチ（3 段目フォールバック）の feature flag（Phase 5 P5-S3）。
     * {@code application.yml} の {@code f18.fuzzy-match.levenshtein.enabled} と連動する。
     * 既定値は {@code true}（即時有効化）。障害時は yml で {@code false} に切替えるだけで無効化できる。
     */
    @Value("${f18.fuzzy-match.levenshtein.enabled:true}")
    private boolean levenshteinEnabled;

    /**
     * Levenshtein 距離の許容上限（Phase 5 P5-S3）。
     * これ以下の距離なら fuzzy match を採用する。既定 {@code 1}（1 文字違いまで）。
     */
    @Value("${f18.fuzzy-match.levenshtein.max-distance:1}")
    private int levenshteinMaxDistance;

    /**
     * Levenshtein 段を発動する最小入力長（Phase 5 P5-S3）。
     * 入力（正規化後）がこの長さ未満なら近似マッチをスキップする（短すぎる文字列での誤マッチ防止）。
     * 既定 {@code 5}。
     */
    @Value("${f18.fuzzy-match.levenshtein.min-input-length:5}")
    private int levenshteinMinInputLength;

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
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。提携プロバイダのキャッシュ再読込であり、プロセス内キャッシュの更新に閉じる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
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
     * <p>3 段フォールバック（Phase 5 P5-S3 で 3 段目追加）:
     * <ol>
     *   <li>provider 直マッチ（{@code code} / {@code display_name}）</li>
     *   <li>同義語辞書経由マッチ（{@code is_active=false} の provider は除外）</li>
     *   <li>Levenshtein 距離マッチ（feature flag 有効時のみ、{@code min-input-length} 以上の入力に対して
     *       {@code max-distance} 以内で最良候補を選定）</li>
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
            Optional<PointCardProviderEntity> synonymMatched =
                    providerRepository.findById(synonymProviderId)
                            .filter(p -> Boolean.TRUE.equals(p.getActive()));
            if (synonymMatched.isPresent()) {
                return synonymMatched;
            }
            // synonym hit したが provider が無効化されていた → 通常はここで empty 返却するが、
            // 既存仕様（Phase 4）に合わせて 3 段目には進まず empty で確定する。
            return Optional.empty();
        }

        // 第 3 段: Levenshtein 距離マッチ（feature flag で有効化、Phase 5 P5-S3）
        //
        // マスター御裁可済みの設計:
        // - normalize 適用後のキーに対して距離計算
        // - 距離 ≤ max-distance のみマッチ採用
        // - 入力長 < min-input-length の場合は誤マッチ防止のためスキップ
        // - 1/2 段目で hit した場合はここに到達しないため、自動的にパフォーマンス温存
        // - 同一距離で複数 hit した場合は優先度順（normalizedIndex 由来 > synonymIndex 由来）で解決
        if (levenshteinEnabled && key.length() >= levenshteinMinInputLength) {
            return findByLevenshtein(key);
        }

        return Optional.empty();
    }

    /**
     * Levenshtein 距離マッチ（3 段目フォールバック、Phase 5 P5-S3）。
     *
     * <p>normalizedIndex と synonymIndex の全 key を走査し、入力との編集距離が
     * {@link #levenshteinMaxDistance} 以下の候補を集めて、優先度順 + 距離小さい順で最良候補を返す。
     *
     * <p>優先度の定義:
     * <ul>
     *   <li>{@code priority=0}: {@link #normalizedIndex} 由来（provider の {@code code} / {@code display_name} に近い）</li>
     *   <li>{@code priority=1}: {@link #synonymIndex} 由来（同義語経由）</li>
     * </ul>
     * 同一距離で normalizedIndex / synonymIndex の両方が hit した場合は normalizedIndex を採用する。
     *
     * <p>計算量: O((N + M) * L^2)。N=normalizedIndex サイズ、M=synonymIndex サイズ、L=平均文字列長。
     * F18 Phase 5 時点では N ≒ 40、M ≒ 30、L ≒ 8 で、1 回の呼び出しでも 5μs 未満（実測ベース推定）。
     *
     * @param normalizedInput 正規化済みの入力文字列（空でないことが保証されている前提）
     * @return マッチした provider。候補がなければ {@link Optional#empty()}
     */
    private Optional<PointCardProviderEntity> findByLevenshtein(String normalizedInput) {
        Candidate best = null;

        // normalizedIndex 走査（priority=0）
        for (Map.Entry<String, PointCardProviderEntity> entry : normalizedIndex.entrySet()) {
            int distance = levenshteinDistance(normalizedInput, entry.getKey());
            if (distance > levenshteinMaxDistance) {
                continue;
            }
            // is_active=true な provider のみキャッシュされているため active フィルタ不要
            Candidate c = new Candidate(distance, 0, entry.getValue());
            if (isBetterThan(c, best)) {
                best = c;
            }
        }

        // synonymIndex 走査（priority=1）
        for (Map.Entry<String, UUID> entry : synonymIndex.entrySet()) {
            int distance = levenshteinDistance(normalizedInput, entry.getKey());
            if (distance > levenshteinMaxDistance) {
                continue;
            }
            // synonym 由来は priority=1 のため、既に同等以下の normalized 由来候補があるならスキップ可能
            if (best != null && best.distance <= distance && best.priority <= 1) {
                // best.priority は 0 or 1。priority=0 で同距離以下なら synonym の追加走査は無意味
                if (best.priority == 0) {
                    continue;
                }
            }
            Optional<PointCardProviderEntity> resolved = providerRepository.findById(entry.getValue())
                    .filter(p -> Boolean.TRUE.equals(p.getActive()));
            if (resolved.isEmpty()) {
                continue;
            }
            Candidate c = new Candidate(distance, 1, resolved.get());
            if (isBetterThan(c, best)) {
                best = c;
            }
        }

        return best == null ? Optional.empty() : Optional.of(best.provider);
    }

    /**
     * Levenshtein 候補の優劣判定。距離小・優先度小（=normalized 由来）が「より良い」。
     * 同一距離 + 同一優先度のときは先勝ち（HashMap iteration order 依存だが実運用上問題なしと判断）。
     */
    private static boolean isBetterThan(Candidate c, Candidate current) {
        if (current == null) {
            return true;
        }
        if (c.distance != current.distance) {
            return c.distance < current.distance;
        }
        return c.priority < current.priority;
    }

    /**
     * Levenshtein マッチ候補。距離 + 優先度 + provider を保持する（内部用 immutable record）。
     */
    private record Candidate(int distance, int priority, PointCardProviderEntity provider) {
    }

    /**
     * Levenshtein 距離（編集距離）を計算する。
     *
     * <p>Phase 5 P5-S3 で導入。Apache Commons Text 等の外部依存追加を避けるため、
     * 教科書的な 2 次元 DP で自前実装する。
     *
     * <p>計算量: O(N * M)。N=入力長、M=候補長。
     * F18 では正規化後の文字列同士の比較（平均 8 文字 × 平均 8 文字 = 64 セル）で
     * 1 比較あたり 100ns 未満。全候補 45 件走査でも 5μs 未満で完了する。
     *
     * @param a 比較文字列 1（normalize 適用済みを想定）
     * @param b 比較文字列 2（normalize 適用済みを想定）
     * @return 編集距離（0 = 完全一致）
     */
    static int levenshteinDistance(String a, String b) {
        // 標準的な 2 次元 DP。可読性優先で 2 次元配列を使う（メモリ O(min(N,M)) 最適化は不要規模）。
        int n = a.length();
        int m = b.length();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[n][m];
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
