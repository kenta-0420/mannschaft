package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.ActionMemoMetrics;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.actionmemo.dto.ActionMemoListResponse;
import com.mannschaft.app.actionmemo.dto.ActionMemoResponse;
import com.mannschaft.app.actionmemo.dto.ActionMemoTagSummary;
import com.mannschaft.app.actionmemo.dto.CreateActionMemoRequest;
import com.mannschaft.app.actionmemo.dto.LinkTodoRequest;
import com.mannschaft.app.actionmemo.dto.PublishDailyRequest;
import com.mannschaft.app.actionmemo.dto.PublishDailyResponse;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoRequest;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.entity.ActionMemoTagEntity;
import com.mannschaft.app.actionmemo.entity.ActionMemoTagLinkEntity;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagLinkRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.security.HtmlSanitizer;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F02.5 行動メモサービス。
 *
 * <p>設計書 §5 §6 に厳密に従い、以下を保証する:</p>
 * <ul>
 *   <li>{@code currentUser.id == memo.userId} の所有者一致検証（不一致は 404）</li>
 *   <li>{@code mood_enabled = false} ユーザーの mood を silent に NULL 化（400 を返さない）</li>
 *   <li>{@code related_todo_id} のスコープ整合性検証（PERSONAL かつ自分所有）</li>
 *   <li>1日 200 件上限</li>
 *   <li>未来日付のバリデーション</li>
 *   <li>{@code memo_date} 省略時は JST の今日に自動セット</li>
 *   <li>ログ出力時の content マスキング（INFO: memoId/userId/length のみ、ERROR: 先頭20文字+...）</li>
 * </ul>
 *
 * <p><b>Phase 1 スコープ外</b>: {@code publishDaily} メソッドは Phase 2 で実装する。
 * タグ系の作成・更新 API は Phase 4 で実装する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActionMemoService {

    /** 設計書 §3: 1日あたりのメモ数上限 */
    private static final int DAILY_MEMO_LIMIT = 200;

    /** 1ページあたりのデフォルト/最大件数 */
    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;

    /** ログ出力時の content マスキング上限（ERROR レベル用） */
    private static final int CONTENT_ERROR_LOG_MAX_LENGTH = 20;

    /** JST タイムゾーン（設計書 §3 memo_date の自動セット） */
    private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");

    private final ActionMemoRepository memoRepository;
    private final ActionMemoTagRepository tagRepository;
    private final ActionMemoTagLinkRepository tagLinkRepository;
    private final TodoRepository todoRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final ActionMemoSettingsService settingsService;
    private final ActionMemoMetrics metrics;

    // ==================================================================
    // 作成
    // ==================================================================

    /**
     * 行動メモを1件作成する。
     */
    @Transactional
    public ActionMemoResponse createMemo(CreateActionMemoRequest request, Long userId) {
        // 1. 本文の空チェック（@NotBlank に加えて Service 層でも保険）
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_CONTENT_EMPTY);
        }
        if (request.getContent().length() > 5000) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_CONTENT_TOO_LONG);
        }

        // 2. memo_date のデフォルト設定 + 未来日付バリデーション
        LocalDate today = LocalDate.now(ZONE_JST);
        LocalDate memoDate = request.getMemoDate() != null ? request.getMemoDate() : today;
        if (memoDate.isAfter(today)) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_FUTURE_DATE);
        }

        // 3. 1日 200 件上限チェック
        long dailyCount = memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(userId, memoDate);
        if (dailyCount >= DAILY_MEMO_LIMIT) {
            metrics.incrementDailyLimitExceeded();
            log.warn("行動メモ1日上限到達: userId={}, memoDate={}, currentCount={}",
                    userId, memoDate, dailyCount);
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_DAILY_LIMIT_EXCEEDED);
        }

        // 4. mood の silent ignore 処理
        ActionMemoMood mood = resolveMood(userId, request.getMood());

        // 5. related_todo_id のスコープ整合性検証
        if (request.getRelatedTodoId() != null) {
            validateTodoScope(request.getRelatedTodoId(), userId);
        }

        // 6. タグの所有権検証
        List<ActionMemoTagEntity> tagEntities = validateAndFetchTags(request.getTagIds(), userId);

        // 7. エンティティ保存
        ActionMemoEntity entity = ActionMemoEntity.builder()
                .userId(userId)
                .memoDate(memoDate)
                .content(request.getContent())
                .mood(mood)
                .relatedTodoId(request.getRelatedTodoId())
                .build();
        ActionMemoEntity saved = memoRepository.save(entity);

        // 8. タグ紐付け保存
        if (!tagEntities.isEmpty()) {
            for (ActionMemoTagEntity tag : tagEntities) {
                tagLinkRepository.save(ActionMemoTagLinkEntity.builder()
                        .memoId(saved.getId())
                        .tagId(tag.getId())
                        .build());
            }
        }

        // 9. メトリクス + ログ（content マスキング）
        metrics.incrementCreated();
        log.info("行動メモ作成: memoId={}, userId={}, memoDate={}, length={}",
                saved.getId(), userId, memoDate, saved.getContent().length());

        return toResponse(saved, tagEntities);
    }

    // ==================================================================
    // 取得
    // ==================================================================

    /**
     * 自分のメモ1件を取得する。他人のメモは 404。
     */
    public ActionMemoResponse getMemo(Long memoId, Long userId) {
        ActionMemoEntity memo = findOwnMemoOrThrow(memoId, userId);
        List<ActionMemoTagEntity> tags = fetchTagsForMemo(memoId);
        return toResponse(memo, tags);
    }

    /**
     * 自分のメモ一覧を取得する（クエリフィルタ + カーソルページネーション）。
     *
     * @param userId   現在のユーザー
     * @param date     単日指定（任意、from/to と排他）
     * @param from     期間開始（任意）
     * @param to       期間終了（任意）
     * @param tagId    タグフィルタ（任意。Phase 1 では未使用でも受け取る）
     * @param cursor   カーソル（任意、Long の id）
     * @param limit    取得件数
     */
    public ActionMemoListResponse listMemos(
            Long userId,
            LocalDate date,
            LocalDate from,
            LocalDate to,
            Long tagId,
            String cursor,
            Integer limit) {

        int effectiveLimit = normalizeLimit(limit);
        Long cursorId = parseCursor(cursor);
        // limit+1 件を取って次カーソル有無を判定する
        PageRequest pageable = PageRequest.of(0, effectiveLimit + 1);

        List<ActionMemoEntity> memos;
        if (date != null) {
            memos = memoRepository.findByUserIdAndDateWithCursor(userId, date, cursorId, pageable);
        } else if (from != null && to != null) {
            memos = memoRepository.findByUserIdAndDateRangeWithCursor(userId, from, to, cursorId, pageable);
        } else {
            memos = memoRepository.findByUserIdWithCursor(userId, cursorId, pageable);
        }

        // tagId フィルタ（Phase 1 では簡易実装: アプリ層で絞る）
        if (tagId != null && !memos.isEmpty()) {
            Set<Long> memosWithTag = tagLinkRepository.findByMemoIdIn(
                            memos.stream().map(ActionMemoEntity::getId).toList())
                    .stream()
                    .filter(l -> Objects.equals(l.getTagId(), tagId))
                    .map(ActionMemoTagLinkEntity::getMemoId)
                    .collect(Collectors.toSet());
            memos = memos.stream().filter(m -> memosWithTag.contains(m.getId())).toList();
        }

        // 次カーソル判定
        String nextCursor = null;
        if (memos.size() > effectiveLimit) {
            ActionMemoEntity last = memos.get(effectiveLimit - 1);
            nextCursor = String.valueOf(last.getId());
            memos = memos.subList(0, effectiveLimit);
        }

        // タグの一括取得（N+1 対策）
        List<Long> memoIds = memos.stream().map(ActionMemoEntity::getId).toList();
        Map<Long, List<ActionMemoTagEntity>> tagsByMemoId = fetchTagsForMemos(memoIds);

        List<ActionMemoResponse> data = memos.stream()
                .map(m -> toResponse(m, tagsByMemoId.getOrDefault(m.getId(), List.of())))
                .toList();

        return new ActionMemoListResponse(data, nextCursor);
    }

    // ==================================================================
    // 更新
    // ==================================================================

    /**
     * 自分のメモを更新する。他人のメモは 404。
     */
    @Transactional
    public ActionMemoResponse updateMemo(Long memoId, UpdateActionMemoRequest request, Long userId) {
        ActionMemoEntity memo = findOwnMemoOrThrow(memoId, userId);

        if (request.getContent() != null) {
            if (request.getContent().isBlank()) {
                throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_CONTENT_EMPTY);
            }
            if (request.getContent().length() > 5000) {
                throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_CONTENT_TOO_LONG);
            }
            memo.setContent(request.getContent());
        }

        if (request.getMemoDate() != null) {
            LocalDate today = LocalDate.now(ZONE_JST);
            if (request.getMemoDate().isAfter(today)) {
                throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_FUTURE_DATE);
            }
            memo.setMemoDate(request.getMemoDate());
        }

        if (request.getMood() != null) {
            memo.setMood(resolveMood(userId, request.getMood()));
        }

        if (request.getRelatedTodoId() != null) {
            validateTodoScope(request.getRelatedTodoId(), userId);
            memo.setRelatedTodoId(request.getRelatedTodoId());
        }

        // タグの差し替え（送信された場合のみ）
        if (request.getTagIds() != null) {
            List<ActionMemoTagEntity> tagEntities = validateAndFetchTags(request.getTagIds(), userId);
            // 既存リンクを削除して再作成（シンプルな全置換）
            List<ActionMemoTagLinkEntity> existing = tagLinkRepository.findByMemoId(memoId);
            tagLinkRepository.deleteAll(existing);
            for (ActionMemoTagEntity tag : tagEntities) {
                tagLinkRepository.save(ActionMemoTagLinkEntity.builder()
                        .memoId(memoId)
                        .tagId(tag.getId())
                        .build());
            }
        }

        ActionMemoEntity saved = memoRepository.save(memo);

        log.info("行動メモ更新: memoId={}, userId={}, length={}",
                saved.getId(), userId, saved.getContent().length());

        List<ActionMemoTagEntity> tags = fetchTagsForMemo(memoId);
        return toResponse(saved, tags);
    }

    // ==================================================================
    // 削除
    // ==================================================================

    /**
     * 自分のメモを論理削除する。他人のメモは 404。
     */
    @Transactional
    public void deleteMemo(Long memoId, Long userId) {
        ActionMemoEntity memo = findOwnMemoOrThrow(memoId, userId);
        memo.softDelete();
        memoRepository.save(memo);
        log.info("行動メモ削除: memoId={}, userId={}", memoId, userId);
    }

    // ==================================================================
    // TODO 紐付け
    // ==================================================================

    /**
     * 自分のメモに TODO を紐付ける。他人の TODO / スコープ違反は 404。
     */
    @Transactional
    public ActionMemoResponse linkTodo(Long memoId, LinkTodoRequest request, Long userId) {
        ActionMemoEntity memo = findOwnMemoOrThrow(memoId, userId);
        validateTodoScope(request.getTodoId(), userId);

        memo.setRelatedTodoId(request.getTodoId());
        ActionMemoEntity saved = memoRepository.save(memo);

        log.info("行動メモ TODO 紐付け: memoId={}, todoId={}, userId={}",
                memoId, request.getTodoId(), userId);

        List<ActionMemoTagEntity> tags = fetchTagsForMemo(memoId);
        return toResponse(saved, tags);
    }

    // ==================================================================
    // publishDaily（Phase 2 本実装）
    // ==================================================================

    /** publish-daily 本文日付ヘッダー */
    private static final DateTimeFormatter MEMO_DATE_HEADER_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** publish-daily 本文各行の時刻フォーマット（HH:MM） */
    private static final DateTimeFormatter MEMO_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm");

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
            // createdAt は JST に変換して HH:mm を取り出す
            String hhmm = memo.getCreatedAt() != null
                    ? memo.getCreatedAt().atZone(ZoneId.systemDefault())
                        .withZoneSameInstant(ZONE_JST)
                        .format(MEMO_TIME_FORMATTER)
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


    // ==================================================================
    // プライベートヘルパー
    // ==================================================================

    /**
     * 所有者一致検証付きのメモ取得。不一致・存在しない・論理削除済みは全て 404。
     */
    private ActionMemoEntity findOwnMemoOrThrow(Long memoId, Long userId) {
        return memoRepository.findByIdAndUserId(memoId, userId)
                .orElseThrow(() -> new BusinessException(ActionMemoErrorCode.ACTION_MEMO_NOT_FOUND));
    }

    /**
     * 設定 OFF のユーザーが mood を送ってきた場合に silent に NULL 化する。
     * 設定 ON の場合はそのまま通す（NULL も許容）。
     */
    private ActionMemoMood resolveMood(Long userId, ActionMemoMood requestedMood) {
        if (requestedMood == null) {
            return null;
        }
        boolean enabled = settingsService.getMoodEnabled(userId);
        return enabled ? requestedMood : null;
    }

    /**
     * 紐付け対象 TODO が自分の PERSONAL スコープであることを検証する。
     * 違反時は 404（IDOR 対策）。
     */
    private void validateTodoScope(Long todoId, Long userId) {
        Optional<TodoEntity> todoOpt = todoRepository.findByIdAndDeletedAtIsNull(todoId);
        if (todoOpt.isEmpty()) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TODO_NOT_FOUND);
        }
        TodoEntity todo = todoOpt.get();
        if (todo.getScopeType() != TodoScopeType.PERSONAL
                || !Objects.equals(todo.getScopeId(), userId)) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TODO_NOT_FOUND);
        }
    }

    /**
     * タグ ID リストの所有権を検証し、取得する。存在しない/他人のタグが含まれていたら 404。
     */
    private List<ActionMemoTagEntity> validateAndFetchTags(List<Long> tagIds, Long userId) {
        if (tagIds == null || tagIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> distinctIds = tagIds.stream().distinct().toList();
        List<ActionMemoTagEntity> tags = tagRepository.findByIdInAndUserId(distinctIds, userId);
        if (tags.size() != distinctIds.size()) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TAG_NOT_FOUND);
        }
        return tags;
    }

    /**
     * 1メモ分のタグ一覧を取得する。
     */
    private List<ActionMemoTagEntity> fetchTagsForMemo(Long memoId) {
        List<ActionMemoTagLinkEntity> links = tagLinkRepository.findByMemoId(memoId);
        if (links.isEmpty()) {
            return List.of();
        }
        List<Long> tagIds = links.stream().map(ActionMemoTagLinkEntity::getTagId).toList();
        return tagRepository.findAllById(tagIds);
    }

    /**
     * 一括: 複数メモ分のタグ一覧を一度に取得する（N+1 対策）。
     * 論理削除済みタグも含むため注意（@SQLRestriction で除外される実装ではあるが、
     * 本来は削除済みタグも表示する必要があるため findAllById を直接使わない選択肢もある）。
     */
    private Map<Long, List<ActionMemoTagEntity>> fetchTagsForMemos(List<Long> memoIds) {
        if (memoIds == null || memoIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ActionMemoTagLinkEntity> links = tagLinkRepository.findByMemoIdIn(memoIds);
        if (links.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> tagIdSet = links.stream().map(ActionMemoTagLinkEntity::getTagId).collect(Collectors.toSet());
        List<ActionMemoTagEntity> tags = tagRepository.findAllById(tagIdSet);
        Map<Long, ActionMemoTagEntity> tagById = tags.stream()
                .collect(Collectors.toMap(ActionMemoTagEntity::getId, t -> t));

        Map<Long, List<ActionMemoTagEntity>> result = new HashMap<>();
        for (ActionMemoTagLinkEntity link : links) {
            ActionMemoTagEntity tag = tagById.get(link.getTagId());
            if (tag != null) {
                result.computeIfAbsent(link.getMemoId(), k -> new ArrayList<>()).add(tag);
            }
        }
        return result;
    }

    /**
     * Entity → Response マッピング。
     */
    private ActionMemoResponse toResponse(ActionMemoEntity memo, List<ActionMemoTagEntity> tags) {
        List<ActionMemoTagSummary> tagSummaries = tags == null ? List.of()
                : tags.stream()
                        .map(t -> new ActionMemoTagSummary(t.getId(), t.getName(), t.getColor(), false))
                        .toList();

        return ActionMemoResponse.builder()
                .id(memo.getId())
                .memoDate(memo.getMemoDate())
                .content(memo.getContent())
                .mood(memo.getMood())
                .relatedTodoId(memo.getRelatedTodoId())
                .timelinePostId(memo.getTimelinePostId())
                .tags(tagSummaries)
                .createdAt(memo.getCreatedAt())
                .updatedAt(memo.getUpdatedAt())
                .build();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIST_LIMIT;
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * ERROR ログ用に content を先頭 {@value #CONTENT_ERROR_LOG_MAX_LENGTH} 文字 + "..." で打ち切る。
     * INFO 系では本ヘルパーを使わず length のみを出力する。
     */
    @SuppressWarnings("unused")
    private String maskContentForError(String content) {
        if (content == null) return "";
        if (content.length() <= CONTENT_ERROR_LOG_MAX_LENGTH) return content;
        return content.substring(0, CONTENT_ERROR_LOG_MAX_LENGTH) + "...";
    }
}
