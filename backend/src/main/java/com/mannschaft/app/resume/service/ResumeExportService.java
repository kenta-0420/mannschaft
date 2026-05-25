package com.mannschaft.app.resume.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.excel.ExcelGeneratorService;
import com.mannschaft.app.common.pdf.PdfFileNameBuilder;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.resume.ResumeErrorCode;
import com.mannschaft.app.resume.dto.ResumeExportResponse;
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
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PDF / Excel 出力オーケストレータ（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §5.12 / §7 / §8.2
 *
 * <p>プレビュー（R2 非経由・インライン返却）と正式出力（R2 永続保存・presigned URL 返却）の
 * 2 モードを提供する。
 *
 * <p>レート制限は Bucket4j + Caffeine で実現する:
 * <ul>
 *   <li>プレビュー: 120 回 / 時 / ユーザー</li>
 *   <li>正式出力: 30 回 / 時 / ユーザー</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResumeExportService {

    // ──────────────────────────────────────────────────────────────────────
    // 列挙型
    // ──────────────────────────────────────────────────────────────────────

    /** 出力する書類種別。 */
    public enum DocumentType { RIREKISHO, SHOKUMUKEIREKISHO }

    /** 出力形式。 */
    public enum OutputFormat { PDF, EXCEL }

    // ──────────────────────────────────────────────────────────────────────
    // レート制限定数
    // ──────────────────────────────────────────────────────────────────────

    /** プレビューのレート上限（120 回 / 時 / ユーザー）。 */
    private static final int PREVIEW_RATE_PER_HOUR = 120;

    /** 正式出力のレート上限（30 回 / 時 / ユーザー）。 */
    private static final int EXPORT_RATE_PER_HOUR = 30;

    /** バケットのキャッシュ TTL（最終アクセスから 2 時間）。 */
    private static final Duration BUCKET_TTL = Duration.ofHours(2);

    /** キャッシュの最大エントリ数。 */
    private static final long MAX_BUCKETS = 5_000L;

    /** presigned URL の TTL（5 分）。 */
    private static final Duration PRESIGNED_URL_TTL = Duration.ofMinutes(5);

    // ──────────────────────────────────────────────────────────────────────
    // Bucket4j キャッシュ
    // ──────────────────────────────────────────────────────────────────────

    private final Cache<String, Bucket> previewBuckets = Caffeine.newBuilder()
            .expireAfterAccess(BUCKET_TTL)
            .maximumSize(MAX_BUCKETS)
            .build();

    private final Cache<String, Bucket> exportBuckets = Caffeine.newBuilder()
            .expireAfterAccess(BUCKET_TTL)
            .maximumSize(MAX_BUCKETS)
            .build();

    // ──────────────────────────────────────────────────────────────────────
    // 依存サービス
    // ──────────────────────────────────────────────────────────────────────

    private final ResumeRepository resumeRepository;
    private final ResumeEducationRepository educationRepository;
    private final ResumeCareerRepository careerRepository;
    private final ResumeQualificationRepository qualificationRepository;
    private final ResumeSkillRepository skillRepository;
    private final UserRepository userRepository;
    private final PdfGeneratorService pdfGeneratorService;
    private final ExcelGeneratorService excelGeneratorService;
    private final StorageService storageService;
    private final AuditLogService auditLogService;
    private final ResumeEraFormatter eraFormatter;
    private final ResumePhotoService photoService;

    // ──────────────────────────────────────────────────────────────────────
    // 公開 API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * プレビュー: R2 非経由でインライン byte を返す（§5.12.1）。
     *
     * <p>レート制限: 120 回 / 時 / ユーザー
     * <p>監査ログ: 記録しない
     *
     * @param resumeId 対象の履歴書 ID
     * @param userId   認証ユーザー ID
     * @param type     書類種別
     * @param format   出力形式
     * @return 生成バイナリ
     */
    public byte[] generatePreview(UUID resumeId, Long userId,
                                   DocumentType type, OutputFormat format) {
        // レート制限チェック
        Bucket bucket = previewBuckets.get(
                buildBucketKey(userId), k -> newBucketPerHour(PREVIEW_RATE_PER_HOUR));
        if (!bucket.tryConsume(1)) {
            log.warn("プレビューレート上限到達: userId={}, resumeId={}", userId, resumeId);
            // レート超過監査ログ（preview なのでサービス側に記録）
            auditLogService.record(
                    AuditEventType.RESUME_EXPORT_RATE_LIMITED.name(),
                    userId, userId, null, null, null, null, null,
                    "{\"endpoint\":\"preview\",\"resumeId\":\"" + resumeId + "\"}"
            );
            throw new BusinessException(ResumeErrorCode.RESUME_008);
        }

        ResumeEntity resume = loadResume(resumeId, userId);
        UserEntity user = loadUser(userId);
        return generateDocument(resume, user, type, format);
    }

    /**
     * 正式出力: R2 に永続保存し presigned URL を返す（§5.12.2）。
     *
     * <p>レート制限: 30 回 / 時 / ユーザー
     * <p>監査ログ: {@code RESUME_EXPORTED} を記録
     *
     * @param resumeId 対象の履歴書 ID
     * @param userId   認証ユーザー ID
     * @param type     書類種別
     * @param format   出力形式
     * @return 出力レスポンス（presigned URL・ファイル名・有効期限）
     */
    public ResumeExportResponse exportResume(UUID resumeId, Long userId,
                                              DocumentType type, OutputFormat format) {
        // レート制限チェック
        Bucket bucket = exportBuckets.get(
                buildBucketKey(userId), k -> newBucketPerHour(EXPORT_RATE_PER_HOUR));
        if (!bucket.tryConsume(1)) {
            log.warn("出力レート上限到達: userId={}, resumeId={}", userId, resumeId);
            auditLogService.record(
                    AuditEventType.RESUME_EXPORT_RATE_LIMITED.name(),
                    userId, userId, null, null, null, null, null,
                    "{\"endpoint\":\"export\",\"resumeId\":\"" + resumeId + "\"}"
            );
            throw new BusinessException(ResumeErrorCode.RESUME_008);
        }

        ResumeEntity resume = loadResume(resumeId, userId);
        UserEntity user = loadUser(userId);
        byte[] data = generateDocument(resume, user, type, format);

        // R2 に保存
        String ext = resolveExtension(format);
        String typeKey = resolveTypeKey(type);
        String storageKey = buildExportStorageKey(userId, resumeId, typeKey, ext);
        String contentType = resolveContentType(format);
        storageService.upload(storageKey, data, contentType);

        // ファイル名生成
        String fullName = buildFullName(user);
        String docLabel = resolveDocumentLabel(type);
        String fileName = PdfFileNameBuilder.of(docLabel)
                .date(LocalDate.now())
                .identifier(fullName)
                .buildWithExtension("." + ext);

        // presigned URL 発行
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneId.of("Asia/Tokyo"))
                .plus(PRESIGNED_URL_TTL);
        String downloadUrl = storageService.generateDownloadUrl(storageKey, PRESIGNED_URL_TTL);

        // 監査ログ
        auditLogService.record(
                AuditEventType.RESUME_EXPORTED.name(),
                userId, userId, null, null, null, null, null,
                "{\"resumeId\":\"" + resumeId + "\","
                        + "\"type\":\"" + typeKey + "\","
                        + "\"format\":\"" + ext + "\"}"
        );

        return ResumeExportResponse.builder()
                .downloadUrl(downloadUrl)
                .fileName(fileName)
                .expiresAt(expiresAt.toString())
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部: 帳票生成
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 書類種別と形式に応じてバイナリを生成する。
     */
    private byte[] generateDocument(ResumeEntity resume, UserEntity user,
                                    DocumentType type, OutputFormat format) {
        try {
            if (format == OutputFormat.PDF) {
                return generatePdf(resume, user, type);
            } else {
                return generateExcel(resume, user, type);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("帳票生成失敗: resumeId={}, type={}, format={}", resume.getId(), type, format, e);
            throw new BusinessException(ResumeErrorCode.RESUME_009);
        }
    }

    /**
     * PDF を生成する。
     */
    private byte[] generatePdf(ResumeEntity resume, UserEntity user, DocumentType type) {
        Map<String, Object> variables = buildCommonVariables(resume, user);

        if (type == DocumentType.RIREKISHO) {
            // 学歴・職歴（rirekisho 対象）・免許資格
            addRirekishoVariables(variables, resume);
            return pdfGeneratorService.generateFromTemplate("pdf/resume-rirekisho", variables);
        } else {
            // 職務経歴書データ
            addShokumukeirekishoVariables(variables, resume);
            return pdfGeneratorService.generateFromTemplate("pdf/resume-shokumukeirekisho", variables);
        }
    }

    /**
     * Excel を生成する。
     *
     * <p>履歴書: {@code ExcelGeneratorService.fillTemplateWithRows()} を使用（テンプレート差込）。
     * 職務経歴書: {@code ExcelGeneratorService.generateMultiSheetExcel()} でプログラム生成。
     */
    private byte[] generateExcel(ResumeEntity resume, UserEntity user, DocumentType type) {
        if (type == DocumentType.RIREKISHO) {
            return generateRirekishoExcel(resume, user);
        } else {
            return generateShokumukeirekishoExcel(resume, user);
        }
    }

    /**
     * 履歴書 Excel を生成する（テンプレート差込方式）。
     *
     * <p>テンプレートファイルが存在しない場合はプログラム生成にフォールバックする。
     */
    private byte[] generateRirekishoExcel(ResumeEntity resume, UserEntity user) {
        // ヘッダデータ（単発値）
        Map<String, Object> headerData = new LinkedHashMap<>();
        headerData.put("fullName", buildFullName(user));
        headerData.put("fullNameKana", buildFullNameKana(user));
        headerData.put("birthDate", user.getBirthDate() != null ? user.getBirthDate() : "");
        headerData.put("gender", user.getGender() != null ? user.getGender() : "");
        headerData.put("currentAddress", resume.getCurrentAddress() != null ? resume.getCurrentAddress() : "");
        headerData.put("contactPhone", resume.getContactPhone() != null ? resume.getContactPhone() : "");
        headerData.put("contactEmail", resume.getContactEmail() != null ? resume.getContactEmail() : "");
        headerData.put("motivation", resume.getMotivation() != null ? resume.getMotivation() : "");
        headerData.put("selfPr", resume.getSelfPr() != null ? resume.getSelfPr() : "");
        headerData.put("personalRequest", resume.getPersonalRequest() != null ? resume.getPersonalRequest() : "");

        // 学歴・職歴（履歴書対象のみ）の行データ
        List<Map<String, Object>> rows = new ArrayList<>();
        ResumeEntity.EraFormat eraFormat = resume.getEraFormat();

        // 学歴行を追加
        List<ResumeEducationEntity> educations = educationRepository
                .findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resume.getId());
        for (ResumeEducationEntity edu : educations) {
            Map<String, Object> row = new HashMap<>();
            row.put("rowType", "education");
            int year = edu.getEntryYear();
            Integer month = edu.getEntryMonth() != null ? (int) edu.getEntryMonth() : null;
            row.put("yearMonth", eraFormatter.formatYearMonth(year, month, eraFormat));
            row.put("description", edu.getDescription());
            rows.add(row);
        }

        // 職歴行（rirekisho 対象のみ）を追加
        List<ResumeCareerEntity> careers = careerRepository
                .findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resume.getId());
        for (ResumeCareerEntity career : careers) {
            if (!career.isIncludeInRirekisho()) {
                continue;
            }
            Map<String, Object> entryRow = new HashMap<>();
            entryRow.put("rowType", "career_entry");
            int entryYear = career.getEntryYear();
            Integer entryMonth = career.getEntryMonth() != null ? (int) career.getEntryMonth() : null;
            entryRow.put("yearMonth", eraFormatter.formatYearMonth(entryYear, entryMonth, eraFormat));
            entryRow.put("description", career.getCompanyName() + " 入社");
            rows.add(entryRow);

            if (career.isCurrent()) {
                Map<String, Object> currentRow = new HashMap<>();
                currentRow.put("rowType", "career_current");
                currentRow.put("yearMonth", "");
                currentRow.put("description", "現在に至る");
                rows.add(currentRow);
            } else if (career.getEndYear() != null) {
                Map<String, Object> endRow = new HashMap<>();
                endRow.put("rowType", "career_end");
                int endYear = career.getEndYear();
                Integer endMonth = career.getEndMonth() != null ? (int) career.getEndMonth() : null;
                endRow.put("yearMonth", eraFormatter.formatYearMonth(endYear, endMonth, eraFormat));
                endRow.put("description", career.getCompanyName() + " 退社");
                rows.add(endRow);
            }
        }

        // テンプレートファイルを試みる（存在しない場合はプログラム生成にフォールバック）
        try {
            var templateStream = getClass().getResourceAsStream(
                    "/templates/excel/rirekisho.xlsx");
            if (templateStream != null) {
                return excelGeneratorService.fillTemplateWithRows(
                        templateStream, headerData, rows, "rows[]");
            }
        } catch (Exception e) {
            log.warn("履歴書 Excel テンプレート読み込み失敗。プログラム生成にフォールバック: {}", e.getMessage());
        }

        // プログラム生成フォールバック
        return generateRirekishoExcelProgrammatically(resume, user, rows);
    }

    /**
     * 履歴書 Excel プログラム生成（テンプレートなしフォールバック）。
     */
    private byte[] generateRirekishoExcelProgrammatically(ResumeEntity resume, UserEntity user,
                                                            List<Map<String, Object>> rows) {
        List<String> headers = List.of("年月", "学歴・職歴");
        List<List<Object>> dataRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            dataRows.add(List.of(
                    row.getOrDefault("yearMonth", ""),
                    row.getOrDefault("description", "")));
        }

        // ヘッダー情報シート
        List<String> infoHeaders = List.of("項目", "内容");
        List<List<Object>> infoRows = List.of(
                List.of("氏名", buildFullName(user)),
                List.of("フリガナ", buildFullNameKana(user)),
                List.of("生年月日", user.getBirthDate() != null ? user.getBirthDate() : ""),
                List.of("現住所", resume.getCurrentAddress() != null ? resume.getCurrentAddress() : ""),
                List.of("電話", resume.getContactPhone() != null ? resume.getContactPhone() : ""),
                List.of("メール", resume.getContactEmail() != null ? resume.getContactEmail() : ""),
                List.of("志望動機", resume.getMotivation() != null ? resume.getMotivation() : ""),
                List.of("自己PR", resume.getSelfPr() != null ? resume.getSelfPr() : ""),
                List.of("本人希望", resume.getPersonalRequest() != null ? resume.getPersonalRequest() : "")
        );

        return excelGeneratorService.generateMultiSheetExcel(List.of(
                new ExcelGeneratorService.ExcelSheet("基本情報", infoHeaders, infoRows),
                new ExcelGeneratorService.ExcelSheet("学歴・職歴", headers, dataRows)
        ));
    }

    /**
     * 職務経歴書 Excel をプログラム生成する。
     */
    private byte[] generateShokumukeirekishoExcel(ResumeEntity resume, UserEntity user) {
        ResumeEntity.EraFormat eraFormat = resume.getEraFormat();

        // 基本情報シート
        List<String> infoHeaders = List.of("項目", "内容");
        List<List<Object>> infoRows = new ArrayList<>();
        infoRows.add(List.of("氏名", buildFullName(user)));
        infoRows.add(List.of("フリガナ", buildFullNameKana(user)));
        if (resume.getCareerSummary() != null) {
            infoRows.add(List.of("職務要約", resume.getCareerSummary()));
        }
        if (resume.getSkillsSummary() != null) {
            infoRows.add(List.of("活かせる経験・知識・技術", resume.getSkillsSummary()));
        }

        // 職歴詳細シート
        List<ResumeCareerEntity> careers = careerRepository
                .findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resume.getId());
        List<String> careerHeaders = List.of("会社名", "在職期間", "部署", "雇用形態", "事業内容", "職務内容", "実績");
        List<List<Object>> careerRows = new ArrayList<>();
        for (ResumeCareerEntity career : careers) {
            if (!career.isIncludeInShokumukeireki()) {
                continue;
            }
            int entryYear = career.getEntryYear();
            Integer entryMonth = career.getEntryMonth() != null ? (int) career.getEntryMonth() : null;
            String period = eraFormatter.formatYearMonth(entryYear, entryMonth, eraFormat);
            if (career.isCurrent()) {
                period += " 〜 現在";
            } else if (career.getEndYear() != null) {
                int endYear = career.getEndYear();
                Integer endMonth = career.getEndMonth() != null ? (int) career.getEndMonth() : null;
                period += " 〜 " + eraFormatter.formatYearMonth(endYear, endMonth, eraFormat);
            }
            careerRows.add(List.of(
                    career.getCompanyName(),
                    period,
                    career.getDepartment() != null ? career.getDepartment() : "",
                    career.getEmploymentType() != null ? career.getEmploymentType() : "",
                    career.getBusinessSummary() != null ? career.getBusinessSummary() : "",
                    career.getJobDescription() != null ? career.getJobDescription() : "",
                    career.getAchievements() != null ? career.getAchievements() : ""
            ));
        }

        // スキルシート
        List<ResumeSkillEntity> skills = skillRepository
                .findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resume.getId());
        List<String> skillHeaders = List.of("スキル名", "習熟度", "補足");
        List<List<Object>> skillRows = new ArrayList<>();
        for (ResumeSkillEntity skill : skills) {
            String levelLabel = skill.getLevel() != null
                    ? resolveSkillLevelLabel(skill.getLevel()) : "";
            skillRows.add(List.of(
                    skill.getSkillName(),
                    levelLabel,
                    skill.getDescription() != null ? skill.getDescription() : ""
            ));
        }

        List<ExcelGeneratorService.ExcelSheet> sheets = new ArrayList<>();
        sheets.add(new ExcelGeneratorService.ExcelSheet("基本情報", infoHeaders, infoRows));
        if (!careerRows.isEmpty()) {
            sheets.add(new ExcelGeneratorService.ExcelSheet("職歴詳細", careerHeaders, careerRows));
        }
        if (!skillRows.isEmpty()) {
            sheets.add(new ExcelGeneratorService.ExcelSheet("スキル", skillHeaders, skillRows));
        }
        return excelGeneratorService.generateMultiSheetExcel(sheets);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部: Thymeleaf variables 構築
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 共通変数を構築する（氏名・生年月日・写真等）。
     */
    private Map<String, Object> buildCommonVariables(ResumeEntity resume, UserEntity user) {
        Map<String, Object> vars = new HashMap<>();

        // ユーザー情報（UserEntity から出力時参照）
        vars.put("fullName", buildFullName(user));
        vars.put("fullNameKana", buildFullNameKana(user));
        vars.put("birthDate", user.getBirthDate() != null ? user.getBirthDate() : "");
        vars.put("gender", user.getGender() != null ? user.getGender() : "");

        // 証明写真（Base64 埋め込み用）
        String photoBase64 = null;
        String photoMimeType = "image/jpeg";
        if (resume.getPhotoKey() != null) {
            try {
                byte[] photoBytes = storageService.download(resume.getPhotoKey());
                photoBase64 = Base64.getEncoder().encodeToString(photoBytes);
                // photo_key の拡張子からMIMEタイプを推定
                if (resume.getPhotoKey().endsWith(".png")) {
                    photoMimeType = "image/png";
                }
            } catch (Exception e) {
                log.warn("証明写真の取得失敗（スキップ）: photoKey={}", resume.getPhotoKey(), e);
            }
        }
        vars.put("photoBase64", photoBase64);
        vars.put("photoMimeType", photoMimeType);

        // resume ヘッダー情報
        vars.put("currentAddress", resume.getCurrentAddress());
        vars.put("currentAddressKana", resume.getCurrentAddressKana());
        vars.put("contactAddress", resume.getContactAddress());
        vars.put("contactAddressKana", resume.getContactAddressKana());
        vars.put("contactPhone", resume.getContactPhone());
        vars.put("contactEmail", resume.getContactEmail());
        vars.put("motivation", resume.getMotivation());
        vars.put("selfPr", resume.getSelfPr());
        vars.put("personalRequest", resume.getPersonalRequest());
        vars.put("commuteMinutes", resume.getCommuteMinutes());
        vars.put("dependentsCount", resume.getDependentsCount());
        vars.put("hasSpouse", resume.getHasSpouse());
        vars.put("spouseSupport", resume.getSpouseSupport());

        return vars;
    }

    /**
     * 履歴書用変数を追加する（学歴・職歴行リスト・免許資格）。
     */
    private void addRirekishoVariables(Map<String, Object> vars, ResumeEntity resume) {
        ResumeEntity.EraFormat eraFormat = resume.getEraFormat();

        // 学歴行（表示用 Map リスト）
        List<ResumeEducationEntity> educations = educationRepository
                .findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resume.getId());
        List<Map<String, String>> educationRows = new ArrayList<>();
        for (ResumeEducationEntity edu : educations) {
            Map<String, String> row = new HashMap<>();
            int year = edu.getEntryYear();
            Integer month = edu.getEntryMonth() != null ? (int) edu.getEntryMonth() : null;
            row.put("yearMonth", eraFormatter.formatYearMonth(year, month, eraFormat));
            row.put("description", edu.getDescription());
            educationRows.add(row);
        }
        vars.put("educationRows", educationRows);

        // 職歴行（rirekisho 対象のみ）
        List<ResumeCareerEntity> careers = careerRepository
                .findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resume.getId());
        List<Map<String, String>> careerRows = new ArrayList<>();
        for (ResumeCareerEntity career : careers) {
            if (!career.isIncludeInRirekisho()) {
                continue;
            }
            Map<String, String> entryRow = new HashMap<>();
            int entryYear = career.getEntryYear();
            Integer entryMonth = career.getEntryMonth() != null ? (int) career.getEntryMonth() : null;
            entryRow.put("yearMonth", eraFormatter.formatYearMonth(entryYear, entryMonth, eraFormat));
            entryRow.put("description", career.getCompanyName() + " 入社");
            careerRows.add(entryRow);

            if (career.isCurrent()) {
                Map<String, String> currentRow = new HashMap<>();
                currentRow.put("yearMonth", "");
                currentRow.put("description", "現在に至る");
                careerRows.add(currentRow);
            } else if (career.getEndYear() != null) {
                Map<String, String> endRow = new HashMap<>();
                int endYear = career.getEndYear();
                Integer endMonth = career.getEndMonth() != null ? (int) career.getEndMonth() : null;
                endRow.put("yearMonth", eraFormatter.formatYearMonth(endYear, endMonth, eraFormat));
                endRow.put("description", career.getCompanyName() + " 退社");
                careerRows.add(endRow);
            }
        }
        vars.put("careerRows", careerRows);

        // 免許・資格
        List<ResumeQualificationEntity> qualifications = qualificationRepository
                .findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resume.getId());
        List<Map<String, String>> qualRows = new ArrayList<>();
        for (ResumeQualificationEntity qual : qualifications) {
            Map<String, String> row = new HashMap<>();
            int year = qual.getAcquiredYear();
            Integer month = qual.getAcquiredMonth() != null ? (int) qual.getAcquiredMonth() : null;
            row.put("yearMonth", eraFormatter.formatYearMonth(year, month, eraFormat));
            row.put("name", qual.getName());
            row.put("note", qual.getNote() != null ? qual.getNote() : "");
            qualRows.add(row);
        }
        vars.put("qualificationRows", qualRows);
    }

    /**
     * 職務経歴書用変数を追加する（職務要約・スキル・職歴詳細）。
     */
    private void addShokumukeirekishoVariables(Map<String, Object> vars, ResumeEntity resume) {
        ResumeEntity.EraFormat eraFormat = resume.getEraFormat();

        vars.put("careerSummary", resume.getCareerSummary());
        vars.put("skillsSummary", resume.getSkillsSummary());

        // 職歴詳細（shokumukeireki 対象のみ）
        List<ResumeCareerEntity> careers = careerRepository
                .findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resume.getId());
        List<Map<String, Object>> careerDetails = new ArrayList<>();
        for (ResumeCareerEntity career : careers) {
            if (!career.isIncludeInShokumukeireki()) {
                continue;
            }
            Map<String, Object> detail = new HashMap<>();
            detail.put("companyName", career.getCompanyName());
            detail.put("department", career.getDepartment());
            detail.put("employmentType", career.getEmploymentType());
            detail.put("businessSummary", career.getBusinessSummary());
            detail.put("jobDescription", career.getJobDescription());
            detail.put("achievements", career.getAchievements());

            int entryYear = career.getEntryYear();
            Integer entryMonth = career.getEntryMonth() != null ? (int) career.getEntryMonth() : null;
            detail.put("entryPeriod", eraFormatter.formatYearMonth(entryYear, entryMonth, eraFormat));

            if (career.isCurrent()) {
                detail.put("endPeriod", "現在");
                detail.put("isCurrent", true);
            } else if (career.getEndYear() != null) {
                int endYear = career.getEndYear();
                Integer endMonth = career.getEndMonth() != null ? (int) career.getEndMonth() : null;
                detail.put("endPeriod", eraFormatter.formatYearMonth(endYear, endMonth, eraFormat));
                detail.put("isCurrent", false);
            } else {
                detail.put("endPeriod", "");
                detail.put("isCurrent", false);
            }
            careerDetails.add(detail);
        }
        vars.put("careerDetails", careerDetails);

        // 構造化スキル一覧
        List<ResumeSkillEntity> skills = skillRepository
                .findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resume.getId());
        List<Map<String, String>> skillRows = new ArrayList<>();
        for (ResumeSkillEntity skill : skills) {
            Map<String, String> row = new HashMap<>();
            row.put("skillName", skill.getSkillName());
            row.put("level", skill.getLevel() != null
                    ? resolveSkillLevelLabel(skill.getLevel()) : "");
            row.put("description", skill.getDescription() != null ? skill.getDescription() : "");
            skillRows.add(row);
        }
        vars.put("skillRows", skillRows);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部: ユーティリティ
    // ──────────────────────────────────────────────────────────────────────

    private ResumeEntity loadResume(UUID resumeId, Long userId) {
        return resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_001));
    }

    private UserEntity loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResumeErrorCode.RESUME_001));
    }

    private String buildFullName(UserEntity user) {
        String lastName  = user.getLastName()  != null ? user.getLastName()  : "";
        String firstName = user.getFirstName() != null ? user.getFirstName() : "";
        String fullName = (lastName + firstName).trim();
        return fullName.isEmpty() ? "氏名未設定" : fullName;
    }

    private String buildFullNameKana(UserEntity user) {
        String lastKana  = user.getLastNameKana()  != null ? user.getLastNameKana()  : "";
        String firstKana = user.getFirstNameKana() != null ? user.getFirstNameKana() : "";
        return (lastKana + firstKana).trim();
    }

    private String resolveExtension(OutputFormat format) {
        return (format == OutputFormat.PDF) ? "pdf" : "xlsx";
    }

    private String resolveTypeKey(DocumentType type) {
        return (type == DocumentType.RIREKISHO) ? "rirekisho" : "shokumukeirekisho";
    }

    private String resolveContentType(OutputFormat format) {
        return (format == OutputFormat.PDF)
                ? "application/pdf"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    private String resolveDocumentLabel(DocumentType type) {
        return (type == DocumentType.RIREKISHO) ? "履歴書" : "職務経歴書";
    }

    private String buildExportStorageKey(Long userId, UUID resumeId, String typeKey, String ext) {
        return "user/" + userId + "/resume/" + resumeId + "/" + typeKey + "." + ext;
    }

    private String buildBucketKey(Long userId) {
        return "resume:u:" + userId;
    }

    private Bucket newBucketPerHour(int capacity) {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacity, Duration.ofHours(1)))
                .build();
    }

    /**
     * スキルレベルを日本語ラベルに変換する。
     */
    private String resolveSkillLevelLabel(ResumeSkillEntity.SkillLevel level) {
        return switch (level) {
            case BEGINNER      -> "初級";
            case INTERMEDIATE  -> "中級";
            case ADVANCED      -> "上級";
            case EXPERT        -> "エキスパート";
        };
    }
}
