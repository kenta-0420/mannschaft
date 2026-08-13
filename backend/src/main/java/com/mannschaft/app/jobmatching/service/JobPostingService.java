package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.policy.JobPolicy;
import com.mannschaft.app.jobmatching.repository.JobApplicationRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.jobmatching.service.command.CreateJobPostingCommand;
import com.mannschaft.app.jobmatching.service.command.UpdateJobPostingCommand;
import com.mannschaft.app.jobmatching.state.JobPostingStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 求人投稿サービス。F13.1 Phase 13.1.1 MVP。
 *
 * <p>求人の作成・公開・編集・募集終了・キャンセル・論理削除・検索の中核ロジックを担う。
 * 状態遷移は {@link JobPostingStateMachine}、認可は {@link JobPolicy} に委譲し、
 * 本クラスは業務ルールバリデーション（報酬範囲・公開範囲MVP制限・日時整合性など）に集中する。</p>
 *
 * <p>応募が 1 件でも存在する求人では、業務継続に影響を及ぼす重要属性
 * （報酬・業務開始/終了・応募締切・定員・公開範囲）の変更を禁止する。軽微な属性
 * （タイトル・説明文・カテゴリ・業務場所・住所）のみ変更を許容する。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class JobPostingService {

    /** MVP で許容する公開範囲。F01.7 カスタムテンプレート等は Phase 13.1.2 以降で解放。 */
    private static final Set<VisibilityScope> MVP_ALLOWED_SCOPES = EnumSet.of(
            VisibilityScope.TEAM_MEMBERS,
            VisibilityScope.TEAM_MEMBERS_SUPPORTERS
    );

    /** 報酬下限（円）。Entity CHECK 制約と同値だが、Service でも早期検証する。 */
    private static final int MIN_REWARD_JPY = 500;

    /** 報酬上限（円）。 */
    private static final int MAX_REWARD_JPY = 1_000_000;

    private final JobPostingRepository postingRepository;
    private final JobApplicationRepository applicationRepository;
    private final JobPostingStateMachine stateMachine;
    private final JobPolicy jobPolicy;
    /**
     * F00 共通可視性基盤の Checker。
     * Phase C 試験的置換: {@link #listByTeamForViewer(Long, JobPostingStatus, Long, Pageable)} で利用。
     */
    private final ContentVisibilityChecker visibilityChecker;
    /** CMP-028 Phase C: 求人一覧の可視レベル解決（SQL 述語化）のため。 */
    private final com.mannschaft.app.common.visibility.MembershipBatchQueryService membershipBatchQueryService;

    // ---------------------------------------------------------------------
    // コマンド系（更新）
    // ---------------------------------------------------------------------

    /**
     * 求人を DRAFT ステータスで新規作成する。
     *
     * <p>公開範囲は MVP 対応スコープのみ許容する。報酬額・日時整合性・publishAt の未来日チェックを行う。</p>
     *
     * @param cmd    作成コマンド
     * @param userId 操作ユーザーID
     * @return 保存された求人
     */
    @Transactional
    public JobPostingEntity create(CreateJobPostingCommand cmd, Long userId) {
        Objects.requireNonNull(cmd, "cmd は必須");
        Objects.requireNonNull(userId, "userId は必須");

        if (!jobPolicy.canCreatePosting(userId, cmd.teamId())) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }

        validateVisibilityScope(cmd.visibilityScope());
        validateReward(cmd.baseRewardJpy());
        validateWorkTimings(cmd.workStartAt(), cmd.workEndAt(), cmd.applicationDeadlineAt());
        validatePublishAt(cmd.publishAt());

        JobPostingEntity entity = JobPostingEntity.builder()
                .teamId(cmd.teamId())
                .createdByUserId(userId)
                .title(cmd.title())
                .description(cmd.description())
                .category(cmd.category())
                .workLocationType(cmd.workLocationType())
                .workAddress(cmd.workAddress())
                .workStartAt(cmd.workStartAt())
                .workEndAt(cmd.workEndAt())
                .rewardType(cmd.rewardType())
                .baseRewardJpy(cmd.baseRewardJpy())
                .capacity(cmd.capacity())
                .applicationDeadlineAt(cmd.applicationDeadlineAt())
                .visibilityScope(cmd.visibilityScope())
                .status(JobPostingStatus.DRAFT)
                .publishAt(cmd.publishAt())
                .build();

        JobPostingEntity saved = postingRepository.save(entity);
        log.info("求人作成: postingId={}, teamId={}, userId={}", saved.getId(), cmd.teamId(), userId);
        return saved;
    }

    /**
     * 求人を公開（DRAFT → OPEN）する。publishAt が指定されている場合は未来日時でなければならない。
     */
    @Transactional
    public JobPostingEntity publish(Long postingId, Long userId) {
        JobPostingEntity posting = findOrThrow(postingId);

        if (!jobPolicy.canEditPosting(userId, posting)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }

        stateMachine.validate(posting.getStatus(), JobPostingStatus.OPEN);

        LocalDateTime publishAt = posting.getPublishAt();
        if (publishAt != null && publishAt.isBefore(LocalDateTime.now())) {
            // 予約公開として登録されたが、公開指示時点で過去日になっていた場合は拒否。
            throw new BusinessException(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION);
        }

        posting.publish();
        JobPostingEntity saved = postingRepository.save(posting);
        log.info("求人公開: postingId={}, userId={}", postingId, userId);
        return saved;
    }

    /**
     * 求人情報を部分更新する。
     *
     * <p>応募者が 1 件でも存在する状態では、報酬・業務日時・応募締切・定員・公開範囲の変更を拒否する
     * （応募者に不利な条件変更を防止するため）。タイトル・説明など軽微属性のみ許容する。</p>
     */
    @Transactional
    public JobPostingEntity update(Long postingId, UpdateJobPostingCommand cmd, Long userId) {
        Objects.requireNonNull(cmd, "cmd は必須");
        JobPostingEntity posting = findOrThrow(postingId);

        if (!jobPolicy.canEditPosting(userId, posting)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }

        // CLOSED/CANCELLED は編集不可。
        if (posting.getStatus() == JobPostingStatus.CLOSED
                || posting.getStatus() == JobPostingStatus.CANCELLED) {
            throw new BusinessException(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION);
        }

        int applicantCount = applicationRepository.countByJobPostingId(postingId);
        boolean hasApplicants = applicantCount > 0;

        // 重要属性の変更可否判定。hasApplicants=true のときは値が posting 側と一致するか null でなければ拒否。
        if (hasApplicants) {
            rejectIfImmutableFieldChanged(posting.getBaseRewardJpy(), cmd.baseRewardJpy());
            rejectIfImmutableFieldChanged(posting.getWorkStartAt(), cmd.workStartAt());
            rejectIfImmutableFieldChanged(posting.getWorkEndAt(), cmd.workEndAt());
            rejectIfImmutableFieldChanged(posting.getApplicationDeadlineAt(), cmd.applicationDeadlineAt());
            rejectIfImmutableFieldChanged(posting.getCapacity(), cmd.capacity());
            rejectIfImmutableFieldChanged(posting.getVisibilityScope(), cmd.visibilityScope());
        }

        // 値の検証（指定されたフィールドのみ）。
        if (cmd.baseRewardJpy() != null) {
            validateReward(cmd.baseRewardJpy());
        }
        if (cmd.visibilityScope() != null) {
            validateVisibilityScope(cmd.visibilityScope());
        }
        LocalDateTime nextStart = cmd.workStartAt() != null ? cmd.workStartAt() : posting.getWorkStartAt();
        LocalDateTime nextEnd = cmd.workEndAt() != null ? cmd.workEndAt() : posting.getWorkEndAt();
        LocalDateTime nextDeadline = cmd.applicationDeadlineAt() != null
                ? cmd.applicationDeadlineAt() : posting.getApplicationDeadlineAt();
        validateWorkTimings(nextStart, nextEnd, nextDeadline);

        if (cmd.publishAt() != null) {
            validatePublishAt(cmd.publishAt());
        }

        // managed entity を直接ミューテートして null でないフィールドのみ差し替える。
        // id・version を保持するため save は UPDATE として永続化される
        // （旧実装の toBuilder().build() は BaseEntity 継承の id を引き継がず INSERT 化する不具合があった）。
        // 不変フィールドチェック（rejectIfImmutableFieldChanged）・日時整合性検証は上で旧値を読んで実施済み。
        posting.applyUpdate(
                cmd.title(), cmd.description(), cmd.category(),
                cmd.workLocationType(), cmd.workAddress(),
                cmd.workStartAt(), cmd.workEndAt(),
                cmd.rewardType(), cmd.baseRewardJpy(), cmd.capacity(),
                cmd.applicationDeadlineAt(), cmd.visibilityScope(),
                cmd.publishAt());

        JobPostingEntity saved = postingRepository.save(posting);
        log.info("求人更新: postingId={}, userId={}, applicantCount={}", postingId, userId, applicantCount);
        return saved;
    }

    /**
     * 求人を募集終了する（OPEN → CLOSED）。
     */
    @Transactional
    public JobPostingEntity close(Long postingId, Long userId) {
        JobPostingEntity posting = findOrThrow(postingId);
        if (!jobPolicy.canEditPosting(userId, posting)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }
        stateMachine.validate(posting.getStatus(), JobPostingStatus.CLOSED);
        posting.close();
        JobPostingEntity saved = postingRepository.save(posting);
        log.info("求人募集終了: postingId={}, userId={}", postingId, userId);
        return saved;
    }

    /**
     * 求人をキャンセルする（DRAFT または OPEN → CANCELLED）。
     */
    @Transactional
    public JobPostingEntity cancel(Long postingId, Long userId) {
        JobPostingEntity posting = findOrThrow(postingId);
        if (!jobPolicy.canEditPosting(userId, posting)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }
        stateMachine.validate(posting.getStatus(), JobPostingStatus.CANCELLED);
        posting.cancel();
        JobPostingEntity saved = postingRepository.save(posting);
        log.info("求人キャンセル: postingId={}, userId={}", postingId, userId);
        return saved;
    }

    /**
     * 求人を論理削除する。応募者がゼロ件の場合のみ許容する。
     */
    @Transactional
    public void delete(Long postingId, Long userId) {
        JobPostingEntity posting = findOrThrow(postingId);
        if (!jobPolicy.canEditPosting(userId, posting)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }
        int applicantCount = applicationRepository.countByJobPostingId(postingId);
        if (applicantCount > 0) {
            // 応募者がいる状態での論理削除は履歴を失うため禁止（CANCELLED 化で対応する）。
            throw new BusinessException(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION);
        }
        posting.softDelete();
        postingRepository.save(posting);
        log.info("求人論理削除: postingId={}, userId={}", postingId, userId);
    }

    // ---------------------------------------------------------------------
    // クエリ系
    // ---------------------------------------------------------------------

    /**
     * 求人 ID で取得する（viewer 視点の可視性チェック込み）。
     *
     * <p>BOLA対策: 一覧（{@link #listByTeamForViewer}）では {@link ContentVisibilityChecker}
     * によるフィルタリングを行っているが、詳細取得はこれまで存在確認のみで viewer の可視性を
     * 見ていなかった（他チームの DRAFT 求人等が id 直打ちで閲覧できてしまう欠陥）。
     * {@link ContentVisibilityChecker#assertCanView} で一覧と同じ可視性基盤を通す。</p>
     *
     * @param postingId    求人ID
     * @param viewerUserId 閲覧者ユーザーID（未認証は {@code null}）
     * @return 求人エンティティ
     */
    public JobPostingEntity findById(Long postingId, Long viewerUserId) {
        JobPostingEntity posting = findOrThrow(postingId);
        visibilityChecker.assertCanView(ReferenceType.JOB_POSTING, postingId, viewerUserId);
        return posting;
    }

    /**
     * チーム配下の求人一覧を viewer 視点でフィルタしてページング取得する。
     *
     * <h2>CMP-028 Phase C: SQL 述語化によるページング歯抜けの根治</h2>
     * <p>旧実装は SQL で 1 ページ分（{@code size=limit}）を取得してから
     * {@link ContentVisibilityChecker#filterAccessible} でメモリ上フィルタしており、
     * 非公開求人が混ざると要求件数ちょうどが返らない歯抜けが起きていた
     * （{@code ActivityResultService} と同種の欠陥）。
     * {@link com.mannschaft.app.common.visibility.MembershipBatchQueryService#resolveVisibleLevels}
     * が返す「行に依存せず判定できる可視 {@code StandardVisibility} ラダー集合」を
     * {@link com.mannschaft.app.common.visibility.mapping.JobMatchingVisibilityMapper#toFunctional}
     * で機能 enum へ逆写像し、SQL の {@code WHERE visibility_scope IN (...)} へ渡す。
     * 判定器は F00 のままであり、SQL はその出力の機械的な転写に過ぎない。</p>
     *
     * <p><b>{@code JOBBER_INTERNAL}（{@code StandardVisibility.CUSTOM}）の翻訳</b>:
     * {@code resolveVisibleLevels} は行依存の CUSTOM 軸をラダー集合に含めないため、
     * {@code JobPostingVisibilityResolver#evaluateCustom} と同一の判定
     * （viewer が対象求人のチームで {@code JOBBER} ロールを保有するか）を {@code EXISTS}
     * サブクエリとして OR で組み合わせる（{@link JobPostingRepository#findVisibleByTeamId} 参照）。</p>
     *
     * <p><b>{@code CUSTOM_TEMPLATE} は意図的に SQL 対象外（fail-closed・殿の判断待ち）</b>:
     * テンプレート評価は行ごとの動的判定が必要で SQL 述語に落とせない。現行 MVP では
     * {@link #MVP_ALLOWED_SCOPES} が {@code JOBBER_INTERNAL} / {@code CUSTOM_TEMPLATE} の
     * <b>書き込みを禁止</b>しており到達しないため、当面は SQL から除外する（将来これらの値の
     * 書き込みが解放された瞬間に静かに壊れないよう、{@code JobMatchingVisibilityMapper} 側で
     * ラダー集合に含めない設計にしている）。</p>
     *
     * <p><b>第二の門（保険）</b>: SQL が通した行を F00 {@link ContentVisibilityChecker} で
     * 再確認する。通常は 1 件も落ちない。乖離した場合は fail-closed で除外し {@code log.warn} に
     * 記録する（{@code ActivityResultService#listActivities} と同じ流儀）。</p>
     *
     * @param teamId       対象チーム ID
     * @param status       絞り込み status（{@code null} で全ステータス）
     * @param viewerUserId 閲覧者 user_id（{@code null} 可、未認証）
     * @param pageable     ページング指定
     * @return viewer に閲覧可能な求人のページ（総件数は DB 総件数ベースで正確値）
     */
    public Page<JobPostingEntity> listByTeamForViewer(
            Long teamId, JobPostingStatus status, Long viewerUserId, Pageable pageable) {
        com.mannschaft.app.common.visibility.ScopeKey scope =
                new com.mannschaft.app.common.visibility.ScopeKey("TEAM", teamId);
        com.mannschaft.app.common.visibility.UserScopeRoleSnapshot snapshot =
                membershipBatchQueryService.snapshotForUser(viewerUserId, Set.of(scope), Set.of(scope));
        Set<com.mannschaft.app.common.visibility.StandardVisibility> visibleLevels =
                membershipBatchQueryService.resolveVisibleLevels(scope, snapshot);
        Set<VisibilityScope> visibleScopes =
                com.mannschaft.app.common.visibility.mapping.JobMatchingVisibilityMapper
                        .toFunctional(visibleLevels);
        // StandardVisibility.PUBLIC は resolveVisibleLevels が常に含めるため
        // visibleScopes は非空（IN () の不正 SQL は起きない）。

        Page<JobPostingEntity> raw = postingRepository.findVisibleByTeamId(
                teamId, status, visibleScopes, viewerUserId, snapshot.isSystemAdmin(), pageable);

        List<JobPostingEntity> rawContent = raw.getContent();
        if (rawContent.isEmpty()) {
            return raw;
        }

        // 第二の門（保険）: SQL 述語と F00 の判定が食い違った場合を検知する。
        List<Long> ids = rawContent.stream().map(JobPostingEntity::getId).toList();
        Set<Long> accessibleIds = visibilityChecker.filterAccessible(
                ReferenceType.JOB_POSTING, ids, viewerUserId);
        if (accessibleIds.size() == rawContent.size()) {
            return raw;
        }
        List<Long> divergentIds = ids.stream().filter(id -> !accessibleIds.contains(id)).toList();
        log.warn("求人一覧: SQL 述語と F00 可視性判定が乖離しました（fail-closed で除外）。"
                        + "teamId={}, viewerUserId={}, divergentIds={}",
                teamId, viewerUserId, divergentIds);
        List<JobPostingEntity> filtered = rawContent.stream()
                .filter(e -> accessibleIds.contains(e.getId()))
                .toList();
        long totalElements = Math.max(0L, raw.getTotalElements() - divergentIds.size());
        return new PageImpl<>(filtered, pageable, totalElements);
    }

    /**
     * 自分が作成した求人一覧をページング取得する。
     */
    public Page<JobPostingEntity> listMyCreated(Long userId, Pageable pageable) {
        return postingRepository.findByCreatedByUserId(userId, pageable);
    }

    // ---------------------------------------------------------------------
    // 内部ヘルパー
    // ---------------------------------------------------------------------

    /**
     * 求人をIDで取得、見つからなければ {@code JOB_NOT_FOUND} を送出する。
     */
    private JobPostingEntity findOrThrow(Long postingId) {
        return postingRepository.findById(postingId)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_NOT_FOUND));
    }

    /**
     * MVP 対応の公開範囲かを検証する。
     */
    private void validateVisibilityScope(VisibilityScope scope) {
        if (scope == null || !MVP_ALLOWED_SCOPES.contains(scope)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_VIS_NOT_SUPPORTED);
        }
    }

    /**
     * 報酬額が許容範囲内か検証する。
     */
    private void validateReward(Integer rewardJpy) {
        if (rewardJpy == null || rewardJpy < MIN_REWARD_JPY || rewardJpy > MAX_REWARD_JPY) {
            throw new BusinessException(JobmatchingErrorCode.JOB_REWARD_OUT_OF_RANGE);
        }
    }

    /**
     * 業務開始・終了・応募締切の日時整合性を検証する。
     * <ul>
     *   <li>workEndAt は workStartAt より後</li>
     *   <li>applicationDeadlineAt は workStartAt 以前（同時刻までは許容）</li>
     * </ul>
     */
    private void validateWorkTimings(LocalDateTime workStartAt, LocalDateTime workEndAt,
                                     LocalDateTime applicationDeadlineAt) {
        if (workStartAt == null || workEndAt == null || applicationDeadlineAt == null) {
            return;
        }
        if (!workEndAt.isAfter(workStartAt)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION);
        }
        if (applicationDeadlineAt.isAfter(workStartAt)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_DEADLINE_PASSED);
        }
    }

    /**
     * publishAt が指定されている場合は未来日時でなければならない。
     */
    private void validatePublishAt(LocalDateTime publishAt) {
        if (publishAt != null && !publishAt.isAfter(LocalDateTime.now())) {
            throw new BusinessException(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION);
        }
    }

    /**
     * 「応募者がいる状態での重要属性変更」検知。current と incoming が両方非 null かつ不一致なら拒否する。
     * incoming が null の場合は「未指定＝変更無し」とみなし素通り。
     */
    private void rejectIfImmutableFieldChanged(Object current, Object incoming) {
        if (incoming == null) {
            return;
        }
        if (!Objects.equals(current, incoming)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION);
        }
    }
}
