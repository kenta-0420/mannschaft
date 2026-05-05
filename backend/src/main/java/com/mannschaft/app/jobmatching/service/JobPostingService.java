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
import java.util.Comparator;
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

        // toBuilder で新インスタンスを構築し、null でないフィールドのみ差し替え。
        JobPostingEntity updated = posting.toBuilder()
                .title(cmd.title() != null ? cmd.title() : posting.getTitle())
                .description(cmd.description() != null ? cmd.description() : posting.getDescription())
                .category(cmd.category() != null ? cmd.category() : posting.getCategory())
                .workLocationType(cmd.workLocationType() != null
                        ? cmd.workLocationType() : posting.getWorkLocationType())
                .workAddress(cmd.workAddress() != null ? cmd.workAddress() : posting.getWorkAddress())
                .workStartAt(cmd.workStartAt() != null ? cmd.workStartAt() : posting.getWorkStartAt())
                .workEndAt(cmd.workEndAt() != null ? cmd.workEndAt() : posting.getWorkEndAt())
                .rewardType(cmd.rewardType() != null ? cmd.rewardType() : posting.getRewardType())
                .baseRewardJpy(cmd.baseRewardJpy() != null ? cmd.baseRewardJpy() : posting.getBaseRewardJpy())
                .capacity(cmd.capacity() != null ? cmd.capacity() : posting.getCapacity())
                .applicationDeadlineAt(cmd.applicationDeadlineAt() != null
                        ? cmd.applicationDeadlineAt() : posting.getApplicationDeadlineAt())
                .visibilityScope(cmd.visibilityScope() != null
                        ? cmd.visibilityScope() : posting.getVisibilityScope())
                .publishAt(cmd.publishAt() != null ? cmd.publishAt() : posting.getPublishAt())
                .build();

        JobPostingEntity saved = postingRepository.save(updated);
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
     * 求人 ID で取得する。見つからない場合は {@code JOB_NOT_FOUND} を送出する。
     */
    public JobPostingEntity findById(Long postingId) {
        return findOrThrow(postingId);
    }

    /**
     * チーム配下の求人一覧をページング取得する。status が null の場合は全ステータス対象。
     *
     * <p><strong>注意</strong>: 本メソッドは可視性フィルタリングを行わない。viewer 視点で
     * 閲覧可能な求人のみに絞り込みたい場合は
     * {@link #listByTeamForViewer(Long, JobPostingStatus, Long, Pageable)} を使うこと。
     * Phase E で本メソッドの呼び出し点をすべて Resolver 経由に切り替えた後、削除予定。</p>
     */
    public Page<JobPostingEntity> listByTeam(Long teamId, JobPostingStatus status, Pageable pageable) {
        if (status == null) {
            return postingRepository.findByTeamId(teamId, pageable);
        }
        return postingRepository.findByTeamIdAndStatus(teamId, status, pageable);
    }

    /**
     * チーム配下の求人一覧を viewer 視点でフィルタしてページング取得する。
     *
     * <p>F00 Phase C 試験的置換: {@link ContentVisibilityChecker#filterAccessible} で
     * viewer に閲覧可能な ID のみに絞り込む。ページング統計（totalElements/totalPages）は
     * フィルタ後の数で再計算する（過剰開示を避けるため、生の DB 件数は出さない）。</p>
     *
     * <p>F00 設計書 §10.3 の移行パスに準拠。{@code AccessControlService} 直叩きの判定や
     * Service 内部の {@code isVisibleTo()} と機能横断的に等価な可視性判断を共通基盤に集約する。</p>
     *
     * @param teamId       対象チーム ID
     * @param status       絞り込み status（{@code null} で全ステータス）
     * @param viewerUserId 閲覧者 user_id（{@code null} 可、未認証）
     * @param pageable     ページング指定
     * @return viewer に閲覧可能な求人のページ（フィルタ後の集計）
     */
    public Page<JobPostingEntity> listByTeamForViewer(
            Long teamId, JobPostingStatus status, Long viewerUserId, Pageable pageable) {
        Page<JobPostingEntity> raw = listByTeam(teamId, status, pageable);
        if (raw.isEmpty()) {
            return raw;
        }
        List<JobPostingEntity> rawContent = raw.getContent();
        List<Long> ids = rawContent.stream().map(JobPostingEntity::getId).toList();
        Set<Long> accessibleIds = visibilityChecker.filterAccessible(
                ReferenceType.JOB_POSTING, ids, viewerUserId);
        // 入力の順序を維持しつつフィルタ
        List<JobPostingEntity> filtered = rawContent.stream()
                .filter(e -> accessibleIds.contains(e.getId()))
                .sorted(Comparator.comparingInt(e -> ids.indexOf(e.getId())))
                .toList();
        return new PageImpl<>(filtered, pageable, filtered.size());
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
