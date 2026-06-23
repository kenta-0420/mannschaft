package com.mannschaft.app.reflection.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reflection.ReflectionConstants;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.ReflectionReminderStatus;
import com.mannschaft.app.reflection.ReflectionSourceType;
import com.mannschaft.app.reflection.dto.CreateReflectionThemeRequest;
import com.mannschaft.app.reflection.dto.ReflectionThemeResponse;
import com.mannschaft.app.reflection.dto.UpdateReflectionThemeRequest;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionSpacedReminderRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 振り返りテーマのサービス（F06.5・§7 #1〜#5）。
 *
 * <p>本人所有検証（他人は IDOR 対策で 404）・テーマ数上限 100（§2.5.1 b）・exam_date 設定時の
 * PRE_EXAM 再生成（§5.5）・削除時の配下 entry CASCADE 論理削除＋PENDING リマインダ CANCEL を担う。
 * 更新は {@code applyUpdate} の直接ミューテート（toBuilder 回避）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionThemeService {

    private final ReflectionThemeRepository reflectionThemeRepository;
    private final ReflectionEntryRepository reflectionEntryRepository;
    private final ReflectionSpacedReminderRepository reflectionSpacedReminderRepository;
    private final ReflectionSpacedReminderService reflectionSpacedReminderService;

    /**
     * 自分のテーマ一覧（§7 #1）。
     * Phase 3: アクティブテーマのみ（archived_at IS NULL）を返す新メソッドに切替（AC-39）。
     * archived テーマは /archive/search EP で参照する。
     */
    @Transactional(readOnly = true)
    public List<ReflectionThemeResponse> listMyThemes(Long userId) {
        return reflectionThemeRepository.findByUserIdAndArchivedAtIsNullOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    /** テーマ作成（§7 #2・テーマ数上限 100 検証＝§2.5.1(b)）。 */
    @Transactional
    public ReflectionThemeResponse createTheme(Long userId, CreateReflectionThemeRequest request) {
        long count = reflectionThemeRepository.countByUserId(userId);
        if (count >= ReflectionConstants.MAX_THEMES_PER_USER) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_THEME_LIMIT_EXCEEDED);
        }
        ReflectionThemeEntity entity = ReflectionThemeEntity.builder()
                .userId(userId)
                .title(request.title())
                .description(request.description())
                .sourceType(request.sourceType() != null ? request.sourceType() : ReflectionSourceType.FREE)
                .linkedSlotKind(request.linkedSlotKind())
                .linkedSlotId(request.linkedSlotId())
                .examDate(request.examDate())
                // Phase 2: 科目名紐づけ（§11.1）
                .linkedSubjectName(request.linkedSubjectName())
                .linkedCourseCode(request.linkedCourseCode())
                // Phase 3: 学年・学期・親テーマ（§12）
                .academicYear(request.academicYear())
                .termLabel(request.termLabel())
                // visibility / recallIntervalDays は MVP 固定（@Builder.Default の PRIVATE / '1,3,7,14'）。
                .build();
        // Phase 3: 親テーマ指定のバリデーション＆セット
        if (request.parentThemeId() != null) {
            UUID parentId = UUID.fromString(request.parentThemeId());
            validateAndSetParent(entity, userId, parentId);
        }
        ReflectionThemeEntity saved = reflectionThemeRepository.save(entity);
        // exam_date が指定されていれば PRE_EXAM リマインダを生成（過去日ガード内包・§5.5）。
        if (saved.getExamDate() != null) {
            reflectionSpacedReminderService.generatePreExamReminders(saved);
        }
        return toResponse(saved);
    }

    /** テーマ詳細（§7 #3・本人所有検証＝AC-2）。 */
    @Transactional(readOnly = true)
    public ReflectionThemeResponse getTheme(Long userId, UUID themeId) {
        return toResponse(requireOwnedTheme(userId, themeId));
    }

    /** テーマ更新（§7 #4・exam_date 設定で PRE_EXAM 再生成＝AC-12／§5.5）。 */
    @Transactional
    public ReflectionThemeResponse updateTheme(Long userId, UUID themeId,
                                               UpdateReflectionThemeRequest request) {
        ReflectionThemeEntity theme = requireOwnedTheme(userId, themeId);
        LocalDate prevExamDate = theme.getExamDate();

        theme.applyUpdate(request.title(), request.description(), request.sourceType(), null);
        // exam_date は「未指定＝現値維持」「examDateCleared=true＝NULL クリア」「値あり＝設定」。
        LocalDate newExamDate = prevExamDate;
        if (request.examDateCleared()) {
            theme.setExamDate(null);
            newExamDate = null;
        } else if (request.examDate() != null) {
            theme.setExamDate(request.examDate());
            newExamDate = request.examDate();
        }
        // Phase 2: linked_subject_name/linked_course_code（examDate と完全同型・§11.4）
        if (request.clearLinkedSubject()) {
            theme.clearLinkedSubject();
        } else {
            if (request.linkedSubjectName() != null) {
                theme.setLinkedSubject(request.linkedSubjectName(), request.linkedCourseCode());
            } else if (request.linkedCourseCode() != null) {
                // subjectName 維持・courseCode のみ更新（保守的：subjectName変更なしケース）
                theme.setLinkedSubject(theme.getLinkedSubjectName(), request.linkedCourseCode());
            }
        }
        // Phase 3: 学年・学期（null=現値維持・§12.1）
        theme.setAcademicYearAndTerm(request.academicYear(), request.termLabel());
        // Phase 3: 親テーマ（clearParent=true でクリア・examDateCleared と同型・§12.3）
        if (request.clearParent()) {
            theme.clearParentThemeId();
        } else if (request.parentThemeId() != null) {
            UUID parentId = UUID.fromString(request.parentThemeId());
            validateAndSetParent(theme, userId, parentId);
        }
        ReflectionThemeEntity saved = reflectionThemeRepository.save(theme);

        // exam_date が変化したら既存 PENDING PRE_EXAM を CANCELLED にして作り直す（§5.5）。
        if (!Objects.equals(prevExamDate, newExamDate)) {
            reflectionSpacedReminderService.cancelPendingPreExamForTheme(themeId);
            if (newExamDate != null) {
                reflectionSpacedReminderService.generatePreExamReminders(saved);
            }
        }
        return toResponse(saved);
    }

    /** テーマ論理削除（§7 #5・配下 entry も CASCADE 論理削除＋PENDING リマインダ CANCEL）。 */
    @Transactional
    public void deleteTheme(Long userId, UUID themeId) {
        ReflectionThemeEntity theme = requireOwnedTheme(userId, themeId);
        // 配下エントリを論理削除＋当該エントリ由来 PENDING リマインダを CANCEL。
        List<ReflectionEntryEntity> entries =
                reflectionEntryRepository.findByThemeIdOrderByTargetDateDesc(themeId);
        for (ReflectionEntryEntity entry : entries) {
            entry.softDelete();
            reflectionSpacedReminderService.cancelPendingForEntry(entry.getId());
        }
        reflectionEntryRepository.saveAll(entries);
        // テーマ由来 PRE_EXAM PENDING も CANCEL。
        reflectionSpacedReminderService.cancelPendingPreExamForTheme(themeId);
        theme.softDelete();
        reflectionThemeRepository.save(theme);
    }

    // ─── 内部ヘルパ ───────────────────────────────────────────────

    /** 本人所有のテーマを取得（他人所有・不在は IDOR 対策で 404）。 */
    private ReflectionThemeEntity requireOwnedTheme(Long userId, UUID themeId) {
        return reflectionThemeRepository.findByIdAndUserId(themeId, userId)
                .orElseThrow(() -> new BusinessException(ReflectionErrorCode.REFLECTION_NOT_FOUND));
    }

    /**
     * ReflectionArchiveService から利用するための公開版 toResponse。
     * 内容は {@link #toResponse} と同一。
     */
    public ReflectionThemeResponse toResponsePublic(ReflectionThemeEntity e) {
        return toResponse(e);
    }

    private ReflectionThemeResponse toResponse(ReflectionThemeEntity e) {
        return ReflectionThemeResponse.builder()
                .id(e.getId().toString())
                .userId(e.getUserId())
                .title(e.getTitle())
                .description(e.getDescription())
                .sourceType(e.getSourceType())
                .linkedSlotKind(e.getLinkedSlotKind())
                .linkedSlotId(e.getLinkedSlotId())
                .linkedSubjectName(e.getLinkedSubjectName())
                .linkedCourseCode(e.getLinkedCourseCode())
                .examDate(e.getExamDate())
                .visibility(e.getVisibility())
                .recallIntervalDays(e.getRecallIntervalDays())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                // Phase 3: アーカイブ＆分類フィールド（§12.5）
                .academicYear(e.getAcademicYear())
                .termLabel(e.getTermLabel())
                .parentThemeId(e.getParentThemeId() != null ? e.getParentThemeId().toString() : null)
                .archivedAt(e.getArchivedAt())
                .build();
    }

    /**
     * Phase 3: 親テーマのバリデーション（§12.3）:
     * - 自己参照禁止（400）
     * - depth 超過禁止（親の親は不可・400）
     * - 他人テーマ禁止（404）
     * - アーカイブ済み/削除済み禁止（400）
     */
    private void validateAndSetParent(ReflectionThemeEntity theme, Long userId, UUID parentId) {
        // 自己参照チェック
        if (theme.getId() != null && theme.getId().equals(parentId)) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_PARENT_SELF_REFERENCE);
        }
        // 親テーマの取得（他人テーマは 404）
        ReflectionThemeEntity parent = reflectionThemeRepository.findByIdAndUserId(parentId, userId)
                .orElseThrow(() -> new BusinessException(ReflectionErrorCode.REFLECTION_NOT_FOUND));
        // アーカイブ済み/削除済みチェック（@SQLRestriction で削除済みは取得されないが明示チェック）
        if (parent.getArchivedAt() != null) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_PARENT_INVALID_STATE);
        }
        // depth 超過チェック（親の親は不可）
        if (parent.getParentThemeId() != null) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_PARENT_DEPTH_EXCEEDED);
        }
        theme.setParentThemeId(parentId);
    }

    /** PENDING リマインダー総数（呼び出し補助・未使用箇所向け）。 */
    long countPendingReminders(Long userId) {
        return reflectionSpacedReminderRepository
                .countByUserIdAndStatus(userId, ReflectionReminderStatus.PENDING);
    }
}
