package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * fan-out ジョブの<b>ロケール別・描画済み文面</b>（Issue #2871）。
 *
 * <p>親 {@link NotificationFanoutJob} 1 件につき配信 bucket の数（6 行）が enqueue 時に作られる。
 * ワーカーは受信者の locale でこの表を引き、行ごとに異なる title / body を
 * {@code notifications} へバルク INSERT する。</p>
 *
 * <h2>親の {@code title} / {@code body} 列は撤去した（二経路を残さない）</h2>
 * <p>親に「描画済み文字列」を残したまま子表を足すと、どちらを正とするか実装ごとにブレて
 * 「子表があるのに親の日本語が配られている」経路が静かに生き残る。本番に未処理データが無い
 * ことを確認済みのため、後方互換を捨てて親の列を落とし、文面の正本を子表 1 箇所に統一した。</p>
 *
 * <h2>FK / CASCADE の扱い</h2>
 * <p>親子とも notification ドメイン内であり、クロスドメイン FK 禁止（原則1）には抵触しない。
 * 同一ドメイン内なので CASCADE DELETE も許可される（原則2）。</p>
 */
@Entity
@Table(
        name = "notification_fanout_job_messages",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_fanout_job_message_locale",
                columnNames = {"job_id", "locale"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class NotificationFanoutJobMessage extends UuidV7Entity {

    /**
     * 親ジョブ ID（同一ドメイン内の参照）。
     *
     * <p>{@code @ManyToOne} の association ではなく ID 参照で持つ。ワーカーは
     * {@code findByJobId} でロケール表をまとめて引くだけであり、親から辿る必要がないため、
     * 遅延ロードのプロキシや N+1 の温床を作らない。</p>
     */
    @Column(name = "job_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID jobId;

    /** 配信ロケール（{@link com.mannschaft.app.common.i18n.DeliveryLocales#TAGS} の 6 種のいずれか）。 */
    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    /** 描画済みタイトル（enqueue 時にコードポイント境界で 200 まで切り詰め済み）。 */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 描画済み本文（enqueue 時にコードポイント境界で 1000 まで切り詰め済み）。 */
    @Column(name = "body", length = 1000)
    private String body;
}
