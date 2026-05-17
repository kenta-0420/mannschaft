package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdNgWord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * F09.17 Phase 11-b 自動 NG 辞書リポジトリ。
 *
 * <p>テナント横断のマスタテーブル扱い (CLAUDE.md 原則 6 マスタ例外には該当しないため
 * UUIDv7 を採用しているが、全テナント共通で参照される)。</p>
 *
 * <p>{@link #findByIsActiveTrue()} は {@code AdContentModerator} のキャッシュ層で
 * 1 時間 TTL で保持され、submit のたびに DB を叩かない設計。</p>
 */
public interface AdNgWordRepository extends JpaRepository<AdNgWord, UUID> {

    /** 有効な NG 辞書エントリを全件取得。AdContentModerator がキャッシュ前提で呼ぶ。 */
    List<AdNgWord> findByIsActiveTrue();
}
