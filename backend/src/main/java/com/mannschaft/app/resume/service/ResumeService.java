package com.mannschaft.app.resume.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.resume.ResumeErrorCode;
import com.mannschaft.app.resume.dto.ResumeDetailResponse;
import com.mannschaft.app.resume.dto.ResumeFullSaveRequest;
import com.mannschaft.app.resume.dto.ResumeHeaderPatchRequest;
import com.mannschaft.app.resume.dto.ResumeSummaryResponse;
import com.mannschaft.app.resume.entity.ResumeCareerEntity;
import com.mannschaft.app.resume.entity.ResumeEducationEntity;
import com.mannschaft.app.resume.entity.ResumeEntity;
import com.mannschaft.app.resume.entity.ResumeQualificationEntity;
import com.mannschaft.app.resume.entity.ResumeSkillEntity;
import com.mannschaft.app.resume.repository.ResumeCareerRepository;
import com.mannschaft.app.resume.repository.ResumeEducationRepository;
import com.mannschaft.app.resume.repository.ResumeQualificationRepository;
import com.mannschaft.app.resume.repository.ResumeRepository;
import com.mannschaft.app.resume.repository.ResumeSkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 履歴書バージョン CRUD サービス（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §5
 *
 * <p>CRUD・一括保存・複製・タイトル自動採番・楽観ロック競合ハンドリングを担当する。
 * Phase 3 担当の証明写真（{@code ResumePhotoService}）と
 * 帳票生成（{@code ResumeExportService}）は別クラスに分離している。
 *
 * <p>ドメイン境界: すべての {@code @Transactional} は resume ドメイン内に閉じる。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResumeService {

    /** 1 ユーザーあたりの履歴書バージョン上限。 */
    private static final int MAX_RESUMES_PER_USER = 20;
    /** 学歴の上限件数。 */
    private static final int MAX_EDUCATIONS = 30;
    /** 職歴の上限件数。 */
    private static final int MAX_CAREERS = 30;
    /** 免許・資格の上限件数。 */
    private static final int MAX_QUALIFICATIONS = 50;
    /** 構造化スキルの上限件数。 */
    private static final int MAX_SKILLS = 50;

    private final ResumeRepository resumeRepository;
    private final ResumeEducationRepository educationRepository;
    private final ResumeCareerRepository careerRepository;
    private final ResumeQualificationRepository qualificationRepository;
    private final ResumeSkillRepository skillRepository;

    // =========================================================================
    // 一覧取得
    // =========================================================================

    /**
     * 認証ユーザーの履歴書バージョン一覧を取得する（サマリー形式）。
     *
     * @param userId 認証ユーザーID
     * @return 履歴書サマリーリスト（作成日時降順）
     */
    public List<ResumeSummaryResponse> listResumes(Long userId) {
        return resumeRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    // =========================================================================
    // フル取得
    // =========================================================================

    /**
     * 指定した履歴書バージョンをフル取得する。
     *
     * <p>所有者確認を行う。所有者不一致・存在しない・論理削除済みの場合は
     * RESUME_001（404）を返す（IDOR 対策）。
     *
     * @param id     履歴書 ID
     * @param userId 認証ユーザーID（所有者確認に使用）
     * @return 履歴書詳細レスポンス
     */
    public ResumeDetailResponse getResume(UUID id, Long userId) {
        ResumeEntity resume = findResumeOwnedBy(id, userId);
        return toDetailResponse(resume);
    }

    // =========================================================================
    // 新規作成
    // =========================================================================

    /**
     * 新規履歴書バージョンを作成する。
     *
     * <p>タイトルが空文字または null の場合は「下書き YYYY-MM-DD」を自動採番する。
     * 同日の「下書き」が既にある場合は「下書き YYYY-MM-DD (2)」「 (3)」と連番。
     *
     * @param userId 認証ユーザーID
     * @param title  バージョン名（null / 空文字 = 自動採番）
     * @return 作成した履歴書の詳細レスポンス
     */
    @Transactional
    public ResumeDetailResponse createResume(Long userId, String title) {
        // 上限チェック
        long count = resumeRepository.findByUserIdOrderByCreatedAtDesc(userId).size();
        if (count >= MAX_RESUMES_PER_USER) {
            throw new BusinessException(ResumeErrorCode.RESUME_003);
        }

        String resolvedTitle = resolveTitle(userId, title);

        ResumeEntity resume = ResumeEntity.builder()
                .userId(userId)
                .title(resolvedTitle)
                .build();

        resume = resumeRepository.save(resume);
        return toDetailResponse(resume);
    }

    // =========================================================================
    // フル一括保存（宣言的置換・冪等）
    // =========================================================================

    /**
     * 履歴書のヘッダー情報と子要素（学歴・職歴・資格・スキル）をまとめて保存する。
     *
     * <p>子要素の差分 upsert ロジック:
     * <ul>
     *   <li>リクエストに id がある → 既存レコードを更新</li>
     *   <li>リクエストに id がない → 新規作成</li>
     *   <li>既存の子でリクエストにないもの → 論理削除</li>
     * </ul>
     *
     * <p>楽観ロック: {@code ObjectOptimisticLockingFailureException} を catch して
     * RESUME_010 に変換する。
     *
     * @param id     履歴書 ID
     * @param userId 認証ユーザーID
     * @param req    一括保存リクエスト
     * @return 保存後の履歴書詳細レスポンス
     */
    @Transactional
    public ResumeDetailResponse saveResume(UUID id, Long userId, ResumeFullSaveRequest req) {
        // 件数上限チェック
        checkChildLimits(req);

        ResumeEntity resume = findResumeOwnedBy(id, userId);

        // 楽観ロックバージョンを一致させる（@Version フィールドは直接操作できないため
        // バージョン不一致は JPA の flush 時に ObjectOptimisticLockingFailureException が発生する）
        if (req.version() != null && !req.version().equals(resume.getVersion())) {
            throw new BusinessException(ResumeErrorCode.RESUME_010);
        }

        // ヘッダー更新
        ResumeEntity.EraFormat eraFormat = parseEraFormat(req.eraFormat(), resume.getEraFormat());
        resume.updateHeader(
                req.title(), resume.getPhotoKey(), eraFormat,
                req.currentAddress(), req.currentAddressKana(),
                req.contactAddress(), req.contactAddressKana(),
                req.contactPhone(), req.contactEmail(),
                req.motivation(), req.selfPr(), req.personalRequest(),
                toShort(req.commuteMinutes()), toShort(req.dependentsCount()),
                req.hasSpouse(), req.spouseSupport(),
                req.careerSummary(), req.skillsSummary()
        );

        try {
            resumeRepository.save(resume);

            // 子要素の差分 upsert
            if (req.educations() != null) {
                upsertEducations(resume.getId(), req.educations());
            }
            if (req.careers() != null) {
                upsertCareers(resume.getId(), req.careers());
            }
            if (req.qualifications() != null) {
                upsertQualifications(resume.getId(), req.qualifications());
            }
            if (req.skills() != null) {
                upsertSkills(resume.getId(), req.skills());
            }

        } catch (OptimisticLockingFailureException e) {
            // ObjectOptimisticLockingFailureException は OptimisticLockingFailureException の
            // サブクラスであるため、親クラスのみキャッチすれば両方を補足できる。
            log.warn("履歴書の楽観ロック競合が発生しました: resumeId={}, userId={}", id, userId);
            throw new BusinessException(ResumeErrorCode.RESUME_010);
        }

        // 最新状態を再取得して返す
        return getResume(id, userId);
    }

    // =========================================================================
    // ヘッダー部分更新（PATCH）
    // =========================================================================

    /**
     * 履歴書のヘッダー情報を部分更新する。
     *
     * <p>送信された非 null フィールドのみを更新する（null = 変更なし）。
     * 子要素（学歴・職歴等）は対象外。
     *
     * @param id     履歴書 ID
     * @param userId 認証ユーザーID
     * @param req    ヘッダー部分更新リクエスト
     * @return 更新後の履歴書詳細レスポンス
     */
    @Transactional
    public ResumeDetailResponse patchResume(UUID id, Long userId, ResumeHeaderPatchRequest req) {
        ResumeEntity resume = findResumeOwnedBy(id, userId);

        // null のフィールドは元の値を維持する
        String title = req.title() != null ? req.title() : resume.getTitle();
        ResumeEntity.EraFormat eraFormat = req.eraFormat() != null
                ? parseEraFormat(req.eraFormat(), resume.getEraFormat())
                : resume.getEraFormat();
        String currentAddress = req.currentAddress() != null
                ? req.currentAddress() : resume.getCurrentAddress();
        String currentAddressKana = req.currentAddressKana() != null
                ? req.currentAddressKana() : resume.getCurrentAddressKana();
        String contactAddress = req.contactAddress() != null
                ? req.contactAddress() : resume.getContactAddress();
        String contactAddressKana = req.contactAddressKana() != null
                ? req.contactAddressKana() : resume.getContactAddressKana();
        String contactPhone = req.contactPhone() != null
                ? req.contactPhone() : resume.getContactPhone();
        String contactEmail = req.contactEmail() != null
                ? req.contactEmail() : resume.getContactEmail();
        String motivation = req.motivation() != null ? req.motivation() : resume.getMotivation();
        String selfPr = req.selfPr() != null ? req.selfPr() : resume.getSelfPr();
        String personalRequest = req.personalRequest() != null
                ? req.personalRequest() : resume.getPersonalRequest();
        Short commuteMinutes = req.commuteMinutes() != null
                ? toShort(req.commuteMinutes()) : resume.getCommuteMinutes();
        Short dependentsCount = req.dependentsCount() != null
                ? toShort(req.dependentsCount()) : resume.getDependentsCount();
        Boolean hasSpouse = req.hasSpouse() != null ? req.hasSpouse() : resume.getHasSpouse();
        Boolean spouseSupport = req.spouseSupport() != null
                ? req.spouseSupport() : resume.getSpouseSupport();
        String careerSummary = req.careerSummary() != null
                ? req.careerSummary() : resume.getCareerSummary();
        String skillsSummary = req.skillsSummary() != null
                ? req.skillsSummary() : resume.getSkillsSummary();

        resume.updateHeader(
                title, resume.getPhotoKey(), eraFormat,
                currentAddress, currentAddressKana,
                contactAddress, contactAddressKana,
                contactPhone, contactEmail,
                motivation, selfPr, personalRequest,
                commuteMinutes, dependentsCount,
                hasSpouse, spouseSupport,
                careerSummary, skillsSummary
        );

        resumeRepository.save(resume);
        return getResume(id, userId);
    }

    // =========================================================================
    // 論理削除
    // =========================================================================

    /**
     * 履歴書バージョンを論理削除する。
     *
     * @param id     履歴書 ID
     * @param userId 認証ユーザーID
     */
    @Transactional
    public void deleteResume(UUID id, Long userId) {
        ResumeEntity resume = findResumeOwnedBy(id, userId);
        resume.softDelete();
        resumeRepository.save(resume);
    }

    // =========================================================================
    // 複製
    // =========================================================================

    /**
     * 履歴書バージョンを複製する。子要素（学歴・職歴・資格・スキル）も複製する。
     *
     * <p>複製時の証明写真: Phase 3 の {@code ResumePhotoService} が担当するため、
     * Phase 2 では {@code photo_key = null} で複製する。
     *
     * <p>タイトルは「{元のタイトル} (コピー)」とする。
     *
     * @param id     複製元の履歴書 ID
     * @param userId 認証ユーザーID
     * @return 複製された履歴書の詳細レスポンス
     */
    @Transactional
    public ResumeDetailResponse duplicateResume(UUID id, Long userId) {
        // 上限チェック
        long count = resumeRepository.findByUserIdOrderByCreatedAtDesc(userId).size();
        if (count >= MAX_RESUMES_PER_USER) {
            throw new BusinessException(ResumeErrorCode.RESUME_003);
        }

        ResumeEntity source = findResumeOwnedBy(id, userId);

        // ヘッダーを複製（photo_key は Phase 3 で対応するため null に）
        ResumeEntity copy = ResumeEntity.builder()
                .userId(userId)
                .title(source.getTitle() + " (コピー)")
                // photo_key は Phase 3 の ResumePhotoService で対応するため null
                .eraFormat(source.getEraFormat())
                .currentAddress(source.getCurrentAddress())
                .currentAddressKana(source.getCurrentAddressKana())
                .contactAddress(source.getContactAddress())
                .contactAddressKana(source.getContactAddressKana())
                .contactPhone(source.getContactPhone())
                .contactEmail(source.getContactEmail())
                .motivation(source.getMotivation())
                .selfPr(source.getSelfPr())
                .personalRequest(source.getPersonalRequest())
                .commuteMinutes(source.getCommuteMinutes())
                .dependentsCount(source.getDependentsCount())
                .hasSpouse(source.getHasSpouse())
                .spouseSupport(source.getSpouseSupport())
                .careerSummary(source.getCareerSummary())
                .skillsSummary(source.getSkillsSummary())
                .build();

        copy = resumeRepository.save(copy);
        final UUID newResumeId = copy.getId();

        // 学歴を複製
        List<ResumeEducationEntity> educations =
                educationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(id);
        for (ResumeEducationEntity edu : educations) {
            ResumeEducationEntity newEdu = ResumeEducationEntity.builder()
                    .resumeId(newResumeId)
                    .entryYear(edu.getEntryYear())
                    .entryMonth(edu.getEntryMonth())
                    .description(edu.getDescription())
                    .displayOrder(edu.getDisplayOrder())
                    .build();
            educationRepository.save(newEdu);
        }

        // 職歴を複製
        List<ResumeCareerEntity> careers =
                careerRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(id);
        for (ResumeCareerEntity career : careers) {
            ResumeCareerEntity newCareer = ResumeCareerEntity.builder()
                    .resumeId(newResumeId)
                    .entryYear(career.getEntryYear())
                    .entryMonth(career.getEntryMonth())
                    .endYear(career.getEndYear())
                    .endMonth(career.getEndMonth())
                    .isCurrent(career.isCurrent())
                    .companyName(career.getCompanyName())
                    .department(career.getDepartment())
                    .employmentType(career.getEmploymentType())
                    .businessSummary(career.getBusinessSummary())
                    .jobDescription(career.getJobDescription())
                    .achievements(career.getAchievements())
                    .includeInRirekisho(career.isIncludeInRirekisho())
                    .includeInShokumukeireki(career.isIncludeInShokumukeireki())
                    .displayOrder(career.getDisplayOrder())
                    .build();
            careerRepository.save(newCareer);
        }

        // 免許・資格を複製
        List<ResumeQualificationEntity> qualifications =
                qualificationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(id);
        for (ResumeQualificationEntity qual : qualifications) {
            ResumeQualificationEntity newQual = ResumeQualificationEntity.builder()
                    .resumeId(newResumeId)
                    .acquiredYear(qual.getAcquiredYear())
                    .acquiredMonth(qual.getAcquiredMonth())
                    .name(qual.getName())
                    .note(qual.getNote())
                    .displayOrder(qual.getDisplayOrder())
                    .build();
            qualificationRepository.save(newQual);
        }

        // 構造化スキルを複製
        List<ResumeSkillEntity> skills =
                skillRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(id);
        for (ResumeSkillEntity skill : skills) {
            ResumeSkillEntity newSkill = ResumeSkillEntity.builder()
                    .resumeId(newResumeId)
                    .skillName(skill.getSkillName())
                    .level(skill.getLevel())
                    .description(skill.getDescription())
                    .displayOrder(skill.getDisplayOrder())
                    .build();
            skillRepository.save(newSkill);
        }

        return toDetailResponse(copy);
    }

    // =========================================================================
    // プライベートユーティリティメソッド
    // =========================================================================

    /**
     * 所有者チェック付きで履歴書を取得する。
     *
     * @param id     履歴書 ID
     * @param userId 認証ユーザーID
     * @return 履歴書エンティティ
     * @throws BusinessException RESUME_001 履歴書が見つからない場合（IDOR 対策）
     */
    private ResumeEntity findResumeOwnedBy(UUID id, Long userId) {
        return resumeRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_001));
    }

    /**
     * タイトルを解決する。空文字または null の場合は自動採番する。
     *
     * @param userId 認証ユーザーID（同日「下書き」の連番判定に使用）
     * @param title  入力タイトル
     * @return 解決済みタイトル
     */
    private String resolveTitle(Long userId, String title) {
        if (title != null && !title.isBlank()) {
            return title;
        }

        String base = "下書き " + LocalDate.now();
        List<String> existingTitles = resumeRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(ResumeEntity::getTitle)
                .toList();

        if (!existingTitles.contains(base)) {
            return base;
        }

        int suffix = 2;
        while (existingTitles.contains(base + " (" + suffix + ")")) {
            suffix++;
        }
        return base + " (" + suffix + ")";
    }

    /**
     * 子要素件数上限チェック。
     *
     * @param req 一括保存リクエスト
     */
    private void checkChildLimits(ResumeFullSaveRequest req) {
        if (req.educations() != null && req.educations().size() > MAX_EDUCATIONS) {
            throw new BusinessException(ResumeErrorCode.RESUME_003);
        }
        if (req.careers() != null && req.careers().size() > MAX_CAREERS) {
            throw new BusinessException(ResumeErrorCode.RESUME_003);
        }
        if (req.qualifications() != null && req.qualifications().size() > MAX_QUALIFICATIONS) {
            throw new BusinessException(ResumeErrorCode.RESUME_003);
        }
        if (req.skills() != null && req.skills().size() > MAX_SKILLS) {
            throw new BusinessException(ResumeErrorCode.RESUME_003);
        }
    }

    /**
     * 元号フォーマット文字列を Enum に変換する。
     *
     * @param value    リクエスト値（null の場合はデフォルト値を使用）
     * @param fallback フォールバック値
     * @return EraFormat Enum
     */
    private ResumeEntity.EraFormat parseEraFormat(String value,
                                                   ResumeEntity.EraFormat fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return ResumeEntity.EraFormat.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback != null ? fallback : ResumeEntity.EraFormat.WESTERN;
        }
    }

    /**
     * Integer を Short に変換する（null 安全）。
     */
    private Short toShort(Integer value) {
        return value != null ? value.shortValue() : null;
    }

    // =========================================================================
    // 子要素差分 upsert
    // =========================================================================

    /**
     * 学歴を差分 upsert する。
     * リクエストにある id の行を更新、id なしの行を新規作成、
     * リクエストにない既存行を論理削除する。
     */
    private void upsertEducations(UUID resumeId,
                                   List<ResumeFullSaveRequest.EducationSaveDto> dtos) {
        List<ResumeEducationEntity> existing =
                educationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId);
        Map<UUID, ResumeEducationEntity> existingMap = existing.stream()
                .collect(Collectors.toMap(ResumeEducationEntity::getId, e -> e));

        Set<UUID> requestedIds = dtos.stream()
                .filter(dto -> dto.id() != null)
                .map(dto -> UUID.fromString(dto.id()))
                .collect(Collectors.toSet());

        // 論理削除: リクエストにない既存行を削除
        for (ResumeEducationEntity entity : existing) {
            if (!requestedIds.contains(entity.getId())) {
                entity.softDelete();
                educationRepository.save(entity);
            }
        }

        // 更新 / 新規作成
        for (ResumeFullSaveRequest.EducationSaveDto dto : dtos) {
            if (dto.id() != null) {
                ResumeEducationEntity entity = existingMap.get(UUID.fromString(dto.id()));
                if (entity != null) {
                    entity.update(
                            dto.entryYear().shortValue(),
                            dto.entryMonth() != null ? dto.entryMonth().byteValue() : null,
                            dto.description(),
                            dto.displayOrder()
                    );
                    educationRepository.save(entity);
                }
            } else {
                ResumeEducationEntity newEdu = ResumeEducationEntity.builder()
                        .resumeId(resumeId)
                        .entryYear(dto.entryYear().shortValue())
                        .entryMonth(dto.entryMonth() != null ? dto.entryMonth().byteValue() : null)
                        .description(dto.description())
                        .displayOrder(dto.displayOrder())
                        .build();
                educationRepository.save(newEdu);
            }
        }
    }

    /**
     * 職歴を差分 upsert する。
     */
    private void upsertCareers(UUID resumeId,
                                List<ResumeFullSaveRequest.CareerSaveDto> dtos) {
        List<ResumeCareerEntity> existing =
                careerRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId);
        Map<UUID, ResumeCareerEntity> existingMap = existing.stream()
                .collect(Collectors.toMap(ResumeCareerEntity::getId, e -> e));

        Set<UUID> requestedIds = dtos.stream()
                .filter(dto -> dto.id() != null)
                .map(dto -> UUID.fromString(dto.id()))
                .collect(Collectors.toSet());

        // 論理削除
        for (ResumeCareerEntity entity : existing) {
            if (!requestedIds.contains(entity.getId())) {
                entity.softDelete();
                careerRepository.save(entity);
            }
        }

        // 更新 / 新規作成
        for (ResumeFullSaveRequest.CareerSaveDto dto : dtos) {
            if (dto.id() != null) {
                ResumeCareerEntity entity = existingMap.get(UUID.fromString(dto.id()));
                if (entity != null) {
                    entity.update(
                            dto.entryYear().shortValue(),
                            dto.entryMonth() != null ? dto.entryMonth().byteValue() : null,
                            dto.endYear() != null ? dto.endYear().shortValue() : null,
                            dto.endMonth() != null ? dto.endMonth().byteValue() : null,
                            dto.isCurrent(),
                            dto.companyName(),
                            dto.department(),
                            dto.employmentType(),
                            dto.businessSummary(),
                            dto.jobDescription(),
                            dto.achievements(),
                            dto.includeInRirekisho(),
                            dto.includeInShokumukeireki(),
                            dto.displayOrder()
                    );
                    careerRepository.save(entity);
                }
            } else {
                ResumeCareerEntity newCareer = ResumeCareerEntity.builder()
                        .resumeId(resumeId)
                        .entryYear(dto.entryYear().shortValue())
                        .entryMonth(dto.entryMonth() != null ? dto.entryMonth().byteValue() : null)
                        .endYear(dto.endYear() != null ? dto.endYear().shortValue() : null)
                        .endMonth(dto.endMonth() != null ? dto.endMonth().byteValue() : null)
                        .isCurrent(dto.isCurrent())
                        .companyName(dto.companyName())
                        .department(dto.department())
                        .employmentType(dto.employmentType())
                        .businessSummary(dto.businessSummary())
                        .jobDescription(dto.jobDescription())
                        .achievements(dto.achievements())
                        .includeInRirekisho(dto.includeInRirekisho())
                        .includeInShokumukeireki(dto.includeInShokumukeireki())
                        .displayOrder(dto.displayOrder())
                        .build();
                careerRepository.save(newCareer);
            }
        }
    }

    /**
     * 免許・資格を差分 upsert する。
     */
    private void upsertQualifications(UUID resumeId,
                                       List<ResumeFullSaveRequest.QualificationSaveDto> dtos) {
        List<ResumeQualificationEntity> existing =
                qualificationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId);
        Map<UUID, ResumeQualificationEntity> existingMap = existing.stream()
                .collect(Collectors.toMap(ResumeQualificationEntity::getId, e -> e));

        Set<UUID> requestedIds = dtos.stream()
                .filter(dto -> dto.id() != null)
                .map(dto -> UUID.fromString(dto.id()))
                .collect(Collectors.toSet());

        // 論理削除
        for (ResumeQualificationEntity entity : existing) {
            if (!requestedIds.contains(entity.getId())) {
                entity.softDelete();
                qualificationRepository.save(entity);
            }
        }

        // 更新 / 新規作成
        for (ResumeFullSaveRequest.QualificationSaveDto dto : dtos) {
            if (dto.id() != null) {
                ResumeQualificationEntity entity = existingMap.get(UUID.fromString(dto.id()));
                if (entity != null) {
                    entity.update(
                            dto.acquiredYear().shortValue(),
                            dto.acquiredMonth() != null ? dto.acquiredMonth().byteValue() : null,
                            dto.name(),
                            dto.note(),
                            dto.displayOrder()
                    );
                    qualificationRepository.save(entity);
                }
            } else {
                ResumeQualificationEntity newQual = ResumeQualificationEntity.builder()
                        .resumeId(resumeId)
                        .acquiredYear(dto.acquiredYear().shortValue())
                        .acquiredMonth(dto.acquiredMonth() != null
                                ? dto.acquiredMonth().byteValue() : null)
                        .name(dto.name())
                        .note(dto.note())
                        .displayOrder(dto.displayOrder())
                        .build();
                qualificationRepository.save(newQual);
            }
        }
    }

    /**
     * 構造化スキルを差分 upsert する。
     */
    private void upsertSkills(UUID resumeId,
                               List<ResumeFullSaveRequest.SkillSaveDto> dtos) {
        List<ResumeSkillEntity> existing =
                skillRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId);
        Map<UUID, ResumeSkillEntity> existingMap = existing.stream()
                .collect(Collectors.toMap(ResumeSkillEntity::getId, e -> e));

        Set<UUID> requestedIds = dtos.stream()
                .filter(dto -> dto.id() != null)
                .map(dto -> UUID.fromString(dto.id()))
                .collect(Collectors.toSet());

        // 論理削除
        for (ResumeSkillEntity entity : existing) {
            if (!requestedIds.contains(entity.getId())) {
                entity.softDelete();
                skillRepository.save(entity);
            }
        }

        // 更新 / 新規作成
        for (ResumeFullSaveRequest.SkillSaveDto dto : dtos) {
            ResumeSkillEntity.SkillLevel level = parseSkillLevel(dto.level());
            if (dto.id() != null) {
                ResumeSkillEntity entity = existingMap.get(UUID.fromString(dto.id()));
                if (entity != null) {
                    entity.update(dto.skillName(), level, dto.description(), dto.displayOrder());
                    skillRepository.save(entity);
                }
            } else {
                ResumeSkillEntity newSkill = ResumeSkillEntity.builder()
                        .resumeId(resumeId)
                        .skillName(dto.skillName())
                        .level(level)
                        .description(dto.description())
                        .displayOrder(dto.displayOrder())
                        .build();
                skillRepository.save(newSkill);
            }
        }
    }

    /**
     * スキルレベル文字列を Enum に変換する（null 安全）。
     */
    private ResumeSkillEntity.SkillLevel parseSkillLevel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ResumeSkillEntity.SkillLevel.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // =========================================================================
    // DTO 変換メソッド
    // =========================================================================

    /**
     * サマリーレスポンスに変換する。
     */
    private ResumeSummaryResponse toSummaryResponse(ResumeEntity entity) {
        return ResumeSummaryResponse.builder()
                .id(entity.getId().toString())
                .title(entity.getTitle())
                .hasPhoto(entity.getPhotoKey() != null)
                .eraFormat(entity.getEraFormat().name())
                .updatedAt(entity.getUpdatedAt() != null
                        ? entity.getUpdatedAt().atZone(ZoneOffset.UTC)
                                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        : null)
                .build();
    }

    /**
     * フル詳細レスポンスに変換する。
     */
    private ResumeDetailResponse toDetailResponse(ResumeEntity entity) {
        UUID resumeId = entity.getId();

        List<ResumeDetailResponse.EducationDto> educationDtos =
                educationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId)
                        .stream()
                        .map(edu -> ResumeDetailResponse.EducationDto.builder()
                                .id(edu.getId().toString())
                                .entryYear(edu.getEntryYear() != null ? edu.getEntryYear() : 0)
                                .entryMonth(edu.getEntryMonth() != null
                                        ? (int) edu.getEntryMonth() : null)
                                .description(edu.getDescription())
                                .displayOrder(edu.getDisplayOrder())
                                .build())
                        .toList();

        List<ResumeDetailResponse.CareerDto> careerDtos =
                careerRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId)
                        .stream()
                        .map(career -> ResumeDetailResponse.CareerDto.builder()
                                .id(career.getId().toString())
                                .entryYear(career.getEntryYear() != null
                                        ? career.getEntryYear() : 0)
                                .entryMonth(career.getEntryMonth() != null
                                        ? (int) career.getEntryMonth() : null)
                                .endYear(career.getEndYear() != null
                                        ? (int) career.getEndYear() : null)
                                .endMonth(career.getEndMonth() != null
                                        ? (int) career.getEndMonth() : null)
                                .isCurrent(career.isCurrent())
                                .companyName(career.getCompanyName())
                                .department(career.getDepartment())
                                .employmentType(career.getEmploymentType())
                                .businessSummary(career.getBusinessSummary())
                                .jobDescription(career.getJobDescription())
                                .achievements(career.getAchievements())
                                .includeInRirekisho(career.isIncludeInRirekisho())
                                .includeInShokumukeireki(career.isIncludeInShokumukeireki())
                                .displayOrder(career.getDisplayOrder())
                                .build())
                        .toList();

        List<ResumeDetailResponse.QualificationDto> qualificationDtos =
                qualificationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId)
                        .stream()
                        .map(qual -> ResumeDetailResponse.QualificationDto.builder()
                                .id(qual.getId().toString())
                                .acquiredYear(qual.getAcquiredYear() != null
                                        ? qual.getAcquiredYear() : 0)
                                .acquiredMonth(qual.getAcquiredMonth() != null
                                        ? (int) qual.getAcquiredMonth() : null)
                                .name(qual.getName())
                                .note(qual.getNote())
                                .displayOrder(qual.getDisplayOrder())
                                .build())
                        .toList();

        List<ResumeDetailResponse.SkillDto> skillDtos =
                skillRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId)
                        .stream()
                        .map(skill -> ResumeDetailResponse.SkillDto.builder()
                                .id(skill.getId().toString())
                                .skillName(skill.getSkillName())
                                .level(skill.getLevel() != null ? skill.getLevel().name() : null)
                                .description(skill.getDescription())
                                .displayOrder(skill.getDisplayOrder())
                                .build())
                        .toList();

        return ResumeDetailResponse.builder()
                .id(entity.getId().toString())
                .title(entity.getTitle())
                .eraFormat(entity.getEraFormat().name())
                // photoUrl は Phase 3 の ResumePhotoService が presigned URL を生成する
                // Phase 2 では null を返す
                .photoUrl(null)
                .currentAddress(entity.getCurrentAddress())
                .currentAddressKana(entity.getCurrentAddressKana())
                .contactAddress(entity.getContactAddress())
                .contactAddressKana(entity.getContactAddressKana())
                .contactPhone(entity.getContactPhone())
                .contactEmail(entity.getContactEmail())
                .motivation(entity.getMotivation())
                .selfPr(entity.getSelfPr())
                .personalRequest(entity.getPersonalRequest())
                .commuteMinutes(entity.getCommuteMinutes() != null
                        ? (int) entity.getCommuteMinutes() : null)
                .dependentsCount(entity.getDependentsCount() != null
                        ? (int) entity.getDependentsCount() : null)
                .hasSpouse(entity.getHasSpouse())
                .spouseSupport(entity.getSpouseSupport())
                .careerSummary(entity.getCareerSummary())
                .skillsSummary(entity.getSkillsSummary())
                .version(entity.getVersion())
                .educations(educationDtos)
                .careers(careerDtos)
                .qualifications(qualificationDtos)
                .skills(skillDtos)
                .build();
    }
}
