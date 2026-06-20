package com.mannschaft.app.reflection.service;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.reflection.dto.ExportToBlogRequest;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import com.mannschaft.app.reflection.dto.UpsertReflectionEntryRequest;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 振り返りエントリのサービス（F06.5・§7 #6〜#10, #13）。
 *
 * <p><b>第二陣スケルトン</b>: シグネチャ・依存注入のみ確定。本体ロジック（upsert＋楽観排他 409・
 * target_date 範囲＋PENDING 上限検証・マスク判定＋Mapper・復活更新時のリマインダ再生成・
 * recall 開示・ブログ輸出 PRIVATE 明示＋再輸出 409）は次陣（試練 red→出陣 green）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionEntryService {

    private final ReflectionEntryRepository reflectionEntryRepository;
    private final ReflectionThemeRepository reflectionThemeRepository;
    private final ReflectionSpacedReminderService reflectionSpacedReminderService;

    /** テーマ配下エントリ一覧（§7 #6・マスク適用＝§3.2）。 */
    public List<ReflectionEntryResponse> listEntries(Long userId, UUID themeId) {
        throw new UnsupportedOperationException("F06.5 未実装: §7 #6 エントリ一覧");
    }

    /**
     * エントリ upsert（§7 #7・(theme,target_date)一意＝AC-4・楽観排他 409＝AC-18・
     * target_date 範囲＋PENDING 上限＝§2.5.1・SPACED 生成＝AC-9）。
     */
    public ReflectionEntryResponse upsertEntry(Long userId, UpsertReflectionEntryRequest request) {
        throw new UnsupportedOperationException("F06.5 未実装: §7 #7 エントリ upsert");
    }

    /** エントリ詳細（§7 #8・マスク適用＝§3.2）。 */
    public ReflectionEntryResponse getEntry(Long userId, UUID entryId) {
        throw new UnsupportedOperationException("F06.5 未実装: §7 #8 エントリ詳細");
    }

    /** エントリ論理削除（§7 #9・関連 PENDING リマインダ CANCEL）。 */
    public void deleteEntry(Long userId, UUID entryId) {
        throw new UnsupportedOperationException("F06.5 未実装: §7 #9 エントリ論理削除");
    }

    /** ブログ輸出（§7 #13・PRIVATE 明示＝§6.3・元エントリ残存＝AC-20・再輸出 409）。 */
    public BlogPostResponse exportToBlog(Long userId, UUID entryId, ExportToBlogRequest request) {
        throw new UnsupportedOperationException("F06.5 未実装: §7 #13 ブログ輸出");
    }
}
