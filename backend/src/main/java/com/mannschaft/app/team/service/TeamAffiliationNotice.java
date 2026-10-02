package com.mannschaft.app.team.service;

import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.fanout.FanoutMessageKind;

import java.util.List;

/**
 * チーム加盟の通知1件分の内容（F01.2.1 §6.7）。
 *
 * <p>状態を変える操作と同じトランザクションで fan-out ジョブを enqueue するための入力。
 * 受信者は Worker の処理時点で解決する（{@link RecipientScope} の対象を引く）。</p>
 *
 * @param notificationType 通知種別（{@code source_type} は種別から決まる）
 * @param messageKind      文面の種別（6言語の文面を enqueue 時に描画する）
 * @param messageArgs      文面の引数（利用者が書いた中身。組織名・チーム名など）
 * @param recipientScope   受信者の解決方式
 * @param recipientScopeId 受信者を引く対象の ID（組織 ID またはチーム ID）
 * @param organizationId   テナントとしての組織 ID
 * @param membershipId     加盟の ID（冪等キー・{@code source_id}）
 * @param actorUserId      操作者
 * @param actionUrl        通知から開く画面（slug を使う。数値 ID の URL は踏襲しない）
 */
public record TeamAffiliationNotice(NotificationType notificationType,
                                    FanoutMessageKind messageKind,
                                    List<String> messageArgs,
                                    RecipientScope recipientScope,
                                    Long recipientScopeId,
                                    Long organizationId,
                                    Long membershipId,
                                    Long actorUserId,
                                    String actionUrl) {

    /** 受信者の解決方式（§6.7）。 */
    public enum RecipientScope {
        /** 組織の ADMIN 全員。 */
        ORGANIZATION_ADMINS,
        /** チームの加盟操作者（チーム ADMIN と {@code MANAGE_ORG_AFFILIATION} を付与されたメンバー）。 */
        TEAM_AFFILIATION_OPERATORS
    }

    /**
     * 加盟申請が届いた通知（{@code TEAM_ORG_APPLICATION_RECEIVED}。受信者は組織 ADMIN。§6.1 step 12）。
     */
    public static TeamAffiliationNotice applicationReceived(Long organizationId, String organizationSlug,
                                                            String teamName, String organizationName,
                                                            Long membershipId, Long actorUserId) {
        return new TeamAffiliationNotice(
                NotificationType.TEAM_ORG_APPLICATION_RECEIVED,
                FanoutMessageKind.TEAM_ORG_APPLICATION_RECEIVED,
                List.of(teamName, organizationName),
                RecipientScope.ORGANIZATION_ADMINS,
                organizationId,
                organizationId,
                membershipId,
                actorUserId,
                "/organizations/" + organizationSlug + "/member-teams?view=applications");
    }
}
