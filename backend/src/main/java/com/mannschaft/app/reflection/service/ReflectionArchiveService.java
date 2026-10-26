package com.mannschaft.app.reflection.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.dto.ArchiveFolderResponse;
import com.mannschaft.app.reflection.dto.BulkArchiveRequest;
import com.mannschaft.app.reflection.dto.BulkArchiveResult;
import com.mannschaft.app.reflection.dto.ReflectionThemeResponse;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * F06.5 Phase 3: アーカイブ＆分類サービス（§12・AC-39〜AC-43）。
 *
 * <p>archive/restore/bulk-archive/folders/search を担当。
 * リマインダー停止は既存 {@link ReflectionSpacedReminderService} の
 * {@code cancelPendingForEntry} / {@code cancelPendingPreExamForTheme} を踏襲する（§12.2）。
 * parent バリデーションは {@link ReflectionThemeService#validateAndSetParent} と同型。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionArchiveService {

    /** EP #18 の size 上限（§12.9 DoS 対策）。 */
    public static final int MAX_SEARCH_SIZE = 50;

    private final ReflectionThemeRepository reflectionThemeRepository;
    private final ReflectionEntryRepository reflectionEntryRepository;
    private final ReflectionSpacedReminderService reflectionSpacedReminderService;
    private final ReflectionThemeService reflectionThemeService;
    private final ReflectionAccessGuard reflectionAccessGuard;

    // ─── EP #19: archive ────────────────────────────────────────────

    /**
     * テーマをアーカイブする（EP #19・AC-39/AC-41）。
     *
     * <ul>
     *   <li>既にアーカイブ済み → 409（ALREADY_ARCHIVED）</li>
     *   <li>archive 操作で PENDING SPACED + PRE_EXAM リマインダーを CANCEL（§12.2）</li>
     * </ul>
     */
    @Transactional
    public ReflectionThemeResponse archiveTheme(Long userId, UUID themeId) {
        ReflectionThemeEntity theme = requireOwnedTheme(userId, themeId);
        if (theme.getArchivedAt() != null) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_ALREADY_ARCHIVED);
        }
        // archive_at セット
        theme.archive();
        // SPACED リマインダー停止: 配下エントリの PENDING をすべて CANCEL
        List<ReflectionEntryEntity> entries =
                reflectionEntryRepository.findByThemeIdOrderByTargetDateDesc(themeId);
        for (ReflectionEntryEntity entry : entries) {
            reflectionSpacedReminderService.cancelPendingForEntry(entry.getId());
        }
        // PRE_EXAM リマインダー停止（§12.2）
        reflectionSpacedReminderService.cancelPendingPreExamForTheme(themeId);
        ReflectionThemeEntity saved = reflectionThemeRepository.save(theme);
        return reflectionThemeService.toResponsePublic(saved);
    }

    // ─── EP #20: restore ────────────────────────────────────────────

    /**
     * アーカイブ済みテーマを復元する（EP #20・AC-40/AC-41）。
     *
     * <ul>
     *   <li>アクティブなテーマ（archived_at IS NULL）への restore → 409（NOT_ARCHIVED）</li>
     *   <li>復元時はリマインダーを自動再生成しない（§12.2・AC-41）</li>
     * </ul>
     */
    @Transactional
    public ReflectionThemeResponse restoreTheme(Long userId, UUID themeId) {
        ReflectionThemeEntity theme = requireOwnedThemeAllowArchived(userId, themeId);
        if (theme.getArchivedAt() == null) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_NOT_ARCHIVED);
        }
        theme.restore();
        ReflectionThemeEntity saved = reflectionThemeRepository.save(theme);
        return reflectionThemeService.toResponsePublic(saved);
    }

    // ─── EP #17: folders ────────────────────────────────────────────

    /**
     * アーカイブ済みテーマのフォルダ集計（EP #17・AC-42）。
     *
     * <p>学年×学期×教科 GROUP BY の結果を返す。NULL グループも含む。</p>
     */
    @Transactional(readOnly = true)
    public List<ArchiveFolderResponse> getFolders(Long userId) {
        return reflectionThemeRepository.findArchivedFolders(userId);
    }

    // ─── EP #18: search ────────────────────────────────────────────

    /**
     * アーカイブ済みテーマ横断検索（EP #18・AC-43）。
     *
     * <p>keyword は LIKE エスケープ（% / _ / \ をアプリ層で処理）。</p>
     *
     * @param userId       認証ユーザーID
     * @param archived     true=archived のみ / false=active のみ / null=archived（既定）
     * @param academicYear 学年度フィルタ（null=絞りなし）
     * @param termLabel    学期フィルタ（null=絞りなし）
     * @param subjectName  教科フィルタ（null=絞りなし）
     * @param keyword      タイトル/説明 LIKE キーワード（null=絞りなし）
     * @param page         ページ番号（0始まり）
     * @param size         1ページサイズ（上限 MAX_SEARCH_SIZE=50）
     */
    @Transactional(readOnly = true)
    public Page<ReflectionThemeResponse> search(Long userId, Boolean archived,
                                                Integer academicYear, String termLabel,
                                                String subjectName, String keyword,
                                                int page, int size) {
        if (size > MAX_SEARCH_SIZE) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
        }
        // archived パラメータ未指定（null）の場合は true（アーカイブ済みのみ）として動作（§12.4）
        Boolean archivedFilter = (archived != null) ? archived : Boolean.TRUE;
        // keyword の LIKE エスケープ（% / _ / \ を \ でエスケープ・§12.4）
        String escapedKeyword = keyword != null ? escapeLikeKeyword(keyword) : null;
        Pageable pageable = PageRequest.of(page, size);
        return reflectionThemeRepository
                .searchArchived(userId, archivedFilter, academicYear, termLabel, subjectName,
                        escapedKeyword, pageable)
                .map(reflectionThemeService::toResponsePublic);
    }

    // ─── EP #21: bulk-archive ───────────────────────────────────────

    /**
     * 条件に合致するアクティブテーマを一括アーカイブする（EP #21）。
     *
     * <p>3フィールドすべて null のリクエストは 400 で拒否（全件一括アーカイブ防止・§12.4）。</p>
     */
    @Transactional
    public BulkArchiveResult bulkArchive(Long userId, BulkArchiveRequest request) {
        // 安全弁: 条件が1件も指定されていない場合は 400
        if (request.academicYear() == null && request.termLabel() == null
                && request.subjectName() == null) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_BULK_ARCHIVE_NO_CONDITION);
        }
        List<ReflectionThemeEntity> targets = reflectionThemeRepository.findActiveByCondition(
                userId, request.academicYear(), request.termLabel(), request.subjectName());
        int count = 0;
        for (ReflectionThemeEntity theme : targets) {
            // SPACED リマインダー停止
            List<ReflectionEntryEntity> entries =
                    reflectionEntryRepository.findByThemeIdOrderByTargetDateDesc(theme.getId());
            for (ReflectionEntryEntity entry : entries) {
                reflectionSpacedReminderService.cancelPendingForEntry(entry.getId());
            }
            // PRE_EXAM リマインダー停止
            reflectionSpacedReminderService.cancelPendingPreExamForTheme(theme.getId());
            theme.archive();
            count++;
        }
        if (count > 0) {
            reflectionThemeRepository.saveAll(targets);
        }
        return BulkArchiveResult.builder().archivedCount(count).build();
    }

    // ─── 内部ヘルパ ─────────────────────────────────────────────────

    /**
     * 本人所有のテーマを取得（認可は {@link ReflectionAccessGuard} に一元化・他者所有／不在は 404 秘匿）。
     * archived_at の有無は問わない（アーカイブ状態の業務判定は呼び出し側）。
     */
    private ReflectionThemeEntity requireOwnedTheme(Long userId, UUID themeId) {
        return reflectionAccessGuard.requireOwnedTheme(userId, themeId);
    }

    /**
     * 本人所有テーマを取得（archived 含む・{@code @SQLRestriction} で deleted_at IS NULL は保証）。
     *
     * <p>restore 操作は archived テーマを操作対象とするため、アーカイブ状態で絞らない
     * {@link #requireOwnedTheme} と同一の認可ゲートを用いる（deleted_at とアーカイブは独立）。</p>
     */
    private ReflectionThemeEntity requireOwnedThemeAllowArchived(Long userId, UUID themeId) {
        return reflectionAccessGuard.requireOwnedTheme(userId, themeId);
    }

    /**
     * LIKE キーワードを MySQL 用にエスケープする（§12.4 AC-43）。
     * ESCAPE '\\' 句とセットで使用する（@Query 側で指定済み）。
     *
     * @param keyword 元のキーワード文字列
     * @return \% \_  \\ でエスケープされたキーワード
     */
    static String escapeLikeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        return keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

}
