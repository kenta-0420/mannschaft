package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 公開ニュースレター号（村横断・案Y の「みんなのお便り」）レスポンス DTO（F17.1 ②-4・設計書 §8.2）。
 *
 * <p>{@code visibility=PUBLIC} かつ {@code status=PUBLISHED} の号のみを表す。村メンバー限定の内部情報
 * （コメント最終更新者 ID・楽観ロック版番号・非公開ステータス遷移など）は<b>意図的に出さない</b>。
 * {@code villageId} は号の帰属を示すために載せるが、村メンバーシップや設定は露出しない。</p>
 *
 * @param id                   号 ID（UUIDv7）
 * @param villageId            発行元の村 ID（帰属表示のみ）
 * @param title                号タイトル
 * @param frequency            頻度（WEEKLY / MONTHLY・号外は null）
 * @param publishedAt          配信時刻
 * @param digestPostCount      ダイジェスト: 投稿数
 * @param digestNewMemberCount ダイジェスト: 新規村人数
 * @param digestFestivalCount  ダイジェスト: 祭件数
 * @param digestMeetupCount    ダイジェスト: 寄合件数
 * @param digestRecruitCount   ダイジェスト: 募集件数
 * @param digestTopic1Name     TOP1 トピック名
 * @param digestTopic1Count    TOP1 トピック件数
 * @param digestTopic2Name     TOP2 トピック名
 * @param digestTopic2Count    TOP2 トピック件数
 * @param digestTopic3Name     TOP3 トピック名
 * @param digestTopic3Count    TOP3 トピック件数
 * @param headmanComment       村長コメント（公開号の本文の一部）
 * @param tags                 付与タグ一覧
 */
@Builder
public record PublicNewsletterIssueResponse(
        UUID id,
        UUID villageId,
        String title,
        VillageNewsletterFrequency frequency,
        LocalDateTime publishedAt,
        Integer digestPostCount,
        Integer digestNewMemberCount,
        Integer digestFestivalCount,
        Integer digestMeetupCount,
        Integer digestRecruitCount,
        String digestTopic1Name,
        Integer digestTopic1Count,
        String digestTopic2Name,
        Integer digestTopic2Count,
        String digestTopic3Name,
        Integer digestTopic3Count,
        String headmanComment,
        List<NewsletterTagResponse> tags
) {}
