package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.ActionMemoMetrics;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.actionmemo.dto.PublishDailyRequest;
import com.mannschaft.app.actionmemo.dto.PublishDailyResponse;
import com.mannschaft.app.actionmemo.dto.PublishDailyToTeamRequest;
import com.mannschaft.app.actionmemo.dto.PublishDailyToTeamResponse;
import com.mannschaft.app.actionmemo.dto.PublishToTeamRequest;
import com.mannschaft.app.actionmemo.dto.PublishToTeamResponse;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.security.HtmlSanitizer;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F02.5 行動メモ投稿サービス。
 *
 * <p>タイムライン投稿系（publishDaily / publishToTeam / publishDailyToTeam）を担当する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActionMemoPublishingService {

    /** publish-daily 本文日付ヘッダー */
    private static final DateTimeFormatter MEMO_DATE_HEADER_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** publish-daily 本文各行の時刻フォーマット（HH:MM） */
    private static final DateTimeFormatter MEMO_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm");

    /** JST タイムゾーン */
    private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");

    private final ActionMemoRepository memoRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final UserRoleRepository userRoleRepository;
    private final ActionMemoSettingsService settingsService;
    private final ActionMemoMetrics metrics;
    private final TodoRepository todoRepository;

    /**
     * 当日分（または指定日分）のメモをまとめて PERSONAL タイムラインに投稿する。
     *
     * <p>設計書 §4 §5 §5.4 に従い以下の処理を行う:</p>
     * <ol>
     *   <li>{@code memo_date} 省略時は JST の今日に自動セット</li>
     *   <li>対象日のメモを時系列順に取得（{@code @SQLRestriction} により論理削除済みは除外）</li>
     *   <li>0件なら {@link ActionMemoErrorCode#ACTION_MEMO_NO_MEMOS_FOR_DATE}（400）</li>
     *   <li><b>冪等性</b>: 既に {@code timeline_post_id} が埋まっているメモが存在する場合は
     *       対応する {@link TimelinePostEntity} を論理削除し、新規投稿で差し替える
     *       （設計書 §5 重要な判定ロジック「上書き再投稿」）</li>
     *   <li>本文を {@code ## YYYY-MM-DD の行動ログ} ヘッダー + {@code - HH:MM content} の
     *       リスト形式で組み立てる。{@code mood_enabled = true} のユーザーは各行頭に絵文字付与</li>
     *   <li>{@code extra_comment} が指定されていれば末尾に {@code \n\n---\n} 区切りで追記する。
     *       XSS 対策として {@link HtmlSanitizer#sanitizePlainText(String)} を通す</li>
     *   <li>{@code scope_type=PERSONAL, scope_id=userId, user_id=userId} で
     *       {@link TimelinePostEntity} を INSERT</li>
     *   <li>各 {@link ActionMemoEntity#setTimelinePostId(Long)} を新 ID で更新</li>
     * </ol>
     *
     * <p><b>将来仕様変更の留意点</b>: 旧投稿への返信は PERSONAL スコープのため理論上発生しないが、
     * 将来スコープ仕様が変わった場合は「旧投稿を論理削除したときに孤立するリプライ」への
     * 対策を別途設計する必要がある（設計書 §5）。</p>
     *
     * <p><b>ログ方針</b>: 設計書 §6 運用・監視に従い、本文そのもの（content）は出力しない。
     * {@code timelinePostId / memoCount / userId / memoDate} のみ INFO で記録する。</p>
     */
    // TODO: actionmemoドメインとtimelineドメイン(TimelinePostRepository)をまたいでいる。将来はActionMemoPublishedEvent(PERSONAL)で分離予定
    @Transactional
    public PublishDailyResponse publishDaily(PublishDailyRequest request, Long userId) {
        try {
            // 1. memo_date デフォルト設定（JST 今日）
            LocalDate memoDate = request.getMemoDate() != null
                    ? request.getMemoDate()
                    : LocalDate.now(ZONE_JST);

            // 2. 対象日のメモを時系列順に取得
            List<ActionMemoEntity> memos = memoRepository.findByUserIdAndMemoDate(userId, memoDate);

            // 3. 0件チェック → 400
            if (memos.isEmpty()) {
                throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_NO_MEMOS_FOR_DATE);
            }

            // 4. 冪等性: 既存投稿の論理削除（同日に publish-daily が呼ばれた場合の上書き再投稿）
            Set<Long> oldTimelinePostIds = memos.stream()
                    .map(ActionMemoEntity::getTimelinePostId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            for (Long oldPostId : oldTimelinePostIds) {
                timelinePostRepository.findById(oldPostId).ifPresent(old -> {
                    old.softDelete();
                    timelinePostRepository.save(old);
                });
            }

            // 5. mood 表示可否（ユーザー設定）
            boolean moodEnabled = settingsService.getMoodEnabled(userId);

            // 6. 本文組み立て
            String content = buildPublishDailyContent(
                    memoDate, memos, moodEnabled, request.getExtraComment());

            // 7. TimelinePost 新規作成（PERSONAL スコープ）
            TimelinePostEntity post = TimelinePostEntity.builder()
                    .scopeType(PostScopeType.PERSONAL)
                    .scopeId(userId)
                    .userId(userId)
                    .content(content)
                    .build();
            TimelinePostEntity savedPost = timelinePostRepository.save(post);

            // 8. 各メモの timelinePostId を更新
            for (ActionMemoEntity memo : memos) {
                memo.setTimelinePostId(savedPost.getId());
                memoRepository.save(memo);
            }

            // 9. メトリクス + ログ（content 本文は出力しない）
            metrics.incrementPublishDailySuccess();
            log.info("行動メモ 終業投稿成功: timelinePostId={}, memoCount={}, userId={}, memoDate={}",
                    savedPost.getId(), memos.size(), userId, memoDate);

            return PublishDailyResponse.builder()
                    .timelinePostId(savedPost.getId())
                    .memoCount(memos.size())
                    .memoDate(memoDate)
                    .build();
        } catch (BusinessException ex) {
            metrics.incrementPublishDailyError();
            throw ex;
        } catch (RuntimeException ex) {
            metrics.incrementPublishDailyError();
            throw ex;
        }
    }

    /**
     * メモ1件をチームタイムラインに投稿する。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>メモ所有者検証</li>
     *   <li>カテゴリ検証（WORK のみ）</li>
     *   <li>既投稿チェック</li>
     *   <li>team_id 解決（リクエスト → settings.defaultPostTeamId → 400）</li>
     *   <li>チームメンバーシップ検証</li>
     *   <li>本文フォーマット生成 + タイムライン投稿</li>
     *   <li>memo.postedTeamId / timelinePostId 更新</li>
     * </ol>
     *
     * @param memoId  投稿対象メモ ID
     * @param request 投稿リクエスト
     * @param userId  現在のユーザー ID
     * @return 投稿レスポンス
     */
    // TODO: actionmemoドメインがtimelineドメイン(TimelinePostRepository)・roleドメイン(UserRoleRepository)をまたいでいる。将来はActionMemoPublishedEvent(TEAM)で分離予定
    @Transactional
    public PublishToTeamResponse publishToTeam(Long memoId, PublishToTeamRequest request, Long userId) {
        // 1. メモ所有者検証
        ActionMemoEntity memo = memoRepository.findByIdAndUserId(memoId, userId)
                .orElseThrow(() -> new BusinessException(ActionMemoErrorCode.ACTION_MEMO_NOT_FOUND));

        // 2. カテゴリ検証（WORK のみ）
        if (memo.getCategory() != ActionMemoCategory.WORK) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_ONLY_WORK_CAN_BE_POSTED);
        }

        // 3. 既投稿チェック
        if (memo.getPostedTeamId() != null) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_ALREADY_POSTED);
        }

        // 4. team_id 解決（リクエスト → settings.defaultPostTeamId → 400）
        Long teamId = resolveTeamId(request.getTeamId(), userId);

        // 5. チームメンバーシップ検証（IDOR 対策: 非メンバーは 404）
        if (!userRoleRepository.existsByUserIdAndTeamId(userId, teamId)) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TEAM_NOT_FOUND);
        }

        // 6. 本文フォーマット生成
        String content = buildPublishToTeamContent(memo, request.getExtraComment());

        // 7. タイムライン投稿（scope_type=TEAM）
        TimelinePostEntity post = TimelinePostEntity.builder()
                .scopeType(PostScopeType.TEAM)
                .scopeId(teamId)
                .userId(userId)
                .content(content)
                .build();
        TimelinePostEntity savedPost = timelinePostRepository.save(post);

        // 8. memo.postedTeamId / timelinePostId 更新
        memo.setPostedTeamId(teamId);
        memo.setTimelinePostId(savedPost.getId());
        memoRepository.save(memo);

        log.info("行動メモ チーム投稿成功: memoId={}, teamId={}, timelinePostId={}",
                memoId, teamId, savedPost.getId());

        return PublishToTeamResponse.builder()
                .timelinePostId(savedPost.getId())
                .teamId(teamId)
                .memoId(memoId)
                .build();
    }

    /**
     * 当日の WORK メモをまとめてチームタイムラインに投稿する（日次まとめ投稿）。
     *
     * <p>重複投稿防止: postedTeamId が null のメモのみ対象。</p>
     *
     * @param request 投稿リクエスト
     * @param userId  現在のユーザー ID
     * @return 投稿レスポンス
     */
    // TODO: actionmemoドメインがroleドメイン(UserRoleRepository)・timelineドメイン(TimelinePostRepository)をまたいでいる。将来はイベント駆動で分離予定
    @Transactional
    public PublishDailyToTeamResponse publishDailyToTeam(PublishDailyToTeamRequest request, Long userId) {
        LocalDate today = LocalDate.now(ZONE_JST);

        // team_id 解決
        Long teamId = resolveTeamId(request.getTeamId(), userId);

        // チームメンバーシップ検証
        if (!userRoleRepository.existsByUserIdAndTeamId(userId, teamId)) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TEAM_NOT_FOUND);
        }

        // 当日の WORK かつ未投稿のメモを取得
        List<ActionMemoEntity> workMemos = memoRepository
                .findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull(
                        userId, today, ActionMemoCategory.WORK);

        if (workMemos.isEmpty()) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_NO_WORK_MEMO_TODAY);
        }

        // 各メモを個別にチーム投稿
        int postedCount = 0;
        for (ActionMemoEntity memo : workMemos) {
            PublishToTeamRequest individualRequest = new PublishToTeamRequest(teamId, null);
            publishToTeam(memo.getId(), individualRequest, userId);
            postedCount++;
        }

        log.info("行動メモ 日次チームまとめ投稿: teamId={}, postedCount={}, userId={}, memoDate={}",
                teamId, postedCount, userId, today);

        return PublishDailyToTeamResponse.builder()
                .teamId(teamId)
                .postedCount(postedCount)
                .build();
    }

    /**
     * publish-daily の本文を組み立てる。
     *
     * <pre>
     * ## 2026-04-09 の行動ログ
     *
     * - 09:15 朝散歩 30分
     * - 🙂 10:42 会議の準備完了
     * - ...
     *
     * ---
     * 今日はよく動けた。明日も頑張る
     * </pre>
     *
     * @param memoDate     対象日
     * @param memos        時系列順の当日メモ（非空）
     * @param moodEnabled  mood 表示可否
     * @param extraComment 末尾追記コメント（null/空なら追記なし。タグ類は HtmlSanitizer で除去）
     */
    private String buildPublishDailyContent(
            LocalDate memoDate,
            List<ActionMemoEntity> memos,
            boolean moodEnabled,
            String extraComment) {

        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(memoDate.format(MEMO_DATE_HEADER_FORMATTER))
                .append(" の行動ログ\n\n");

        for (ActionMemoEntity memo : memos) {
            sb.append("- ");
            if (moodEnabled && memo.getMood() != null) {
                sb.append(moodEmoji(memo.getMood())).append(" ");
            }
            String hhmm = memo.getCreatedAt() != null
                    ? memo.getCreatedAt().format(MEMO_TIME_FORMATTER)
                    : "";
            if (!hhmm.isEmpty()) {
                sb.append(hhmm).append(" ");
            }
            sb.append(memo.getContent()).append("\n");
        }

        if (extraComment != null && !extraComment.isBlank()) {
            String sanitized = HtmlSanitizer.sanitizePlainText(extraComment);
            sb.append("\n---\n").append(sanitized);
        }

        return sb.toString();
    }

    /**
     * publish-to-team 本文を組み立てる。
     *
     * <pre>
     * [HH:MM] {content}
     * ⏱️ {duration_minutes}分 / 📊 進捗 {progress_rate}%
     * 🔗 関連TODO: {todo.title}
     *
     * ---
     * {extra_comment}
     * </pre>
     */
    private String buildPublishToTeamContent(ActionMemoEntity memo, String extraComment) {
        StringBuilder sb = new StringBuilder();

        // [HH:MM] {content}
        if (memo.getCreatedAt() != null) {
            String hhmm = memo.getCreatedAt().format(MEMO_TIME_FORMATTER);
            sb.append("[").append(hhmm).append("] ");
        }
        sb.append(memo.getContent()).append("\n");

        // ⏱️ {duration_minutes}分 / 📊 進捗 {progress_rate}%
        boolean hasStats = memo.getDurationMinutes() != null || memo.getProgressRate() != null;
        if (hasStats) {
            if (memo.getDurationMinutes() != null) {
                sb.append("⏱️ ").append(memo.getDurationMinutes()).append("分");
            }
            if (memo.getProgressRate() != null) {
                if (memo.getDurationMinutes() != null) {
                    sb.append(" / ");
                }
                sb.append("📊 進捗 ").append(memo.getProgressRate().stripTrailingZeros().toPlainString()).append("%");
            }
            sb.append("\n");
        }

        // 🔗 関連TODO
        if (memo.getRelatedTodoId() != null) {
            todoRepository.findByIdAndDeletedAtIsNull(memo.getRelatedTodoId()).ifPresent(todo ->
                    sb.append("🔗 関連TODO: ").append(todo.getTitle()).append("\n")
            );
        }

        // extra_comment
        if (extraComment != null && !extraComment.isBlank()) {
            String sanitized = HtmlSanitizer.sanitizePlainText(extraComment);
            sb.append("\n---\n").append(sanitized);
        }

        return sb.toString();
    }

    /**
     * team_id を解決する。
     * リクエストの team_id → settings.defaultPostTeamId → 400 の順で解決。
     */
    private Long resolveTeamId(Long requestTeamId, Long userId) {
        if (requestTeamId != null) {
            return requestTeamId;
        }
        Long defaultTeamId = settingsService.findSettings(userId)
                .map(UserActionMemoSettingsEntity::getDefaultPostTeamId)
                .orElse(null);
        if (defaultTeamId == null) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TEAM_ID_REQUIRED);
        }
        return defaultTeamId;
    }

    /**
     * mood から絵文字を解決する。
     */
    private String moodEmoji(ActionMemoMood mood) {
        return switch (mood) {
            case GREAT -> "😄";
            case GOOD -> "🙂";
            case OK -> "😐";
            case TIRED -> "😩";
            case BAD -> "😞";
        };
    }
}
