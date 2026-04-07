package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.util.LikeEscapeUtils;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.quickmemo.QuickMemoErrorCode;
import com.mannschaft.app.quickmemo.dto.*;
import com.mannschaft.app.quickmemo.entity.*;
import com.mannschaft.app.quickmemo.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ポイっとメモサービス。メモの CRUD・アーカイブ・論理削除・復元を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuickMemoService {

    private static final int UNSORTED_MEMO_LIMIT = 500;
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter TITLE_SUFFIX_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final QuickMemoRepository memoRepository;
    private final QuickMemoTagLinkRepository tagLinkRepository;
    private final QuickMemoAttachmentRepository attachmentRepository;
    private final TagRepository tagRepository;
    private final UserQuickMemoSettingsRepository settingsRepository;
    private final AuditLogService auditLogService;

    public PagedResponse<QuickMemoResponse> listMemos(Long userId, String status, int page, int size) {
        String effectiveStatus = (status != null) ? status : "UNSORTED";
        Page<QuickMemoEntity> pageResult = memoRepository
                .findByUserIdAndStatusAndDeletedAtIsNull(userId, effectiveStatus, PageRequest.of(page - 1, size));
        List<QuickMemoResponse> responses = pageResult.getContent().stream()
                .map(memo -> toResponse(memo, false)).toList();
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                pageResult.getTotalElements(), page, size, pageResult.getTotalPages());
        return PagedResponse.of(responses, meta);
    }

    public QuickMemoResponse getMemoDetail(Long memoId, Long userId) {
        QuickMemoEntity memo = memoRepository.findByIdAndUserId(memoId, userId)
                .orElseThrow(() -> new BusinessException(QuickMemoErrorCode.MEMO_NOT_FOUND));
        return toResponse(memo, true);
    }

    public List<QuickMemoResponse> searchMemos(Long userId, String q) {
        if (q == null || q.isBlank()) return List.of();
        String pattern = LikeEscapeUtils.contains(q.strip());
        return memoRepository.searchByKeyword(userId, pattern).stream()
                .map(memo -> toResponse(memo, false)).toList();
    }

    @Transactional
    public QuickMemoResponse createMemo(Long userId, CreateQuickMemoRequest req, String acceptLanguage) {
        long unsortedCount = memoRepository.countByUserIdAndStatusAndDeletedAtIsNull(userId, "UNSORTED");
        if (unsortedCount >= UNSORTED_MEMO_LIMIT) {
            throw new BusinessException(QuickMemoErrorCode.MEMO_UNSORTED_LIMIT_EXCEEDED);
        }
        String title = resolveTitle(req.title(), acceptLanguage);
        boolean usesDefault = req.reminderUsesDefault() == null || req.reminderUsesDefault();
        List<ReminderOffset> offsets = usesDefault
                ? getDefaultReminders(userId)
                : (req.reminders() != null ? req.reminders() : List.of());
        QuickMemoEntity.QuickMemoEntityBuilder builder = QuickMemoEntity.builder()
                .userId(userId).title(title).body(req.body()).reminderUsesDefault(usesDefault);
        applyReminderOffsets(builder, offsets, LocalDate.now(JST));
        QuickMemoEntity memo = memoRepository.save(builder.build());
        if (req.tagIds() != null && !req.tagIds().isEmpty()) attachTags(memo.getId(), userId, req.tagIds());
        auditLogService.record("QUICK_MEMO_CREATED", userId, null, null, null, null, null, null,
                "{\"memoId\":" + memo.getId() + "}");
        log.info("メモ作成: memoId={}, userId={}", memo.getId(), userId);
        return toResponse(memo, false);
    }

    @Transactional
    public QuickMemoResponse updateMemo(Long memoId, Long userId, UpdateQuickMemoRequest req) {
        QuickMemoEntity memo = memoRepository.findByIdAndUserIdForUpdate(memoId, userId)
                .orElseThrow(() -> new BusinessException(QuickMemoErrorCode.MEMO_NOT_FOUND));
        QuickMemoEntity.QuickMemoEntityBuilder builder = memo.toBuilder();
        if (req.title() != null && !req.title().isBlank()) builder.title(req.title());
        if (req.body() != null) builder.body(req.body());
        if (req.reminderUsesDefault() != null) builder.reminderUsesDefault(req.reminderUsesDefault());
        if (req.reminders() != null) applyReminderOffsets(builder, req.reminders(), memo.getCreatedAt().toLocalDate());
        QuickMemoEntity updated = memoRepository.save(builder.build());
        if (req.tagIds() != null) updateTags(memoId, userId, req.tagIds());
        auditLogService.record("QUICK_MEMO_UPDATED", userId, null, null, null, null, null, null,
                "{\"memoId\":" + memoId + "}");
        return toResponse(updated, true);
    }

    @Transactional
    public void deleteMemo(Long memoId, Long userId) {
        QuickMemoEntity memo = memoRepository.findByIdAndUserIdForUpdate(memoId, userId)
                .orElseThrow(() -> new BusinessException(QuickMemoErrorCode.MEMO_NOT_FOUND));
        memo.softDelete();
        memoRepository.save(memo);
        auditLogService.record("QUICK_MEMO_DELETED", userId, null, null, null, null, null, null,
                "{\"memoId\":" + memoId + "}");
    }

    @Transactional
    public QuickMemoResponse undeleteMemo(Long memoId, Long userId) {
        QuickMemoEntity memo = memoRepository.findById(memoId)
                .filter(m -> m.getUserId().equals(userId) && m.isDeleted())
                .orElseThrow(() -> new BusinessException(QuickMemoErrorCode.MEMO_NOT_FOUND));
        QuickMemoEntity saved = memoRepository.save(memo.toBuilder().deletedAt(null).build());
        auditLogService.record("QUICK_MEMO_RESTORED", userId, null, null, null, null, null, null,
                "{\"memoId\":" + memoId + "}");
        return toResponse(saved, true);
    }

    @Transactional
    public QuickMemoResponse archiveMemo(Long memoId, Long userId) {
        QuickMemoEntity memo = memoRepository.findByIdAndUserIdForUpdate(memoId, userId)
                .orElseThrow(() -> new BusinessException(QuickMemoErrorCode.MEMO_NOT_FOUND));
        memo.archive();
        return toResponse(memoRepository.save(memo), true);
    }

    @Transactional
    public QuickMemoResponse restoreMemo(Long memoId, Long userId) {
        QuickMemoEntity memo = memoRepository.findByIdAndUserIdForUpdate(memoId, userId)
                .orElseThrow(() -> new BusinessException(QuickMemoErrorCode.MEMO_NOT_FOUND));
        memo.restore();
        return toResponse(memoRepository.save(memo), true);
    }

    public PagedResponse<QuickMemoResponse> listTrash(Long userId, int page, int size) {
        Page<QuickMemoEntity> pageResult = memoRepository
                .findByUserIdAndDeletedAtIsNotNull(userId, PageRequest.of(page - 1, size));
        List<QuickMemoResponse> responses = pageResult.getContent().stream()
                .map(memo -> toResponse(memo, false)).toList();
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                pageResult.getTotalElements(), page, size, pageResult.getTotalPages());
        return PagedResponse.of(responses, meta);
    }

    // ─── プライベートヘルパー ───────────────────────────────────────────────────────────

    private QuickMemoResponse toResponse(QuickMemoEntity memo, boolean includeAttachments) {
        List<Long> tagIds = tagLinkRepository.findTagIdsByMemoId(memo.getId());
        List<TagSummary> tags = tagIds.isEmpty() ? List.of()
                : tagRepository.findAllById(tagIds).stream().map(TagSummary::from).toList();
        List<AttachmentSummary> attachments = includeAttachments
                ? attachmentRepository.findByMemoIdOrderBySortOrderAsc(memo.getId())
                        .stream().map(AttachmentSummary::from).toList()
                : List.of();
        return QuickMemoResponse.from(memo, tags, attachments);
    }

    private String resolveTitle(String rawTitle, String acceptLanguage) {
        if (rawTitle != null && !rawTitle.isBlank()) return rawTitle;
        return resolveTitlePrefix(acceptLanguage) + "_" + LocalDateTime.now(JST).format(TITLE_SUFFIX_FMT);
    }

    private String resolveTitlePrefix(String acceptLanguage) {
        if (acceptLanguage == null) return "無題メモ";
        String lang = acceptLanguage.split(",")[0].split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (lang.startsWith("ja")) return "無題メモ";
        if (lang.startsWith("zh")) return "无标题备忘录";
        if (lang.startsWith("ko")) return "제목 없는 메모";
        if (lang.startsWith("es")) return "Memo sin título";
        if (lang.startsWith("de")) return "Memo ohne Titel";
        return "Untitled Memo";
    }

    private List<ReminderOffset> getDefaultReminders(Long userId) {
        return settingsRepository.findByUserId(userId)
                .filter(s -> Boolean.TRUE.equals(s.getReminderEnabled()))
                .map(s -> {
                    List<ReminderOffset> list = new ArrayList<>();
                    if (s.getDefaultOffset1Days() != null && s.getDefaultTime1() != null)
                        list.add(new ReminderOffset(s.getDefaultOffset1Days(), s.getDefaultTime1().toString()));
                    if (s.getDefaultOffset2Days() != null && s.getDefaultTime2() != null)
                        list.add(new ReminderOffset(s.getDefaultOffset2Days(), s.getDefaultTime2().toString()));
                    if (s.getDefaultOffset3Days() != null && s.getDefaultTime3() != null)
                        list.add(new ReminderOffset(s.getDefaultOffset3Days(), s.getDefaultTime3().toString()));
                    return list;
                }).orElse(List.of());
    }

    private void applyReminderOffsets(QuickMemoEntity.QuickMemoEntityBuilder builder,
                                       List<ReminderOffset> offsets, LocalDate baseDate) {
        if (offsets == null || offsets.isEmpty()) return;
        for (int i = 0; i < offsets.size() && i < 3; i++) {
            ReminderOffset offset = offsets.get(i);
            LocalDateTime scheduled = baseDate.plusDays(offset.dayOffset()).atTime(LocalTime.parse(offset.time()));
            switch (i) {
                case 0 -> builder.reminder1ScheduledAt(scheduled);
                case 1 -> builder.reminder2ScheduledAt(scheduled);
                case 2 -> builder.reminder3ScheduledAt(scheduled);
            }
        }
    }

    private void attachTags(Long memoId, Long userId, List<Long> tagIds) {
        if (tagIds.size() > 10) throw new BusinessException(QuickMemoErrorCode.TAG_PER_MEMO_LIMIT_EXCEEDED);
        for (Long tagId : tagIds) {
            tagRepository.findByIdAndScopeTypeAndScopeId(tagId, "PERSONAL", userId)
                    .orElseThrow(() -> new BusinessException(QuickMemoErrorCode.TAG_NOT_FOUND));
            if (!tagLinkRepository.existsByMemoIdAndTagId(memoId, tagId)) {
                tagLinkRepository.save(QuickMemoTagLinkEntity.builder().memoId(memoId).tagId(tagId).build());
                tagRepository.incrementUsageCount(tagId);
            }
        }
    }

    private void updateTags(Long memoId, Long userId, List<Long> newTagIds) {
        List<Long> currentTagIds = tagLinkRepository.findTagIdsByMemoId(memoId);
        List<Long> toRemove = currentTagIds.stream().filter(id -> !newTagIds.contains(id)).toList();
        for (Long tagId : toRemove) {
            tagLinkRepository.deleteByMemoIdAndTagId(memoId, tagId);
            tagRepository.decrementUsageCount(tagId);
        }
        List<Long> toAdd = newTagIds.stream().filter(id -> !currentTagIds.contains(id)).toList();
        if (currentTagIds.size() - toRemove.size() + toAdd.size() > 10)
            throw new BusinessException(QuickMemoErrorCode.TAG_PER_MEMO_LIMIT_EXCEEDED);
        attachTags(memoId, userId, toAdd);
    }
}
