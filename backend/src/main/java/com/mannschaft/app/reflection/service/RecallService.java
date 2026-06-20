package com.mannschaft.app.reflection.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserTimezoneCache;
import com.mannschaft.app.reflection.RecallSelfRating;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.dto.CreateRecallAttemptRequest;
import com.mannschaft.app.reflection.dto.RecallAttemptResponse;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import com.mannschaft.app.reflection.entity.RecallAttemptEntity;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.RecallAttemptRepository;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 想起テスト（recall）のサービス（F06.5・§7 #10〜#11・§3.1）。
 *
 * <p>保存＝開示で {@code revealed_at} を記録し original を返す（AC-7）。{@code FORGOT} なら翌日 SPACED 行を
 * 追加生成しマスク継続＋再提示（AC-22）。本人所有検証は entry/theme 経由（他人は 404）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecallService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Tokyo");

    private final RecallAttemptRepository recallAttemptRepository;
    private final ReflectionEntryRepository reflectionEntryRepository;
    private final ReflectionThemeRepository reflectionThemeRepository;
    private final ReflectionSpacedReminderService reflectionSpacedReminderService;
    private final ReflectionContentSanitizer contentSanitizer;
    private final ReflectionEntryResponseMapper responseMapper;
    private final UserTimezoneCache userTimezoneCache;

    /**
     * 想起テスト保存＝開示（§7 #10・revealed_at 記録＋original 返却＝AC-7・
     * FORGOT で翌日 SPACED 再生成＝AC-22）。
     */
    @Transactional
    public ReflectionEntryResponse recordRecall(Long userId, UUID entryId,
                                                CreateRecallAttemptRequest request) {
        ReflectionEntryEntity entry = requireOwnedEntry(userId, entryId);
        ReflectionThemeEntity theme = reflectionThemeRepository.findByIdAndUserId(entry.getThemeId(), userId)
                .orElseThrow(() -> new BusinessException(ReflectionErrorCode.REFLECTION_NOT_FOUND));

        LocalDate recallDate = todayOf(userId);
        String sanitized = contentSanitizer.sanitizeRecalledContent(request.recalledContent());
        LocalDateTime now = LocalDateTime.now();

        RecallAttemptEntity attempt = RecallAttemptEntity.builder()
                .entryId(entryId)
                .userId(userId)
                .recallDate(recallDate)
                .recalledContent(sanitized)
                .selfRating(request.selfRating())
                .revealedAt(now) // 保存＝開示（AC-7）
                .build();
        recallAttemptRepository.save(attempt);

        // FORGOT なら翌日（recall_date+1）の SPACED 行を追加生成（マスク継続＋再提示・AC-22）。
        if (request.selfRating() == RecallSelfRating.FORGOT) {
            reflectionSpacedReminderService.scheduleNextDaySpacedReminder(entry, recallDate);
        }

        // 開示: マスクを無視して original 本文を返す唯一の遷移点（AC-7）。
        return responseMapper.toRevealedResponse(entry, theme);
    }

    /** 想起履歴一覧（§7 #11）。 */
    @Transactional(readOnly = true)
    public List<RecallAttemptResponse> listRecalls(Long userId, UUID entryId) {
        requireOwnedEntry(userId, entryId);
        return recallAttemptRepository.findByEntryIdOrderByRecallDateDesc(entryId).stream()
                .map(this::toResponse)
                .toList();
    }

    // ─── 内部ヘルパ ───────────────────────────────────────────────

    private ReflectionEntryEntity requireOwnedEntry(Long userId, UUID entryId) {
        return reflectionEntryRepository.findByIdAndUserId(entryId, userId)
                .orElseThrow(() -> new BusinessException(ReflectionErrorCode.REFLECTION_NOT_FOUND));
    }

    private RecallAttemptResponse toResponse(RecallAttemptEntity e) {
        JsonNode recalled = contentSanitizer.parse(e.getRecalledContent());
        return RecallAttemptResponse.builder()
                .id(e.getId().toString())
                .entryId(e.getEntryId().toString())
                .recallDate(e.getRecallDate())
                .recalledContent(recalled)
                .selfRating(e.getSelfRating())
                .revealedAt(e.getRevealedAt())
                .createdAt(e.getCreatedAt())
                .build();
    }

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
