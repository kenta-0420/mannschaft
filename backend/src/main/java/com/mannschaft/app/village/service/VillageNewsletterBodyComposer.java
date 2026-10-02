package com.mannschaft.app.village.service;

import com.mannschaft.app.village.entity.VillageNewsletterIssueEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 村ニュースレター号の通知タイトル・本文を組み立てるコンポーザ（F17.1 ②-3・設計書 §6.2 / §7.2）。
 *
 * <p>凍結ダイジェスト（{@code digest_*}）から本文の要約を作り、村長コメント（{@code headman_comment}）が
 * あれば連結する。<b>手間ゼロ既定（要件③）</b>のため、コメントの有無で分岐せず必ず本文を生成する。
 * 活動がまったく無い号（全 count 0・トピック無し）でも定型文で本文を作り、号は必ず配信される
 * （規則性の担保・マスター御裁可 Q2「空でも送る」）。</p>
 *
 * <h2>i18n</h2>
 * <p>本文の定型句は直書きせず {@link MessageSource} のメッセージキー（{@code village.newsletter.body.*}）で
 * 解決する。受信者ごとのロケール出し分けは本段のスコープ外で、<b>既定ロケール（{@link Locale#JAPANESE}）</b>で
 * 一律生成する（将来課題: 受信者の通知ロケール settings に応じた出し分け）。</p>
 *
 * <h2>長さ上限</h2>
 * <p>{@code NotificationEntity} の title=200・body=1000 制限に収めるため、生成後に切り詰める
 * （設計書 §7.2・{@code NotificationEntity.java}）。</p>
 */
@Component
@RequiredArgsConstructor
public class VillageNewsletterBodyComposer {

    /** 通知タイトルの最大長（{@code NotificationEntity.title} = 200）。 */
    static final int TITLE_MAX_LENGTH = 200;
    /** 通知本文の最大長（{@code NotificationEntity.body} = 1000）。 */
    static final int BODY_MAX_LENGTH = 1000;

    /**
     * 既定の生成ロケール。受信者ごとの出し分けは本段スコープ外のため、ja 固定で一律生成する
     * （将来課題: 通知 settings のロケールに応じた出し分け）。
     */
    private static final Locale DEFAULT_LOCALE = Locale.JAPANESE;

    private final MessageSource messageSource;

    /** 通知タイトル（号タイトルを 200 文字に切り詰める）。 */
    public String composeTitle(VillageNewsletterIssueEntity issue) {
        return truncate(issue.getTitle(), TITLE_MAX_LENGTH);
    }

    /**
     * 通知本文を組み立てる。ダイジェスト要約に、村長コメントがあれば連結する（1000 文字に切り詰め）。
     */
    public String composeBody(VillageNewsletterIssueEntity issue) {
        StringBuilder sb = new StringBuilder(digestSummary(issue));

        String comment = issue.getHeadmanComment();
        if (comment != null && !comment.isBlank()) {
            sb.append("\n\n")
              .append(msg("village.newsletter.body.commentHeading"))
              .append("\n")
              .append(comment.strip());
        }
        return truncate(sb.toString(), BODY_MAX_LENGTH);
    }

    /**
     * 凍結ダイジェストから要約行を組み立てる。0 の指標は行に出さず、正の指標・トピックのみを列挙する。
     * 何も無ければ（全 count 0・トピック無し）「静かな一週間でした」等の定型文を返す。
     */
    private String digestSummary(VillageNewsletterIssueEntity issue) {
        List<String> lines = new ArrayList<>();
        addCountLine(lines, "village.newsletter.body.digest.post", issue.getDigestPostCount());
        addCountLine(lines, "village.newsletter.body.digest.newMember", issue.getDigestNewMemberCount());
        addCountLine(lines, "village.newsletter.body.digest.festival", issue.getDigestFestivalCount());
        addCountLine(lines, "village.newsletter.body.digest.meetup", issue.getDigestMeetupCount());
        addCountLine(lines, "village.newsletter.body.digest.recruit", issue.getDigestRecruitCount());
        addTopicLine(lines, issue.getDigestTopic1Name(), issue.getDigestTopic1Count());
        addTopicLine(lines, issue.getDigestTopic2Name(), issue.getDigestTopic2Count());
        addTopicLine(lines, issue.getDigestTopic3Name(), issue.getDigestTopic3Count());

        if (lines.isEmpty()) {
            return msg("village.newsletter.body.quiet");
        }
        return String.join("\n", lines);
    }

    private void addCountLine(List<String> lines, String key, Integer count) {
        if (count != null && count > 0) {
            lines.add(msg(key, count));
        }
    }

    private void addTopicLine(List<String> lines, String name, Integer count) {
        if (name != null && !name.isBlank() && count != null && count > 0) {
            lines.add(msg("village.newsletter.body.topic", name, count));
        }
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, DEFAULT_LOCALE);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
