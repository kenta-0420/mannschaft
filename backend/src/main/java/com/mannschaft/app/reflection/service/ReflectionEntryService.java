package com.mannschaft.app.reflection.service;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserTimezoneCache;
import com.mannschaft.app.reflection.ReflectionConstants;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.dto.ExportToBlogRequest;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import com.mannschaft.app.reflection.dto.UpsertReflectionEntryRequest;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 振り返りエントリのサービス（F06.5・§7 #6〜#10, #13）。
 *
 * <p>upsert（(theme,target_date) 一意・楽観排他 409）・target_date 範囲＋PENDING 上限検証・マスク適用
 * （Mapper 経由）・復活更新時のリマインダ再生成を担う。本人所有検証は theme 経由（他人は 404）。
 * ブログ輸出（#13）は Wave2 のためスタブ据え置き。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionEntryService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Tokyo");

    private final ReflectionEntryRepository reflectionEntryRepository;
    private final ReflectionThemeRepository reflectionThemeRepository;
    private final ReflectionSpacedReminderService reflectionSpacedReminderService;
    private final ReflectionContentSanitizer contentSanitizer;
    private final ReflectionEntryResponseMapper responseMapper;
    private final ReflectionMaskEvaluator maskEvaluator;
    private final UserTimezoneCache userTimezoneCache;

    /** テーマ配下エントリ一覧（§7 #6・マスク適用＝§3.2）。 */
    @Transactional(readOnly = true)
    public List<ReflectionEntryResponse> listEntries(Long userId, UUID themeId) {
        ReflectionThemeEntity theme = requireOwnedTheme(userId, themeId);
        LocalDate today = todayOf(userId);
        return reflectionEntryRepository.findByThemeIdOrderByTargetDateDesc(themeId).stream()
                .map(entry -> responseMapper.toResponse(entry, theme, today))
                .toList();
    }

    /**
     * エントリ upsert（§7 #7・(theme,target_date)一意＝AC-4・楽観排他 409＝AC-18・
     * target_date 範囲＋PENDING 上限＝§2.5.1・SPACED 生成＝AC-9）。
     */
    @Transactional
    public ReflectionEntryResponse upsertEntry(Long userId, UpsertReflectionEntryRequest request) {
        ReflectionThemeEntity theme = requireOwnedTheme(userId, request.themeId());
        LocalDate today = todayOf(userId);
        validateTargetDateRange(request.targetDate(), today);

        String sanitized = contentSanitizer.sanitizeAndSerialize(request.structuredContent());

        // 論理削除済みも含めて (theme, target_date) 行を引く（一意制約衝突回避・復活更新・§2.2）。
        Optional<ReflectionEntryEntity> existingOpt = reflectionEntryRepository
                .findIncludingDeletedByThemeIdAndTargetDate(request.themeId(), request.targetDate());

        if (existingOpt.isEmpty()) {
            return createNewEntry(userId, theme, request.targetDate(), sanitized);
        }

        ReflectionEntryEntity existing = existingOpt.get();
        if (existing.getDeletedAt() != null) {
            return restoreEntry(theme, existing, sanitized);
        }
        return updateActiveEntry(theme, existing, request, sanitized, today);
    }

    /** エントリ詳細（§7 #8・マスク適用＝§3.2）。 */
    @Transactional(readOnly = true)
    public ReflectionEntryResponse getEntry(Long userId, UUID entryId) {
        ReflectionEntryEntity entry = requireOwnedEntry(userId, entryId);
        ReflectionThemeEntity theme = requireOwnedTheme(userId, entry.getThemeId());
        return responseMapper.toResponse(entry, theme, todayOf(userId));
    }

    /** エントリ論理削除（§7 #9・関連 PENDING リマインダ CANCEL）。 */
    @Transactional
    public void deleteEntry(Long userId, UUID entryId) {
        ReflectionEntryEntity entry = requireOwnedEntry(userId, entryId);
        entry.softDelete();
        reflectionEntryRepository.save(entry);
        reflectionSpacedReminderService.cancelPendingForEntry(entryId);
    }

    /** ブログ輸出（§7 #13・Wave2 ゆえ未実装据え置き）。 */
    public BlogPostResponse exportToBlog(Long userId, UUID entryId, ExportToBlogRequest request) {
        throw new UnsupportedOperationException("F06.5 Wave2: §7 #13 ブログ輸出");
    }

    // ─── upsert 分岐 ──────────────────────────────────────────────

    private ReflectionEntryResponse createNewEntry(Long userId, ReflectionThemeEntity theme,
                                                   LocalDate targetDate, String sanitized) {
        ensureReminderHeadroom(userId, theme);
        ReflectionEntryEntity entity = ReflectionEntryEntity.builder()
                .themeId(theme.getId())
                .userId(userId)
                .targetDate(targetDate)
                .structuredContent(sanitized)
                // visibility は MVP PRIVATE 固定（@Builder.Default）。
                .build();
        ReflectionEntryEntity saved = reflectionEntryRepository.save(entity);
        reflectionSpacedReminderService.generateSpacedReminders(saved, theme);
        // 保存直後は当日（非マスク）想定だが、過去日入力もあり得るためマスク適用で返す。
        return responseMapper.toResponse(saved, theme, todayOf(userId));
    }

    private ReflectionEntryResponse restoreEntry(ReflectionThemeEntity theme,
                                                 ReflectionEntryEntity deleted, String sanitized) {
        // 復活: 旧 PENDING リマインダを CANCEL → 本文上書き＋deleted_at=NULL → 新 SPACED 再生成（§2.2）。
        ensureReminderHeadroom(deleted.getUserId(), theme);
        reflectionSpacedReminderService.cancelPendingForEntry(deleted.getId());
        deleted.restoreWith(sanitized);
        ReflectionEntryEntity saved = reflectionEntryRepository.save(deleted);
        reflectionSpacedReminderService.generateSpacedReminders(saved, theme);
        return responseMapper.toResponse(saved, theme, todayOf(saved.getUserId()));
    }

    private ReflectionEntryResponse updateActiveEntry(ReflectionThemeEntity theme,
                                                      ReflectionEntryEntity existing,
                                                      UpsertReflectionEntryRequest request,
                                                      String sanitized, LocalDate today) {
        // 楽観排他: 既存更新は expectedVersion 必須・不一致は 409（AC-18）。
        if (request.expectedVersion() == null
                || !Objects.equals(request.expectedVersion(), existing.getVersion())) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_VERSION_CONFLICT);
        }
        // マスク中エントリの直接 PUT は 409（recall 開示後に編集すること・§3.1 末尾）。
        // 当日（target_date==today）は非マスクゆえ自由編集可。
        if (maskEvaluator.isMasked(existing, theme, today)) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_ENTRY_MASKED);
        }
        existing.applyUpdate(sanitized);
        ReflectionEntryEntity saved = reflectionEntryRepository.save(existing);
        // target_date は不変ゆえ既存 SPACED は有効。再生成しない。
        return responseMapper.toResponse(saved, theme, today);
    }

    // ─── 検証ヘルパ ───────────────────────────────────────────────

    /** target_date 許容範囲（過去365〜未来30日・§2.5.1 c）。範囲外は 400。 */
    private void validateTargetDateRange(LocalDate targetDate, LocalDate today) {
        LocalDate min = today.minusDays(ReflectionConstants.TARGET_DATE_PAST_DAYS);
        LocalDate max = today.plusDays(ReflectionConstants.TARGET_DATE_FUTURE_DAYS);
        if (targetDate.isBefore(min) || targetDate.isAfter(max)) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_TARGET_DATE_OUT_OF_RANGE);
        }
    }

    /**
     * この保存で生成する SPACED 行を加えて PENDING 総数が上限（1,000）を超えるなら 400（§2.5.1 a）。
     */
    private void ensureReminderHeadroom(Long userId, ReflectionThemeEntity theme) {
        long pending = reflectionSpacedReminderService.countPendingReminders(userId);
        long toAdd = maskEvaluator.parseIntervals(theme.getRecallIntervalDays()).size();
        if (pending + toAdd > ReflectionConstants.MAX_PENDING_REMINDERS) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_REMINDER_LIMIT_EXCEEDED);
        }
    }

    private ReflectionThemeEntity requireOwnedTheme(Long userId, UUID themeId) {
        return reflectionThemeRepository.findByIdAndUserId(themeId, userId)
                .orElseThrow(() -> new BusinessException(ReflectionErrorCode.REFLECTION_NOT_FOUND));
    }

    private ReflectionEntryEntity requireOwnedEntry(Long userId, UUID entryId) {
        return reflectionEntryRepository.findByIdAndUserId(entryId, userId)
                .orElseThrow(() -> new BusinessException(ReflectionErrorCode.REFLECTION_NOT_FOUND));
    }

    /** ユーザー TZ の今日（§3.1 / §2.5.1 c の基準）。 */
    private LocalDate todayOf(Long userId) {
        ZoneId zone;
        try {
            zone = ZoneId.of(userTimezoneCache.getTimezone(userId));
        } catch (Exception e) {
            zone = DEFAULT_ZONE;
        }
        return LocalDate.now(zone);
    }
}
