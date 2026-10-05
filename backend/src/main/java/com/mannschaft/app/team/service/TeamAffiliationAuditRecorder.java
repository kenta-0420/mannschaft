package com.mannschaft.app.team.service;

import com.mannschaft.app.auth.AuditEventType;

import java.util.Map;

/**
 * チーム加盟の遷移の監査ログを、状態を変える操作と<b>同じトランザクション</b>で記録する窓口（ポート）。
 *
 * <p>遷移ごとに1行、所定の action と metadata が {@code audit_logs} に残る（F01.2.1 §4.1・§6.1 step 13）。
 * 業務ルール（冷却期間など）は監査ログを参照せず、制限テーブルだけで判定する（§4.2）。</p>
 *
 * <p>招待・承諾・辞退・取消（2-C）は、4-A の監査と同じく書き込みの<b>コミット後</b>に、トランザクションの外から呼ぶ
 * （チームの書き込みトランザクションから auth ドメインの Repository に届かせないため）。その場合の記録は
 * {@code AuditLogService} 自身のトランザクションで確定し、記録の失敗は操作を巻き戻さない。</p>
 *
 * <p>実装は {@link AuditLogTeamAffiliationAuditRecorder}。team ドメインが auth ドメインの Repository へ
 * 推移的に到達しないよう、ポートにしている。</p>
 */
public interface TeamAffiliationAuditRecorder {

    /**
     * 監査ログを1行記録する。
     *
     * @param eventType      遷移に対応する監査イベント種別
     * @param actorUserId    操作者
     * @param teamId         チームのコンテキスト
     * @param organizationId 組織のコンテキスト
     * @param metadata       イベント固有の補足情報（JSON にして保存する。個人情報は入れない）
     */
    void record(AuditEventType eventType, Long actorUserId, Long teamId, Long organizationId,
                Map<String, Object> metadata);
}
