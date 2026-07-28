package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityStatus;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.dto.ActivityParticipantResponse;
import com.mannschaft.app.activity.dto.AddParticipantsRequest;
import com.mannschaft.app.activity.dto.CreateActivityRequest;
import com.mannschaft.app.activity.dto.CreateDraftActivityRequest;
import com.mannschaft.app.activity.dto.DuplicateActivityRequest;
import com.mannschaft.app.activity.dto.RemoveParticipantsRequest;
import com.mannschaft.app.activity.dto.UpdateActivityRequest;
import com.mannschaft.app.activity.entity.ActivityParticipantEntity;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.repository.ActivityParticipantRepository;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 活動記録サービス。活動記録のCRUD・参加者管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityResultService {

    private final ActivityResultRepository resultRepository;
    private final ActivityParticipantRepository participantRepository;
    private final ActivityTemplateService templateService;
    private final ActivityMapper activityMapper;
    private final ObjectMapper objectMapper;
    private final ContentVisibilityChecker contentVisibilityChecker;
    private final ActivityScopeAccessGuard scopeAccessGuard;

    /**
     * 活動記録一覧をページング取得する。
     */
    public Page<ActivityResultEntity> listActivities(Long userId, ActivityScopeType scopeType, Long scopeId,
                                                      Long templateId, Pageable pageable) {
        // スコープメンバーシップ検証: 非メンバーは403
        scopeAccessGuard.checkMembership(userId, scopeType, scopeId);
        Page<ActivityResultEntity> page = templateId != null
                ? resultRepository.findByScopeTypeAndScopeIdAndTemplateIdOrderByActivityDateDescIdDesc(
                        scopeType, scopeId, templateId, pageable)
                : resultRepository.findByScopeTypeAndScopeIdOrderByActivityDateDescIdDesc(
                        scopeType, scopeId, pageable);

        // AC-10: DRAFT（下書き）は作成者・SystemAdmin のみ可視。
        // F00 ContentVisibilityChecker 経由で status × visibility を一元評価し、
        // 閲覧不可（他人の DRAFT 等）を一覧から除外する（可視性判定は F00 正準経由）。
        List<ActivityResultEntity> content = page.getContent();
        if (content.isEmpty()) {
            return page;
        }
        Set<Long> accessibleIds = contentVisibilityChecker.filterAccessible(
                ReferenceType.ACTIVITY_RESULT,
                content.stream().map(ActivityResultEntity::getId).collect(Collectors.toSet()),
                userId);
        List<ActivityResultEntity> filtered = content.stream()
                .filter(e -> accessibleIds.contains(e.getId()))
                .collect(Collectors.toList());
        return new PageImpl<>(filtered, pageable, filtered.size());
    }

    /**
     * 公開活動記録一覧をページング取得する。
     *
     * <p><b>二段構えの可視性フィルタ（多層防御）</b>:</p>
     * <ol>
     *   <li><b>SQL 前段</b>: {@code visibility=PUBLIC} かつ {@code status=PUBLISHED} だけを
     *       DB で絞り込む。ページング・総件数を <b>DB に正しく計算させる</b>ためであり、
     *       取得後にアプリ側で filter するだけの旧実装は総件数が壊れていた
     *       （{@code new PageImpl<>(filtered, pageable, filtered.size())} により総件数が
     *       ページ内件数へ化け、ページャが 1 ページしかないように見えていた。契約テスト AC-29）。</li>
     *   <li><b>F00 正準</b>: {@link ContentVisibilityChecker#filterAccessible(ReferenceType,
     *       java.util.Collection, Long)}（未認証 userId=null）で再評価する。
     *       可視性判定の単一真実源は引き続き F00 側であり、SQL 前段はその冗長化にすぎない。</li>
     * </ol>
     *
     * <p>F00 が SQL 前段より厳しく落とした件数分は総件数から差し引く（このページで落ちた件数を減算）。
     * 実運用では両者の判定が一致するため差し引きは 0 件になる。</p>
     *
     * <p><b>注意</b>: 本メソッドは親スコープ（チーム / 組織）の公開性を検証<b>しない</b>。
     * 匿名公開経路では {@code PublicActivityQueryService} が親スコープを先に検証すること。</p>
     */
    public Page<ActivityResultEntity> listPublicActivities(ActivityScopeType scopeType, Long scopeId,
                                                            Pageable pageable) {
        // (1) SQL 前段: PUBLIC かつ PUBLISHED のみ（ページング上限は呼び出し元が制御）
        Page<ActivityResultEntity> allPage = resultRepository
                .findByScopeTypeAndScopeIdAndVisibilityAndStatusOrderByActivityDateDescIdDesc(
                        scopeType, scopeId, ActivityVisibility.PUBLIC,
                        ActivityStatus.PUBLISHED, pageable);
        List<ActivityResultEntity> all = allPage.getContent();

        if (all.isEmpty()) {
            return allPage;
        }

        // (2) F00 ContentVisibilityChecker 経由で公開判定（userId=null = 未認証）。
        //     ID 集合を 1 回のバッチ呼び出しで判定するため件数に比例した SQL は発行されない（N+1 禁止）。
        Set<Long> accessibleIds = contentVisibilityChecker.filterAccessible(
                ReferenceType.ACTIVITY_RESULT,
                all.stream().map(ActivityResultEntity::getId).collect(Collectors.toSet()),
                null);

        List<ActivityResultEntity> filtered = all.stream()
                .filter(e -> accessibleIds.contains(e.getId()))
                .collect(Collectors.toList());

        if (filtered.size() == all.size()) {
            // F00 が追加で落としたものは無し → DB が算出した総件数をそのまま活かす
            return allPage;
        }
        long removedInThisPage = (long) all.size() - filtered.size();
        return new PageImpl<>(filtered, pageable,
                Math.max(0L, allPage.getTotalElements() - removedInThisPage));
    }

    /**
     * 公開用に活動記録詳細を取得する（認証不要・メンバーシップチェックなし）。
     * ActivityPublicController 等の公開エンドポイント専用。
     */
    public ActivityResultEntity getActivity(Long id) {
        return findActivityOrThrow(id);
    }

    /**
     * 活動記録詳細を取得する。
     */
    public ActivityResultEntity getActivity(Long id, Long userId) {
        ActivityResultEntity entity = findActivityOrThrow(id);
        // スコープメンバーシップ検証
        scopeAccessGuard.checkMembership(userId, entity.getScopeType(), entity.getScopeId());
        // AC-10: DRAFT（下書き）は作成者本人（または管理者以上）のみ閲覧可。
        // それ以外の会員には「存在しない」ものとして扱う（ACTIVITY_NOT_FOUND で漏洩防止）。
        if (entity.getStatus() == com.mannschaft.app.activity.ActivityStatus.DRAFT
                && !userId.equals(entity.getCreatedBy())) {
            boolean adminOrAbove = scopeAccessGuard.isAdminOrAbove(
                    userId, entity.getScopeType(), entity.getScopeId());
            if (!adminOrAbove) {
                throw new BusinessException(ActivityErrorCode.ACTIVITY_NOT_FOUND);
            }
        }
        return entity;
    }

    /**
     * 公開活動記録を ID で取得する（スコープ不問）。
     *
     * <p>F06.4 SNS シェア用。フロントエンドがスコープ（team/org）を意識せずに
     * ID 直引きで公開済みの記録を取得するために使用する。</p>
     *
     * <p><b>status 条件は必須</b>: 旧実装は {@code findByIdAndVisibility(id, PUBLIC)} のみで
     * status を見ておらず、{@code visibility=PUBLIC} のまま公開していない下書き
     * （{@code status=DRAFT}）が匿名で読めてしまっていた（契約テスト AC-11）。
     * 論理削除済みは {@code @SQLRestriction("deleted_at IS NULL")} が自動除外する。</p>
     *
     * <p><b>注意</b>: 本メソッドは親スコープ（チーム / 組織）の公開性を検証<b>しない</b>。
     * 匿名公開経路では {@code PublicActivityQueryService} 経由で使うこと。</p>
     *
     * @param id 活動記録 ID
     * @return visibility=PUBLIC かつ status=PUBLISHED の活動記録（該当なしは空）
     */
    public Optional<ActivityResultEntity> findPublicActivityById(Long id) {
        return resultRepository.findByIdAndVisibilityAndStatus(
                id, ActivityVisibility.PUBLIC, ActivityStatus.PUBLISHED);
    }

    /**
     * 活動記録を作成する。
     */
    @Transactional
    public ActivityResultEntity createActivity(Long userId, ActivityScopeType scopeType,
                                                Long scopeId, CreateActivityRequest request) {
        // スコープメンバーシップ検証: 非メンバーは403
        scopeAccessGuard.checkMembership(userId, scopeType, scopeId);
        // テンプレート存在チェック
        templateService.findTemplateOrThrow(request.getTemplateId());
        // 従来経路（createActivity）は作成即公開（status=PUBLISHED, Entity の @Builder.Default）

        // 時刻バリデーション
        if (request.getActivityTimeStart() != null && request.getActivityTimeEnd() != null
                && request.getActivityTimeEnd().isBefore(request.getActivityTimeStart())) {
            throw new BusinessException(ActivityErrorCode.INVALID_TIME_RANGE);
        }

        ActivityVisibility visibility = request.getVisibility() != null
                ? ActivityVisibility.valueOf(request.getVisibility()) : ActivityVisibility.MEMBERS_ONLY;

        String fieldValuesJson = serializeFieldValues(request.getFieldValues());
        String attachmentsJson = serializeAttachments(request.getFileIds());

        ActivityResultEntity entity = ActivityResultEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .templateId(request.getTemplateId())
                .title(request.getTitle())
                .activityDate(request.getActivityDate())
                .activityTimeStart(request.getActivityTimeStart())
                .activityTimeEnd(request.getActivityTimeEnd())
                .description(request.getDescription())
                .fieldValues(fieldValuesJson)
                .attachments(attachmentsJson)
                .visibility(visibility)
                .scheduleId(request.getScheduleId())
                .createdBy(userId)
                .build();

        ActivityResultEntity saved = resultRepository.save(entity);

        // 参加者の登録
        if (request.getParticipantUserIds() != null && !request.getParticipantUserIds().isEmpty()) {
            for (Long participantUserId : request.getParticipantUserIds()) {
                ActivityParticipantEntity participant = ActivityParticipantEntity.builder()
                        .activityResultId(saved.getId())
                        .userId(participantUserId)
                        .build();
                participantRepository.save(participant);
            }
        }

        log.info("活動記録作成: activityId={}, title={}", saved.getId(), saved.getTitle());
        return saved;
    }

    /**
     * 下書き（DRAFT）活動記録を作成する（F06.4 下書き対応）。
     *
     * <p>AC-8: title + activityDate のみの最小項目で作成できる。テンプレートは任意。
     * status は {@link com.mannschaft.app.activity.ActivityStatus#DRAFT}。DRAFT は
     * 作成者・SystemAdmin のみ閲覧可（F00 可視性で status=DRAFT が author 限定になる）。</p>
     */
    @Transactional
    public ActivityResultEntity createDraftActivity(Long userId, ActivityScopeType scopeType,
                                                    Long scopeId, CreateDraftActivityRequest request) {
        // スコープメンバーシップ検証: 非メンバーは403
        scopeAccessGuard.checkMembership(userId, scopeType, scopeId);
        // テンプレートは任意。指定された場合のみ存在チェック。
        if (request.getTemplateId() != null) {
            templateService.findTemplateOrThrow(request.getTemplateId());
        }

        // 時刻バリデーション
        if (request.getActivityTimeStart() != null && request.getActivityTimeEnd() != null
                && request.getActivityTimeEnd().isBefore(request.getActivityTimeStart())) {
            throw new BusinessException(ActivityErrorCode.INVALID_TIME_RANGE);
        }

        ActivityVisibility visibility = request.getVisibility() != null
                ? ActivityVisibility.valueOf(request.getVisibility()) : ActivityVisibility.MEMBERS_ONLY;

        ActivityResultEntity entity = ActivityResultEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .templateId(request.getTemplateId())
                .title(request.getTitle())
                .activityDate(request.getActivityDate())
                .activityTimeStart(request.getActivityTimeStart())
                .activityTimeEnd(request.getActivityTimeEnd())
                .description(request.getDescription())
                .fieldValues(serializeFieldValues(request.getFieldValues()))
                .visibility(visibility)
                .status(com.mannschaft.app.activity.ActivityStatus.DRAFT)
                .createdBy(userId)
                .build();

        ActivityResultEntity saved = resultRepository.save(entity);
        log.info("活動記録(下書き)作成: activityId={}, title={}", saved.getId(), saved.getTitle());
        return saved;
    }

    /**
     * 下書き活動記録を公開する（DRAFT → PUBLISHED）。
     *
     * <p>AC-9: publish EP で DRAFT→PUBLISHED。既に PUBLISHED のものを publish すると
     * {@link ActivityErrorCode#INVALID_ACTIVITY_STATUS}（400）。
     * 認可は作成者本人または管理者以上（update/delete と同一境界）。</p>
     */
    @Transactional
    public ActivityResultEntity publishActivity(Long id, Long userId) {
        ActivityResultEntity entity = findActivityOrThrow(id);
        // 本人または管理者のみ公開可能（update/delete と同一境界）
        scopeAccessGuard.checkAuthorOrAdmin(
                userId, entity.getCreatedBy(), entity.getScopeType(), entity.getScopeId());
        if (!entity.isPublishable()) {
            // 既に PUBLISHED（DRAFT 以外）の状態からの publish は不正
            throw new BusinessException(ActivityErrorCode.INVALID_ACTIVITY_STATUS);
        }
        entity.publish();
        ActivityResultEntity saved = resultRepository.save(entity);
        log.info("活動記録公開: activityId={}", id);
        return saved;
    }

    /**
     * 活動記録を更新する。
     */
    @Transactional
    public ActivityResultEntity updateActivity(Long id, Long userId, UpdateActivityRequest request) {
        ActivityResultEntity entity = findActivityOrThrow(id);
        // 本人または管理者のみ更新可能
        scopeAccessGuard.checkAuthorOrAdmin(
                userId, entity.getCreatedBy(), entity.getScopeType(), entity.getScopeId());

        // 時刻バリデーション
        if (request.getActivityTimeStart() != null && request.getActivityTimeEnd() != null
                && request.getActivityTimeEnd().isBefore(request.getActivityTimeStart())) {
            throw new BusinessException(ActivityErrorCode.INVALID_TIME_RANGE);
        }

        ActivityVisibility visibility = request.getVisibility() != null
                ? ActivityVisibility.valueOf(request.getVisibility()) : entity.getVisibility();

        String fieldValuesJson = serializeFieldValues(request.getFieldValues());
        String attachmentsJson = serializeAttachments(request.getFileIds());

        entity.update(request.getTitle(), request.getActivityDate(),
                request.getActivityTimeStart(), request.getActivityTimeEnd(),
                request.getDescription(), fieldValuesJson, attachmentsJson, visibility);

        ActivityResultEntity saved = resultRepository.save(entity);
        log.info("活動記録更新: activityId={}", id);
        return saved;
    }

    /**
     * 活動記録を論理削除する。
     */
    @Transactional
    public void deleteActivity(Long id, Long userId) {
        ActivityResultEntity entity = findActivityOrThrow(id);
        // 本人または管理者のみ削除可能
        scopeAccessGuard.checkAuthorOrAdmin(
                userId, entity.getCreatedBy(), entity.getScopeType(), entity.getScopeId());
        entity.softDelete();
        resultRepository.save(entity);
        log.info("活動記録削除: activityId={}", id);
    }

    /**
     * 活動記録を複製する。
     */
    @Transactional
    public ActivityResultEntity duplicateActivity(Long id, Long userId, DuplicateActivityRequest request) {
        ActivityResultEntity original = findActivityOrThrow(id);
        // スコープメンバーシップ検証: 非メンバーは403（他スコープ会員による複製=IDOR を封じる）
        scopeAccessGuard.checkMembership(userId, original.getScopeType(), original.getScopeId());

        String title = request != null && request.getTitle() != null
                ? request.getTitle() : original.getTitle();
        LocalDate activityDate = request != null && request.getActivityDate() != null
                ? request.getActivityDate() : LocalDate.now(TimezoneContextHolder.get());

        ActivityResultEntity copy = ActivityResultEntity.builder()
                .scopeType(original.getScopeType())
                .scopeId(original.getScopeId())
                .templateId(original.getTemplateId())
                .title(title)
                .activityDate(activityDate)
                .activityTimeStart(original.getActivityTimeStart())
                .activityTimeEnd(original.getActivityTimeEnd())
                .description(original.getDescription())
                .fieldValues(original.getFieldValues())
                .visibility(original.getVisibility())
                .createdBy(userId)
                .build();

        ActivityResultEntity saved = resultRepository.save(copy);

        // 参加者のコピー
        List<ActivityParticipantEntity> originalParticipants =
                participantRepository.findByActivityResultIdOrderByCreatedAtAsc(id);
        for (ActivityParticipantEntity p : originalParticipants) {
            ActivityParticipantEntity participantCopy = ActivityParticipantEntity.builder()
                    .activityResultId(saved.getId())
                    .userId(p.getUserId())
                    .roleLabel(p.getRoleLabel())
                    .build();
            participantRepository.save(participantCopy);
        }

        log.info("活動記録複製: originalId={}, newId={}", id, saved.getId());
        return saved;
    }

    /**
     * 参加者を追加する。
     */
    @Transactional
    public List<ActivityParticipantResponse> addParticipants(Long activityId, Long userId, AddParticipantsRequest request) {
        ActivityResultEntity activity = findActivityOrThrow(activityId);
        // スコープメンバーシップ検証: 非メンバーは403
        scopeAccessGuard.checkMembership(userId, activity.getScopeType(), activity.getScopeId());

        for (Long participantUserId : request.getUserIds()) {
            // 重複チェック
            if (participantRepository.findByActivityResultIdAndUserId(activityId, participantUserId).isPresent()) {
                continue;
            }

            String roleLabel = null;
            if (request.getRoleLabels() != null) {
                roleLabel = request.getRoleLabels().get(String.valueOf(participantUserId));
            }

            ActivityParticipantEntity participant = ActivityParticipantEntity.builder()
                    .activityResultId(activityId)
                    .userId(participantUserId)
                    .roleLabel(roleLabel)
                    .build();
            participantRepository.save(participant);
        }

        List<ActivityParticipantEntity> participants =
                participantRepository.findByActivityResultIdOrderByCreatedAtAsc(activityId);
        return activityMapper.toParticipantResponseList(participants);
    }

    /**
     * 参加者を削除する。
     */
    @Transactional
    public List<ActivityParticipantResponse> removeParticipants(Long activityId, Long userId, RemoveParticipantsRequest request) {
        ActivityResultEntity activity = findActivityOrThrow(activityId);
        // スコープメンバーシップ検証: 非メンバーは403
        scopeAccessGuard.checkMembership(userId, activity.getScopeType(), activity.getScopeId());
        participantRepository.deleteByActivityResultIdAndUserIdIn(activityId, request.getUserIds());
        log.info("参加者削除: activityId={}, count={}", activityId, request.getUserIds().size());

        List<ActivityParticipantEntity> participants =
                participantRepository.findByActivityResultIdOrderByCreatedAtAsc(activityId);
        return activityMapper.toParticipantResponseList(participants);
    }

    /**
     * 活動記録エンティティを取得する。存在しない場合は例外をスローする。
     */
    ActivityResultEntity findActivityOrThrow(Long id) {
        return resultRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.ACTIVITY_NOT_FOUND));
    }

    private String serializeFieldValues(Map<String, Object> fieldValues) {
        if (fieldValues == null || fieldValues.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(fieldValues);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("field_valuesのシリアライズに失敗しました", e);
        }
    }

    private String serializeAttachments(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(Map.of("file_ids", fileIds));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("attachmentsのシリアライズに失敗しました", e);
        }
    }
}
