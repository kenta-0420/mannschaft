package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * F03.11.1 募集キャンセル料の記録一覧レスポンス（設計書 §10・免除 UI のための一覧）。
 *
 * <p>受取先側の管理者・本人が「自分が受け取るべきキャンセル料」を確認し、免除の対象を
 * 選ぶための最小限の情報のみを返す。対象者の識別に {@code userId} を含むが、
 * メールアドレス等の追加の個人情報は含めない。</p>
 *
 * <h2>{@code cancelledAt} が {@link OffsetDateTime} である理由</h2>
 *
 * <p>キャンセルが<b>起きた 1 点</b>であり、世界のどこで観測しても同じ瞬間を指す
 * ——[`datetime_policy_utc_instant_vs_wallclock.md`] §3 の判定フロー 1 に照らして「瞬間」であり、
 * 「土地の約束（壁時計）」ではない。同方針 §4 の型表は、瞬間のうち<b>API 入出力で
 * オフセットを明示したい場合</b>に {@link OffsetDateTime} を充てている。本 DTO は
 * まさに API の出力であり、クライアントに「+09:00 の 10:00」と曖昧さなく渡せる形にする。</p>
 *
 * <p>{@code LocalDateTime} は同方針が「原則使わない」とする型である（ゾーン情報が無く、
 * 読む側が文脈で意味を推測せざるを得ない）。<b>レスポンス DTO はデータベースの列型に
 * 縛られない</b>ため、Entity 側が {@code LocalDateTime} で持っていることは
 * ここで {@code LocalDateTime} を使う理由にはならない。</p>
 *
 * <p>変換のゾーンは {@link UserZoneLocalDateTimeParser#SERVER_ZONE}（サーバの業務ゾーン）を
 * <b>明示的に</b>使う。JVM 既定ゾーンに依存させない（既定ゾーンは将来の是正対象であり、
 * 依存を増やすと解体できなくなる）。</p>
 */
@Getter
public class RecruitmentCancellationRecordSummaryResponse {

    private final Long id;
    private final Long listingId;
    private final String listingTitle;
    private final Long participantId;
    private final Long userId;
    private final Integer feeAmount;
    private final String paymentStatus;
    private final OffsetDateTime cancelledAt;
    private final Integer hoursBeforeStart;

    /**
     * JPQL のコンストラクタ式から呼ばれる。
     *
     * <p>{@code cancelledAt} は DB の列型に合わせて壁時計値として受け取り、
     * ここでサーバの業務ゾーンを与えて瞬間（{@link OffsetDateTime}）へ正規化する。
     * 変換をこの 1 箇所に閉じることで、呼び出し側がゾーンを取り違える余地を無くす。</p>
     */
    public RecruitmentCancellationRecordSummaryResponse(
            Long id,
            Long listingId,
            String listingTitle,
            Long participantId,
            Long userId,
            Integer feeAmount,
            String paymentStatus,
            LocalDateTime cancelledAt,
            Integer hoursBeforeStart) {
        this.id = id;
        this.listingId = listingId;
        this.listingTitle = listingTitle;
        this.participantId = participantId;
        this.userId = userId;
        this.feeAmount = feeAmount;
        this.paymentStatus = paymentStatus;
        this.cancelledAt = cancelledAt == null
                ? null
                : cancelledAt.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toOffsetDateTime();
        this.hoursBeforeStart = hoursBeforeStart;
    }
}
