package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.entity.enums.VillageNewsletterVisibility;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 村ニュースレター号 一覧要約 レスポンス DTO（F17.1 ②-4・設計書 §8.1）。
 *
 * <p>号一覧（村内 pull）向けの軽量ビュー。ダイジェスト本体は要約カウントのみを載せ、
 * 詳細（全 digest_* / topic / コメント全文）は {@link NewsletterIssueDetailResponse} で返す。</p>
 *
 * @param id                    号 ID（UUIDv7）
 * @param villageId             村 ID
 * @param title                 号タイトル
 * @param frequency             頻度（WEEKLY / MONTHLY・号外は null）
 * @param status                ライフサイクル状態
 * @param visibility            公開範囲（VILLAGE_MEMBERS / PUBLIC）
 * @param periodStart           集計期間の開始
 * @param periodEnd             集計期間の終了
 * @param publishedAt           実配信時刻（未配信は null）
 * @param createdAt             作成時刻
 * @param digestPostCount       ダイジェスト: 投稿数
 * @param digestNewMemberCount  ダイジェスト: 新規村人数
 * @param hasComment            村長コメントが設定されているか
 * @param tags                  付与タグ一覧
 */
@Builder
public record NewsletterIssueSummaryResponse(
        UUID id,
        UUID villageId,
        String title,
        VillageNewsletterFrequency frequency,
        VillageNewsletterIssueStatus status,
        VillageNewsletterVisibility visibility,
        LocalDateTime periodStart,
        LocalDateTime periodEnd,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        Integer digestPostCount,
        Integer digestNewMemberCount,
        boolean hasComment,
        List<NewsletterTagResponse> tags
) {}
