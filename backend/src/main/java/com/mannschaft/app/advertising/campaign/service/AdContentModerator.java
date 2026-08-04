package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdNgWord;
import com.mannschaft.app.advertising.campaign.enums.AdNgWordSeverity;
import com.mannschaft.app.advertising.campaign.repository.AdNgWordRepository;
import com.mannschaft.app.advertising.campaign.service.moderation.DetectedNgWord;
import com.mannschaft.app.advertising.campaign.service.moderation.ModerationCheckResult;
import com.mannschaft.app.advertising.campaign.service.moderation.SuggestedModerationAction;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * F09.17 Phase 11-b 自動 NG 検知サービス。
 *
 * <p>submit 状態遷移時に {@code body_markdown} 群を {@code ad_ng_words} の有効辞書と
 * 突合し、検出ヒットを返す。判定ロジックは大文字小文字無視 + 単純部分文字列照合
 * ({@code String#toLowerCase().contains()})。コードブロックや HTML エスケープによる
 * NG 回避を防ぐため、Markdown 本文は前処理なしでそのまま照合する。</p>
 *
 * <p>辞書取得は Spring Cache {@code @Cacheable(value="adNgWords")} で
 * RedisConfig のデフォルト TTL (30分) でキャッシュされる。
 * SYSTEM_ADMIN が辞書を更新した際は明示的に evict すること (将来の UI 拡張で対応)。</p>
 */
@Service
@RequiredArgsConstructor
public class AdContentModerator {

    /** Spring Cache 用キャッシュ名。{@code mannschaft:cache:adNgWords::active} に格納される。 */
    public static final String CACHE_NAME = "adNgWords";

    private final AdNgWordRepository adNgWordRepository;

    /**
     * 自己プロキシ参照（issue #2544）。{@code @Cacheable} は Spring AOP プロキシ経由でのみ作用する。
     * 旧実装は {@link #getActiveNgWords} を {@code public} にすれば自己呼び出しでも効くと
     * Javadoc で主張していたが、<b>public 化だけではプロキシを通らない</b>ため
     * {@code adNgWords} キャッシュは一度も発火していなかった（唯一の呼び出し元が同一クラス内）。
     * 循環参照を避けるため {@code @Lazy} を付けたフィールド注入とする。
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private AdContentModerator self;

    /**
     * 本文を NG 辞書と突合し、検出ヒットと推奨アクションを返す。
     *
     * <p>本文が {@code null} または空文字の場合はヒット 0 件で {@code AUTO_PASS} を返す。</p>
     *
     * <p>判定ルール:
     * <ul>
     *   <li>{@code BLOCK} ワード 1 件以上 → {@code AUTO_BLOCK}</li>
     *   <li>{@code BLOCK} 0 件 + {@code WARN} 1 件以上 → {@code AUTO_FLAG}</li>
     *   <li>すべて 0 件 → {@code AUTO_PASS}</li>
     * </ul>
     * </p>
     */
    public ModerationCheckResult check(String bodyMarkdown) {
        if (bodyMarkdown == null || bodyMarkdown.isEmpty()) {
            return new ModerationCheckResult(List.of(), SuggestedModerationAction.AUTO_PASS);
        }

        // issue #2544: 自己プロキシ経由で呼ぶ（this. だと @Cacheable が発火しない）。
        List<AdNgWord> dictionary = self.getActiveNgWords();
        String lower = bodyMarkdown.toLowerCase();

        List<DetectedNgWord> detected = new ArrayList<>();
        boolean hasBlock = false;
        boolean hasWarn = false;

        for (AdNgWord ngWord : dictionary) {
            String word = ngWord.getWord();
            if (word == null || word.isEmpty()) {
                continue;
            }
            // 大文字小文字無視: 辞書側も lower 化して contains 判定
            if (lower.contains(word.toLowerCase())) {
                detected.add(new DetectedNgWord(word, ngWord.getCategory(), ngWord.getSeverity()));
                if (ngWord.getSeverity() == AdNgWordSeverity.BLOCK) {
                    hasBlock = true;
                } else if (ngWord.getSeverity() == AdNgWordSeverity.WARN) {
                    hasWarn = true;
                }
            }
        }

        SuggestedModerationAction action;
        if (hasBlock) {
            action = SuggestedModerationAction.AUTO_BLOCK;
        } else if (hasWarn) {
            action = SuggestedModerationAction.AUTO_FLAG;
        } else {
            action = SuggestedModerationAction.AUTO_PASS;
        }
        return new ModerationCheckResult(detected, action);
    }

    /**
     * 有効な NG 辞書を取得 (Spring Cache でキャッシュ)。
     *
     * <p>{@code @Cacheable} は Spring AOP プロキシ経由でのみ作用するため、
     * 同一クラス内の {@link #check} からは自己プロキシ {@code self} を通して呼ぶこと。
     * <b>public 化だけではプロキシをバイパスしたままで発火しない</b>（issue #2544 で是正）。</p>
     *
     * <p>戻り値の {@code List} は Spring Data が返す可変 {@code ArrayList} であり、
     * {@code AdNgWord} は {@code @Setter} を持つため Valkey から復元できる
     * （{@code CacheValueSerializationRoundTripTest} が実シリアライザで往復検証する）。</p>
     */
    @Cacheable(value = CACHE_NAME, key = "'active'")
    public List<AdNgWord> getActiveNgWords() {
        return adNgWordRepository.findByIsActiveTrue();
    }
}
