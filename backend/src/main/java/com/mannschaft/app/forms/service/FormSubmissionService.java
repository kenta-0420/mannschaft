package com.mannschaft.app.forms.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.forms.FormErrorCode;
import com.mannschaft.app.forms.FormFieldType;
import com.mannschaft.app.forms.FormMapper;
import com.mannschaft.app.forms.FormStatus;
import com.mannschaft.app.forms.dto.CreateFormSubmissionRequest;
import com.mannschaft.app.forms.dto.FormSubmissionResponse;
import com.mannschaft.app.forms.dto.FormUploadUrlRequest;
import com.mannschaft.app.forms.dto.FormUploadUrlResponse;
import com.mannschaft.app.forms.dto.SubmissionValueRequest;
import com.mannschaft.app.forms.dto.UpdateFormSubmissionRequest;
import com.mannschaft.app.forms.entity.FormSubmissionEntity;
import com.mannschaft.app.forms.entity.FormSubmissionValueEntity;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import com.mannschaft.app.forms.repository.FormSubmissionRepository;
import com.mannschaft.app.forms.repository.FormSubmissionValueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * フォーム提出サービス。提出のCRUD・ステータス遷移を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormSubmissionService {

    private final FormSubmissionRepository submissionRepository;
    private final FormSubmissionValueRepository valueRepository;
    private final FormTemplateService templateService;
    private final FormMapper formMapper;
    private final StorageService storageService;

    /** Pre-signed upload URL の有効期間（10 分）。設計書 §4 添付アップロード API 準拠。 */
    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(10);

    /** 一般ファイル添付の最大サイズ（10MB）。設計書 §6 セキュリティ準拠。 */
    private static final long FILE_MAX_BYTES = 10L * 1024 * 1024;

    /** 署名 PNG の最大サイズ（500KB）。設計書 §6 セキュリティ準拠。 */
    private static final long SIGNATURE_MAX_BYTES = 500L * 1024;

    /** 添付ファイル許可 MIME（一般）。 */
    private static final Set<String> ALLOWED_FILE_CONTENT_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp"
    );

    /** 署名画像の許可 MIME。 */
    private static final Set<String> ALLOWED_SIGNATURE_CONTENT_TYPES = Set.of("image/png");

    /** field_key が "signature" を含む場合は署名扱いとする。 */
    private static final String SIGNATURE_FIELD_KEY_HINT = "signature";

    /**
     * テンプレートに紐付く提出一覧をページング取得する。
     *
     * @param templateId テンプレートID
     * @param status     ステータスフィルタ（null の場合は全件）
     * @param pageable   ページング情報
     * @return 提出レスポンスのページ
     */
    public Page<FormSubmissionResponse> listSubmissionsByTemplate(
            Long templateId, String status, Pageable pageable) {
        Page<FormSubmissionEntity> page;
        if (status != null) {
            com.mannschaft.app.forms.SubmissionStatus submissionStatus =
                    com.mannschaft.app.forms.SubmissionStatus.valueOf(status);
            page = submissionRepository.findByTemplateIdAndStatusOrderByCreatedAtDesc(
                    templateId, submissionStatus, pageable);
        } else {
            page = submissionRepository.findByTemplateIdOrderByCreatedAtDesc(templateId, pageable);
        }
        return page.map(entity -> {
            List<FormSubmissionValueEntity> values = valueRepository.findBySubmissionId(entity.getId());
            return formMapper.toSubmissionResponseWithValues(entity, values);
        });
    }

    /**
     * ユーザーの提出一覧をページング取得する。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param pageable  ページング情報
     * @return 提出レスポンスのページ
     */
    public Page<FormSubmissionResponse> listMySubmissions(
            Long userId, String scopeType, Long scopeId, Pageable pageable) {
        Page<FormSubmissionEntity> page = submissionRepository
                .findBySubmittedByAndScopeTypeAndScopeIdOrderByCreatedAtDesc(
                        userId, scopeType, scopeId, pageable);
        return page.map(entity -> {
            List<FormSubmissionValueEntity> values = valueRepository.findBySubmissionId(entity.getId());
            return formMapper.toSubmissionResponseWithValues(entity, values);
        });
    }

    /**
     * 提出詳細を取得する。
     *
     * @param submissionId 提出ID
     * @return 提出レスポンス
     */
    public FormSubmissionResponse getSubmission(Long submissionId) {
        FormSubmissionEntity entity = findSubmissionOrThrow(submissionId);
        List<FormSubmissionValueEntity> values = valueRepository.findBySubmissionId(submissionId);
        return formMapper.toSubmissionResponseWithValues(entity, values);
    }

    /**
     * 提出を作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    提出者ユーザーID
     * @param request   作成リクエスト
     * @return 作成された提出レスポンス
     */
    @Transactional
    public FormSubmissionResponse createSubmission(
            String scopeType, Long scopeId, Long userId, CreateFormSubmissionRequest request) {
        FormTemplateEntity template = templateService.getTemplateEntity(request.getTemplateId());

        if (template.getStatus() != FormStatus.PUBLISHED) {
            throw new BusinessException(FormErrorCode.TEMPLATE_NOT_PUBLISHED);
        }

        if (template.isDeadlinePassed()) {
            throw new BusinessException(FormErrorCode.TEMPLATE_DEADLINE_PASSED);
        }

        if (template.getMaxSubmissionsPerUser() > 0) {
            long existingCount = submissionRepository.countByTemplateIdAndSubmittedBy(
                    request.getTemplateId(), userId);
            if (existingCount >= template.getMaxSubmissionsPerUser()) {
                throw new BusinessException(FormErrorCode.MAX_SUBMISSIONS_EXCEEDED);
            }
        }

        long userSubmissionCount = submissionRepository.countByTemplateIdAndSubmittedBy(
                request.getTemplateId(), userId);

        FormSubmissionEntity entity = FormSubmissionEntity.builder()
                .templateId(request.getTemplateId())
                .scopeType(scopeType)
                .scopeId(scopeId)
                .submittedBy(userId)
                .submissionCountForUser((int) userSubmissionCount + 1)
                .build();

        if (Boolean.TRUE.equals(request.getSubmitImmediately())) {
            entity.submit();
        }

        FormSubmissionEntity saved = submissionRepository.save(entity);

        List<FormSubmissionValueEntity> values = List.of();
        if (request.getValues() != null && !request.getValues().isEmpty()) {
            values = saveValues(saved.getId(), request.getValues());
        }

        if (Boolean.TRUE.equals(request.getSubmitImmediately())) {
            template.incrementSubmissionCount();
        }

        log.info("提出作成: templateId={}, submissionId={}, userId={}", request.getTemplateId(), saved.getId(), userId);
        return formMapper.toSubmissionResponseWithValues(saved, values);
    }

    /**
     * F08.7.1/06: 大会提出枠（tournament_submission_requirement）に紐付けてフォーム提出を作成する。
     *
     * <p>tournament ドメインの {@code TournamentSubmissionRequirementService} から内部委譲で呼ばれる
     * ファサード用メソッド。{@link #createSubmission} と同じ提出ロジック（テンプレート公開状態・締切・
     * 提出回数上限のチェック、値の保存、即時提出時の SUBMITTED 遷移）を再利用しつつ、
     * {@code form_submissions.tournament_submission_requirement_id}（BINARY(16)／UUID）に
     * 提出枠 ID を設定して連結する（設計書 §2.1）。</p>
     *
     * <p>大会の書類提出はチーム単位（{@code scopeType='TEAM'} / {@code scopeId=teamId}）で 1 件に
     * 正規化される。同一提出枠・同一チームの既存提出があり、それが編集可能（DRAFT / RETURNED）であれば
     * 新規作成せずに上書き再提出する（差し戻し再提出フロー）。SUBMITTED 以降の提出が既にある場合は
     * 認可・状態判定の責務を呼出元（tournament ファサード）に委ねるため、本メソッドは新規作成を行わず
     * 既存をそのまま返す前に上書きはしない（編集不可ステータスはここでは弾かず既存を返却）。</p>
     *
     * @param scopeType        スコープ種別（"TEAM" 固定想定）
     * @param scopeId          スコープ ID（提出チーム ID）
     * @param userId           提出者ユーザー ID
     * @param requirementId    大会提出枠 ID（UUIDv7）
     * @param request          作成リクエスト（template_id・値・即時提出フラグ）
     * @return 作成／更新された提出レスポンス
     */
    @Transactional
    public FormSubmissionResponse createSubmissionForRequirement(
            String scopeType, Long scopeId, Long userId, UUID requirementId,
            CreateFormSubmissionRequest request) {
        FormTemplateEntity template = templateService.getTemplateEntity(request.getTemplateId());

        if (template.getStatus() != FormStatus.PUBLISHED) {
            throw new BusinessException(FormErrorCode.TEMPLATE_NOT_PUBLISHED);
        }
        if (template.isDeadlinePassed()) {
            throw new BusinessException(FormErrorCode.TEMPLATE_DEADLINE_PASSED);
        }

        // 同一提出枠・同一チームの既存提出を探し、編集可能なら上書き再提出する
        FormSubmissionEntity existing = submissionRepository
                .findByTournamentSubmissionRequirementIdAndScopeTypeAndScopeId(requirementId, scopeType, scopeId)
                .filter(FormSubmissionEntity::isEditable)
                .orElse(null);

        boolean submitNow = Boolean.TRUE.equals(request.getSubmitImmediately());

        FormSubmissionEntity entity;
        if (existing != null) {
            entity = existing;
            valueRepository.deleteBySubmissionId(entity.getId());
            if (submitNow) {
                entity.submit();
            }
        } else {
            long userSubmissionCount = submissionRepository.countByTemplateIdAndSubmittedBy(
                    request.getTemplateId(), userId);
            entity = FormSubmissionEntity.builder()
                    .templateId(request.getTemplateId())
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .tournamentSubmissionRequirementId(requirementId)
                    .submittedBy(userId)
                    .submissionCountForUser((int) userSubmissionCount + 1)
                    .build();
            if (submitNow) {
                entity.submit();
            }
        }

        FormSubmissionEntity saved = submissionRepository.save(entity);

        List<FormSubmissionValueEntity> values = List.of();
        if (request.getValues() != null && !request.getValues().isEmpty()) {
            values = saveValues(saved.getId(), request.getValues());
        }

        if (submitNow) {
            template.incrementSubmissionCount();
        }

        log.info("大会提出作成: requirementId={}, submissionId={}, scopeId={}, userId={}",
                requirementId, saved.getId(), scopeId, userId);
        return formMapper.toSubmissionResponseWithValues(saved, values);
    }

    /**
     * 提出を更新する。
     *
     * @param submissionId 提出ID
     * @param userId       ユーザーID
     * @param request      更新リクエスト
     * @return 更新された提出レスポンス
     */
    @Transactional
    public FormSubmissionResponse updateSubmission(
            Long submissionId, Long userId, UpdateFormSubmissionRequest request) {
        FormSubmissionEntity entity = submissionRepository.findByIdAndSubmittedBy(submissionId, userId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.SUBMISSION_NOT_FOUND));

        if (!entity.isEditable()) {
            FormTemplateEntity template = templateService.getTemplateEntity(entity.getTemplateId());
            if (!Boolean.TRUE.equals(template.getAllowEditAfterSubmit())) {
                throw new BusinessException(FormErrorCode.EDIT_AFTER_SUBMIT_NOT_ALLOWED);
            }
        }

        if (Boolean.TRUE.equals(request.getSubmitImmediately()) && !entity.isSubmitted()) {
            entity.submit();
            FormTemplateEntity template = templateService.getTemplateEntity(entity.getTemplateId());
            template.incrementSubmissionCount();
        }

        FormSubmissionEntity saved = submissionRepository.save(entity);

        List<FormSubmissionValueEntity> values;
        if (request.getValues() != null) {
            valueRepository.deleteBySubmissionId(submissionId);
            values = saveValues(submissionId, request.getValues());
        } else {
            values = valueRepository.findBySubmissionId(submissionId);
        }

        log.info("提出更新: submissionId={}", submissionId);
        return formMapper.toSubmissionResponseWithValues(saved, values);
    }

    /**
     * 提出を実行する（DRAFT/RETURNED → SUBMITTED 遷移）。
     *
     * <p>F05.7 Phase 11 第一陣: 外部 API 用エントリーポイント。
     * 自分の提出のみ submit 可能（所有者チェック）。すでに SUBMITTED 以降の場合は INVALID_SUBMISSION_STATUS。
     * テンプレートの submissionCount をインクリメントする。
     * 楽観的ロック (@Version) は Entity 側で保持される。</p>
     *
     * @param submissionId 提出ID
     * @param userId       ユーザーID（所有者）
     * @return 更新された提出レスポンス
     */
    @Transactional
    public FormSubmissionResponse submit(Long submissionId, Long userId) {
        FormSubmissionEntity entity = submissionRepository.findByIdAndSubmittedBy(submissionId, userId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.SUBMISSION_NOT_FOUND));

        if (!entity.isEditable()) {
            throw new BusinessException(FormErrorCode.INVALID_SUBMISSION_STATUS);
        }

        FormTemplateEntity template = templateService.getTemplateEntity(entity.getTemplateId());

        if (template.getStatus() != FormStatus.PUBLISHED) {
            throw new BusinessException(FormErrorCode.TEMPLATE_NOT_PUBLISHED);
        }

        if (template.isDeadlinePassed()) {
            throw new BusinessException(FormErrorCode.TEMPLATE_DEADLINE_PASSED);
        }

        entity.submit();
        FormSubmissionEntity saved = submissionRepository.save(entity);
        template.incrementSubmissionCount();
        List<FormSubmissionValueEntity> values = valueRepository.findBySubmissionId(submissionId);

        log.info("提出実行: submissionId={}, userId={}", submissionId, userId);
        return formMapper.toSubmissionResponseWithValues(saved, values);
    }

    /**
     * 提出を承認する。
     *
     * @param submissionId 提出ID
     * @return 更新された提出レスポンス
     */
    @Transactional
    public FormSubmissionResponse approveSubmission(Long submissionId) {
        FormSubmissionEntity entity = findSubmissionOrThrow(submissionId);

        if (!entity.isSubmitted()) {
            throw new BusinessException(FormErrorCode.INVALID_SUBMISSION_STATUS);
        }

        entity.approve();
        FormSubmissionEntity saved = submissionRepository.save(entity);
        List<FormSubmissionValueEntity> values = valueRepository.findBySubmissionId(submissionId);

        log.info("提出承認: submissionId={}", submissionId);
        return formMapper.toSubmissionResponseWithValues(saved, values);
    }

    /**
     * 提出を却下する。
     *
     * @param submissionId 提出ID
     * @return 更新された提出レスポンス
     */
    @Transactional
    public FormSubmissionResponse rejectSubmission(Long submissionId) {
        FormSubmissionEntity entity = findSubmissionOrThrow(submissionId);

        if (!entity.isSubmitted()) {
            throw new BusinessException(FormErrorCode.INVALID_SUBMISSION_STATUS);
        }

        entity.reject();
        FormSubmissionEntity saved = submissionRepository.save(entity);
        List<FormSubmissionValueEntity> values = valueRepository.findBySubmissionId(submissionId);

        log.info("提出却下: submissionId={}", submissionId);
        return formMapper.toSubmissionResponseWithValues(saved, values);
    }

    /**
     * 提出を差し戻す。
     *
     * @param submissionId 提出ID
     * @return 更新された提出レスポンス
     */
    @Transactional
    public FormSubmissionResponse returnSubmission(Long submissionId) {
        FormSubmissionEntity entity = findSubmissionOrThrow(submissionId);

        if (!entity.isSubmitted()) {
            throw new BusinessException(FormErrorCode.INVALID_SUBMISSION_STATUS);
        }

        entity.returnSubmission();
        FormSubmissionEntity saved = submissionRepository.save(entity);
        List<FormSubmissionValueEntity> values = valueRepository.findBySubmissionId(submissionId);

        log.info("提出差し戻し: submissionId={}", submissionId);
        return formMapper.toSubmissionResponseWithValues(saved, values);
    }

    /**
     * 提出を論理削除する。
     *
     * @param submissionId 提出ID
     * @param userId       ユーザーID
     */
    @Transactional
    public void deleteSubmission(Long submissionId, Long userId) {
        FormSubmissionEntity entity = submissionRepository.findByIdAndSubmittedBy(submissionId, userId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.SUBMISSION_NOT_FOUND));
        entity.softDelete();
        submissionRepository.save(entity);
        log.info("提出削除: submissionId={}", submissionId);
    }

    /**
     * 提出を取得する。存在しない場合は例外をスローする。
     */
    private FormSubmissionEntity findSubmissionOrThrow(Long submissionId) {
        return submissionRepository.findById(submissionId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.SUBMISSION_NOT_FOUND));
    }

    /**
     * F05.7 Phase 11 第四陣 4-B: ユーザー横断「自分の提出」一覧をページング取得する。
     *
     * <p>{@code GET /api/v1/me/form-submissions} 用。スコープを問わず提出者で絞り込む。</p>
     *
     * @param userId   ユーザー ID
     * @param pageable ページング
     * @return 提出レスポンスのページ
     */
    public Page<FormSubmissionResponse> listMySubmissions(Long userId, Pageable pageable) {
        Page<FormSubmissionEntity> page =
                submissionRepository.findBySubmittedByOrderByCreatedAtDesc(userId, pageable);
        return page.map(entity -> {
            List<FormSubmissionValueEntity> values = valueRepository.findBySubmissionId(entity.getId());
            return formMapper.toSubmissionResponseWithValues(entity, values);
        });
    }

    /**
     * F05.7 Phase 11 第四陣 4-B: フォーム添付 / 署名 PNG の Pre-signed アップロード URL を発行する。
     *
     * <p>{@code POST /api/v1/{scopeType}/{scopeId}/form-submissions/{id}/upload-url} 用。
     * 認可は「提出者本人 + 編集可能ステータス（DRAFT / RETURNED）」のみ通す。
     * field_key に "signature" が含まれていれば署名扱い（最大 500KB / image/png のみ）。</p>
     *
     * @param scopeType     スコープ種別
     * @param scopeId       スコープ ID
     * @param submissionId  提出 ID
     * @param userId        操作ユーザー ID
     * @param request       アップロードメタ情報
     * @return Pre-signed PUT URL + 確定 R2/S3 キー + 有効期限秒
     * @throws BusinessException SUBMISSION_NOT_FOUND / EDIT_AFTER_SUBMIT_NOT_ALLOWED
     *                           / UPLOAD_CONTENT_TYPE_INVALID / UPLOAD_SIZE_EXCEEDED
     */
    public FormUploadUrlResponse presignUploadUrl(
            String scopeType, Long scopeId, Long submissionId, Long userId,
            FormUploadUrlRequest request) {
        FormSubmissionEntity entity = submissionRepository.findByIdAndSubmittedBy(submissionId, userId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.SUBMISSION_NOT_FOUND));

        // 編集可能ステータス（DRAFT / RETURNED）でのみ添付追加を許可する
        if (!entity.isEditable()) {
            throw new BusinessException(FormErrorCode.EDIT_AFTER_SUBMIT_NOT_ALLOWED);
        }

        boolean isSignature = request.getFieldKey() != null
                && request.getFieldKey().toLowerCase(Locale.ROOT).contains(SIGNATURE_FIELD_KEY_HINT);
        long maxBytes = isSignature ? SIGNATURE_MAX_BYTES : FILE_MAX_BYTES;
        Set<String> allowedTypes = isSignature ? ALLOWED_SIGNATURE_CONTENT_TYPES : ALLOWED_FILE_CONTENT_TYPES;

        String normalizedType = request.getContentType() == null
                ? "" : request.getContentType().toLowerCase(Locale.ROOT);
        if (!allowedTypes.contains(normalizedType)) {
            log.info("フォーム upload-url 拒否（MIME 不許可）: submissionId={}, contentType={}, isSignature={}",
                    submissionId, request.getContentType(), isSignature);
            throw new BusinessException(FormErrorCode.UPLOAD_CONTENT_TYPE_INVALID);
        }

        if (request.getFileSize() > maxBytes) {
            log.info("フォーム upload-url 拒否（サイズ超過）: submissionId={}, fileSize={}, max={}",
                    submissionId, request.getFileSize(), maxBytes);
            throw new BusinessException(FormErrorCode.UPLOAD_SIZE_EXCEEDED);
        }

        String safeName = sanitizeFileName(request.getFileName());
        String fileKey = String.format(
                "forms/%s/%d/submissions/%d/%s/%s/%s",
                scopeType, scopeId, submissionId,
                isSignature ? "signatures" : "attachments",
                UUID.randomUUID(), safeName);

        PresignedUploadResult result = storageService.generateUploadUrl(
                fileKey, normalizedType, UPLOAD_URL_TTL);
        log.info("フォーム upload-url 発行: submissionId={}, userId={}, fileKey={}",
                submissionId, userId, fileKey);
        return new FormUploadUrlResponse(result.uploadUrl(), result.s3Key(), result.expiresInSeconds());
    }

    /**
     * R2/S3 オブジェクトキー安全化（スラッシュ・空白・制御文字を除去）。
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "file";
        }
        String s = fileName.replace('\\', '/');
        int idx = s.lastIndexOf('/');
        if (idx >= 0) s = s.substring(idx + 1);
        // 安全な文字以外は _ に置換
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * 提出値を一括保存する。
     */
    private List<FormSubmissionValueEntity> saveValues(
            Long submissionId, List<SubmissionValueRequest> values) {
        List<FormSubmissionValueEntity> entities = values.stream()
                .map(v -> (FormSubmissionValueEntity) FormSubmissionValueEntity.builder()
                        .submissionId(submissionId)
                        .fieldKey(v.getFieldKey())
                        .fieldType(FormFieldType.valueOf(v.getFieldType()))
                        .textValue(v.getTextValue())
                        .numberValue(v.getNumberValue())
                        .dateValue(v.getDateValue())
                        .fileKey(v.getFileKey())
                        .isAutoFilled(v.getIsAutoFilled() != null ? v.getIsAutoFilled() : false)
                        .build())
                .toList();
        return valueRepository.saveAll(entities);
    }
}
