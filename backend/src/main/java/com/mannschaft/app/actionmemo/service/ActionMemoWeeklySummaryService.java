package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoMetrics;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * F02.5 行動メモ 週次まとめブログ自動生成サービス（Phase 3）。
 *
 * <p>設計書 §5.5 に従い、毎週日曜 21:00 JST に起動し、過去7日間にメモを書いた
 * 各ユーザー向けの「週次ふりかえり」ブログ記事を {@code blog_posts} に
 * {@code visibility = PRIVATE} / {@code cross_post_to_timeline = false} で INSERT する。</p>
 *
 * <p><b>実装方針</b>: {@code com.mannschaft.app.errorreport.service.ErrorReportWeeklySummaryService}
 * を雛形としてコピー改変。AI は使わず、純粋な統計とテンプレート埋め込みのみ（コストゼロ・常時 ON）。</p>
 *
 * <p><b>運用ポリシー</b>:</p>
 * <ul>
 *   <li>個別ユーザーの生成中に例外が発生しても、そのユーザーをスキップして次のユーザーに進む
 *       （1人の失敗で全体が止まらない）</li>
 *   <li>catch-up 機構なし: 実行時刻にサーバーが落ちていた場合はその週のまとめは生成されない。
 *       SYSTEM_ADMIN が {@link #regenerateForUser(Long, LocalDate, LocalDate)} 経由で手動再実行する</li>
 *   <li>その週にメモが0件のユーザー: {@code blog_posts} レコードを作成しない（無音スキップ）</li>
 *   <li>mood セクションの分岐基準: {@code user_action_memo_settings.mood_enabled} の現在値ではなく、
 *       その週の {@code action_memos} に {@code mood IS NOT NULL} が1件以上存在するかで判定する。
 *       これにより mood を OFF→ON→OFF と切り替えたユーザーでも過去データが正しく反映される</li>
 * </ul>
 *
 * <p><b>冪等性</b>: 同じ週 × 同じユーザーに対して複数回実行された場合、同じ slug
 * （{@code weekly-YYYYMMDD-YYYYMMDD}）の既存ブログ記事を論理削除してから新規 INSERT する。
 * {@code blog_posts} の {@code uq_bp_slug_user} ユニーク制約は {@code (user_id, slug, deleted_at)}
 * 複合キーのため、論理削除済みレコードは制約対象外となり衝突しない。</p>
 *
 * <p><b>i18n 方針</b>: 設計書 §11.1 では週次テンプレ見出しの i18n キーが6言語分定義されているが、
 * バックエンド側では当面ハードコード日本語で生成する。Markdown は生成時点で確定・保存され、
 * 閲覧時の言語切替は Phase 5 以降の課題として検討する。
 * TODO(F02.5 Phase 5+): Markdown 生成時にユーザーのロケールを参照して見出しを切り替える。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionMemoWeeklySummaryService {

    private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");

    /** 週次まとめの集計期間（日数） */
    private static final int SUMMARY_DAYS = 7;

    /** ランダム抽出する代表メモ件数（設計書 §11 #16） */
    private static final int FEATURED_MEMO_COUNT = 3;

    private static final DateTimeFormatter DATE_LABEL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter SLUG_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MEMO_SHORT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd");

    private final ActionMemoRepository memoRepository;
    private final BlogPostRepository blogPostRepository;
    private final ActionMemoMetrics actionMemoMetrics;

    // ==================================================================
    // エントリポイント（スケジュール起動）
    // ==================================================================

    /**
     * 週次まとめ自動生成バッチ。毎週日曜 21:00 JST に起動する。
     *
     * <p>過去7日間（今日を含まず、{@code today-7} から {@code today-1} まで）にメモを書いた
     * 全ユーザーに対し、週次ふりかえりブログを生成する。</p>
     *
     * <p>バッチ全体で例外が出ないよう、個別ユーザーの生成は try/catch で隔離される。
     * 1ユーザーの失敗は次のユーザーの処理に影響しない。</p>
     */
    @Scheduled(cron = "0 0 21 * * SUN", zone = "Asia/Tokyo")
    @SchedulerLock(name = "actionMemoWeeklySummary",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void generateWeeklySummaries() {
        LocalDate today = LocalDate.now(ZONE_JST);
        LocalDate from = today.minusDays(SUMMARY_DAYS);
        LocalDate to = today.minusDays(1);

        log.info("[ActionMemoWeeklySummary] バッチ開始: from={}, to={}", from, to);

        List<Long> userIds;
        try {
            userIds = memoRepository.findDistinctUserIdsByMemoDateBetween(from, to);
        } catch (RuntimeException e) {
            log.error("[ActionMemoWeeklySummary] 対象ユーザー抽出に失敗したためバッチを中断", e);
            return;
        }

        int generated = 0;
        int skipped = 0;
        int failed = 0;

        for (Long userId : userIds) {
            try {
                boolean created = generateForUser(userId, from, to);
                if (created) {
                    generated++;
                    actionMemoMetrics.recordWeeklySummaryGenerated();
                } else {
                    skipped++;
                    actionMemoMetrics.recordWeeklySummarySkipped();
                }
            } catch (RuntimeException e) {
                failed++;
                actionMemoMetrics.recordWeeklySummaryFailed();
                log.error("[ActionMemoWeeklySummary] ユーザー単位の生成失敗: userId={}", userId, e);
                // 次のユーザーへ進む
            }
        }

        log.info("[ActionMemoWeeklySummary] バッチ完了: 生成件数={} スキップ件数={} 失敗件数={}",
                generated, skipped, failed);
    }

    // ==================================================================
    // SYSTEM_ADMIN 用 手動再生成
    // ==================================================================

    /**
     * SYSTEM_ADMIN 手動再生成の単一ユーザー向けエントリポイント。
     * 対象ユーザーが該当期間にメモを書いていない場合は {@code false} を返しスキップする。
     *
     * @param userId 対象ユーザー
     * @param from   集計開始日（含む）
     * @param to     集計終了日（含む）
     * @return 生成した場合 {@code true}、スキップした場合 {@code false}
     */
    @Transactional
    public boolean regenerateForUser(Long userId, LocalDate from, LocalDate to) {
        return generateForUser(userId, from, to);
    }

    /**
     * SYSTEM_ADMIN 手動再生成の全ユーザー向けエントリポイント。
     *
     * @param from 集計開始日（含む）
     * @param to   集計終了日（含む）
     * @return 集計結果（生成・スキップ・失敗の件数）
     */
    public RegenerationResult regenerateForAll(LocalDate from, LocalDate to) {
        List<Long> userIds = memoRepository.findDistinctUserIdsByMemoDateBetween(from, to);
        int generated = 0;
        int skipped = 0;
        int failed = 0;
        for (Long userId : userIds) {
            try {
                if (generateForUser(userId, from, to)) {
                    generated++;
                    actionMemoMetrics.recordWeeklySummaryGenerated();
                } else {
                    skipped++;
                    actionMemoMetrics.recordWeeklySummarySkipped();
                }
            } catch (RuntimeException e) {
                failed++;
                actionMemoMetrics.recordWeeklySummaryFailed();
                log.error("[ActionMemoWeeklySummary] 手動再生成で個別失敗: userId={}", userId, e);
            }
        }
        return new RegenerationResult(generated, skipped, failed);
    }

    /**
     * 当期の範囲（今日を含まない過去7日間）を返す。
     * Controller / Service で「期間省略時のデフォルト」を共有するため公開している。
     */
    public LocalDate[] currentPeriod() {
        LocalDate today = LocalDate.now(ZONE_JST);
        return new LocalDate[]{today.minusDays(SUMMARY_DAYS), today.minusDays(1)};
    }

    // ==================================================================
    // 実処理
    // ==================================================================

    /**
     * 1ユーザー分の週次まとめを生成する。
     * メモが0件の場合は {@code false} を返しスキップする。
     * 同じ slug の既存ブログ記事が存在する場合は論理削除してから新規 INSERT する（冪等性）。
     *
     * @return 生成した場合 {@code true}、スキップ（メモ0件）した場合 {@code false}
     */
    @Transactional
    protected boolean generateForUser(Long userId, LocalDate from, LocalDate to) {
        List<ActionMemoEntity> memos = memoRepository
                .findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(userId, from, to);
        if (memos.isEmpty()) {
            log.info("[ActionMemoWeeklySummary] スキップ(0件): userId={}", userId);
            return false;
        }

        // mood セクション分岐判定: その週に mood IS NOT NULL が1件以上あるか
        // （設計書 §5.5 mood_enabled の現在値ではなく実データを基準とする）
        boolean hasMood = memos.stream().anyMatch(m -> m.getMood() != null);

        String markdown = buildMarkdown(memos, from, to, hasMood);
        String title = "週次ふりかえり: " + from.format(DATE_LABEL_FORMAT) + " 〜 " + to.format(DATE_LABEL_FORMAT);
        String slug = "weekly-" + from.format(SLUG_DATE_FORMAT) + "-" + to.format(SLUG_DATE_FORMAT);
        String excerpt = buildExcerpt(memos);

        // 冪等性: 同 slug の既存ブログ記事を論理削除（再生成対応）
        blogPostRepository.findByUserIdAndSlug(userId, slug).ifPresent(existing -> {
            existing.softDelete();
            blogPostRepository.save(existing);
            log.info("[ActionMemoWeeklySummary] 既存まとめを論理削除して差し替え: userId={}, blogPostId={}",
                    userId, existing.getId());
        });

        BlogPostEntity post = BlogPostEntity.builder()
                .userId(userId)
                .authorId(userId)
                .title(title)
                .slug(slug)
                .body(markdown)
                .excerpt(excerpt)
                .postType(PostType.BLOG)
                .visibility(Visibility.PRIVATE)
                .status(PostStatus.PUBLISHED)
                .publishedAt(LocalDateTime.now())
                .crossPostToTimeline(false)
                .targetType("ALL")
                .build();
        BlogPostEntity saved = blogPostRepository.save(post);

        log.info("[ActionMemoWeeklySummary] 生成成功: userId={}, blogPostId={}, memoCount={}, moodSection={}",
                userId, saved.getId(), memos.size(), hasMood);
        return true;
    }

    // ==================================================================
    // Markdown 組み立て
    // ==================================================================

    /**
     * 週次まとめ Markdown を組み立てる。
     *
     * <pre>
     * # 週次ふりかえり: 2026-04-06 〜 2026-04-12
     *
     * ## 📊 今週のサマリー
     * - メモ件数: 32件
     * - 投稿日数: 6/7日
     * - 平均気分: 🙂 いい感じ
     *
     * ## 📈 気分の推移
     * - 月: 🙂 GOOD / GOOD / OK
     * - ...
     *
     * ## 🏷️ よく使ったタグ
     * [準備中] ...
     *
     * ## 📝 今週の代表的なメモ（ランダム抽出）
     * - [4/06] 朝散歩 30分。気持ちよかった
     * - ...
     * </pre>
     */
    private String buildMarkdown(
            List<ActionMemoEntity> memos, LocalDate from, LocalDate to, boolean hasMood) {

        int memoCount = memos.size();
        long postedDays = memos.stream()
                .map(ActionMemoEntity::getMemoDate)
                .distinct()
                .count();

        StringBuilder sb = new StringBuilder();

        // タイトル
        sb.append("# 週次ふりかえり: ")
                .append(from.format(DATE_LABEL_FORMAT))
                .append(" 〜 ")
                .append(to.format(DATE_LABEL_FORMAT))
                .append("\n\n");

        // サマリー
        sb.append("## 📊 今週のサマリー\n");
        sb.append("- メモ件数: ").append(memoCount).append("件\n");
        sb.append("- 投稿日数: ").append(postedDays).append("/7日\n");
        if (hasMood) {
            ActionMemoMood average = calculateAverageMood(memos);
            if (average != null) {
                sb.append("- 平均気分: ")
                        .append(moodEmoji(average))
                        .append(" ")
                        .append(moodLabelJa(average))
                        .append("\n");
            }
        }
        sb.append("\n");

        // 気分の推移
        if (hasMood) {
            sb.append("## 📈 気分の推移\n");
            appendMoodTrend(sb, memos, from, to);
            sb.append("\n");
        }

        // タグ集計
        sb.append("## 🏷️ よく使ったタグ\n");
        sb.append("[準備中] タグ集計は Phase 4 で有効化されます。\n\n");

        // 代表的なメモ（ランダム抽出3件）
        sb.append("## 📝 今週の代表的なメモ（ランダム抽出）\n");
        List<ActionMemoEntity> featured = pickRandomMemos(memos, FEATURED_MEMO_COUNT);
        for (ActionMemoEntity memo : featured) {
            sb.append("- [")
                    .append(memo.getMemoDate().format(MEMO_SHORT_DATE_FORMAT))
                    .append("] ")
                    .append(sanitizeMarkdown(memo.getContent()))
                    .append("\n");
        }

        return sb.toString();
    }

    /**
     * 曜日ごとに mood を集計して行を追記する。mood が無い日はスキップ。
     */
    private void appendMoodTrend(
            StringBuilder sb, List<ActionMemoEntity> memos, LocalDate from, LocalDate to) {
        Map<LocalDate, List<ActionMemoMood>> byDate = new java.util.LinkedHashMap<>();
        // from〜to を順に初期化（順序保持のため）
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            byDate.put(d, new ArrayList<>());
        }
        for (ActionMemoEntity memo : memos) {
            if (memo.getMood() == null) continue;
            byDate.computeIfAbsent(memo.getMemoDate(), k -> new ArrayList<>())
                    .add(memo.getMood());
        }
        for (Map.Entry<LocalDate, List<ActionMemoMood>> entry : byDate.entrySet()) {
            List<ActionMemoMood> moods = entry.getValue();
            if (moods.isEmpty()) continue;
            String dayLabel = dayOfWeekLabelJa(entry.getKey().getDayOfWeek());
            // 代表絵文字は最初の mood のもの
            String emoji = moodEmoji(moods.get(0));
            String moodNames = String.join(" / ",
                    moods.stream().map(Enum::name).toList());
            sb.append("- ").append(dayLabel).append(": ")
                    .append(emoji).append(" ").append(moodNames).append("\n");
        }
    }

    /**
     * 平均 mood を「最頻値」で計算する。同数の場合は順位（GREAT>GOOD>OK>TIRED>BAD）で前寄りを返す。
     */
    private ActionMemoMood calculateAverageMood(List<ActionMemoEntity> memos) {
        Map<ActionMemoMood, Integer> counts = new EnumMap<>(ActionMemoMood.class);
        for (ActionMemoEntity memo : memos) {
            if (memo.getMood() != null) {
                counts.merge(memo.getMood(), 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) return null;
        return counts.entrySet().stream()
                .max(Map.Entry.<ActionMemoMood, Integer>comparingByValue()
                        .thenComparing(e -> -e.getKey().ordinal()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * 代表メモのランダム抽出。
     * 安定した順序が必要なテストでは {@code Collections.shuffle} のシード固定が使えないため、
     * 件数不足時はそのまま先頭から返すことで決定性を確保する。
     */
    private List<ActionMemoEntity> pickRandomMemos(List<ActionMemoEntity> memos, int count) {
        if (memos.size() <= count) {
            return new ArrayList<>(memos);
        }
        List<ActionMemoEntity> copy = new ArrayList<>(memos);
        Collections.shuffle(copy);
        return copy.subList(0, count);
    }

    /**
     * Markdown の箇条書きで表示したときに見た目が崩れる制御文字（改行）を空白に置換する。
     */
    private String sanitizeMarkdown(String content) {
        if (content == null) return "";
        return content.replace("\r", "").replace("\n", " ");
    }

    /**
     * excerpt は最初のメモの先頭 200 文字まで。
     */
    private String buildExcerpt(List<ActionMemoEntity> memos) {
        if (memos.isEmpty()) return "";
        String first = sanitizeMarkdown(memos.get(0).getContent());
        return first.length() > 200 ? first.substring(0, 200) : first;
    }

    private String moodEmoji(ActionMemoMood mood) {
        return switch (mood) {
            case GREAT -> "😄";
            case GOOD -> "🙂";
            case OK -> "😐";
            case TIRED -> "😩";
            case BAD -> "😞";
        };
    }

    private String moodLabelJa(ActionMemoMood mood) {
        return switch (mood) {
            case GREAT -> "絶好調";
            case GOOD -> "いい感じ";
            case OK -> "普通";
            case TIRED -> "疲れ";
            case BAD -> "しんどい";
        };
    }

    private String dayOfWeekLabelJa(DayOfWeek dow) {
        return switch (dow) {
            case MONDAY -> "月";
            case TUESDAY -> "火";
            case WEDNESDAY -> "水";
            case THURSDAY -> "木";
            case FRIDAY -> "金";
            case SATURDAY -> "土";
            case SUNDAY -> "日";
        };
    }

    // ==================================================================
    // DTO
    // ==================================================================

    /**
     * 再生成結果 DTO。Controller が JSON 応答に変換する。
     */
    public record RegenerationResult(int regeneratedCount, int skippedCount, int failedCount) {
    }
}
