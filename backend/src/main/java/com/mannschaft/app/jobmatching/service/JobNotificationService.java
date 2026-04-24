package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 求人マッチング機能の通知発火サービス。
 *
 * <p>既存 {@link NotificationHelper} を薄くラップし、求人マッチングのイベント種別に応じた
 * 通知文・遷移先URL・スコープ設定を一元管理する。通知種別は {@code notifications.notification_type}
 * カラムへ文字列として記録される（システム全体が enum 型ではなく String 運用のため）。</p>
 *
 * <p>サポート通知種別:</p>
 * <ul>
 *   <li>{@code JOB_APPLIED} — 求人投稿者（Requester）へ「応募がありました」</li>
 *   <li>{@code JOB_MATCHED} — Worker へ「採用されました」</li>
 *   <li>{@code JOB_COMPLETION_REPORTED} — Requester へ「完了報告が届きました」</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobNotificationService {

    /** 通知 sourceType（通知テーブル側で識別するためのキー）。 */
    private static final String SOURCE_TYPE_JOB_POSTING = "JOB_POSTING";
    private static final String SOURCE_TYPE_JOB_APPLICATION = "JOB_APPLICATION";
    private static final String SOURCE_TYPE_JOB_CONTRACT = "JOB_CONTRACT";

    /** 通知種別コード（文字列定数）。 */
    private static final String TYPE_JOB_APPLIED = "JOB_APPLIED";
    private static final String TYPE_JOB_MATCHED = "JOB_MATCHED";
    private static final String TYPE_JOB_COMPLETION_REPORTED = "JOB_COMPLETION_REPORTED";

    /** Phase 13.1.2: QR チェックイン／アウト関連の通知種別。 */
    private static final String TYPE_JOB_CHECKED_IN = "JOB_CHECKED_IN";
    private static final String TYPE_JOB_CHECKED_OUT = "JOB_CHECKED_OUT";
    private static final String TYPE_JOB_GEO_ANOMALY = "JOB_GEO_ANOMALY";

    private final NotificationHelper notificationHelper;

    /**
     * 応募発生を Requester（求人投稿者）へ通知する。
     *
     * @param application 応募エンティティ
     * @param posting     応募対象の求人（投稿者特定のため）
     */
    public void notifyApplied(JobApplicationEntity application, JobPostingEntity posting) {
        Long requesterId = posting.getCreatedByUserId();
        String title = "求人に応募がありました";
        String body = String.format("「%s」に応募がありました。採否を判定してください。", posting.getTitle());
        String actionUrl = "/jobs/postings/" + posting.getId() + "/applications";

        notificationHelper.notify(
                requesterId,
                TYPE_JOB_APPLIED,
                title,
                body,
                SOURCE_TYPE_JOB_APPLICATION,
                application.getId(),
                NotificationScopeType.TEAM,
                posting.getTeamId(),
                actionUrl,
                application.getApplicantUserId()
        );
    }

    /**
     * 採用確定（契約成立）を Worker へ通知する。
     *
     * @param contract 生成された契約
     * @param posting  対象求人（タイトル表示のため）
     */
    public void notifyMatched(JobContractEntity contract, JobPostingEntity posting) {
        String title = "求人に採用されました";
        String body = String.format("「%s」への応募が採用されました。チャットで詳細をご確認ください。", posting.getTitle());
        String actionUrl = "/jobs/contracts/" + contract.getId();

        notificationHelper.notify(
                contract.getWorkerUserId(),
                TYPE_JOB_MATCHED,
                title,
                body,
                SOURCE_TYPE_JOB_CONTRACT,
                contract.getId(),
                NotificationScopeType.TEAM,
                posting.getTeamId(),
                actionUrl,
                contract.getRequesterUserId()
        );
    }

    /**
     * 完了報告を Requester へ通知する。
     *
     * @param contract 完了報告された契約
     * @param posting  対象求人（タイトル表示のため）
     */
    public void notifyCompletionReported(JobContractEntity contract, JobPostingEntity posting) {
        String title = "業務完了報告が届きました";
        String body = String.format("「%s」について Worker から完了報告が届きました。内容を確認してください。",
                posting.getTitle());
        String actionUrl = "/jobs/contracts/" + contract.getId();

        notificationHelper.notify(
                contract.getRequesterUserId(),
                TYPE_JOB_COMPLETION_REPORTED,
                title,
                body,
                SOURCE_TYPE_JOB_CONTRACT,
                contract.getId(),
                NotificationScopeType.TEAM,
                posting.getTeamId(),
                actionUrl,
                contract.getWorkerUserId()
        );
    }

    // ---------------------------------------------------------------------
    // Phase 13.1.2: QR チェックイン／アウト通知（足軽参実装のスタブ）
    // ---------------------------------------------------------------------

    /**
     * Worker のチェックイン成立を Requester へ通知する。
     *
     * <p>Phase 13.1.2 足軽参時点ではスタブ実装（呼び出しポイント確保のみ）。
     * 実通知本文・遷移先 URL・スコープ等の詳細化は足軽肆（Controller 担当）で
     * 実装する想定（設計書 §2.7 JOB_CHECKED_IN）。</p>
     *
     * @param contractId 対象契約 ID
     */
    public void notifyCheckedIn(Long contractId) {
        log.debug("[stub] JOB_CHECKED_IN 通知: contractId={}, type={}",
                contractId, TYPE_JOB_CHECKED_IN);
    }

    /**
     * Worker のチェックアウト成立を Requester へ通知する。
     *
     * <p>Phase 13.1.2 足軽参時点ではスタブ実装（設計書 §2.7 JOB_CHECKED_OUT）。</p>
     *
     * @param contractId 対象契約 ID
     */
    public void notifyCheckedOut(Long contractId) {
        log.debug("[stub] JOB_CHECKED_OUT 通知: contractId={}, type={}",
                contractId, TYPE_JOB_CHECKED_OUT);
    }

    /**
     * Geolocation 乖離（業務場所から閾値超の離れ）を Requester へアラート通知する。
     *
     * <p>Phase 13.1.2 足軽参時点ではスタブ実装（設計書 §2.7 JOB_GEO_ANOMALY、強制配信）。
     * 自動拒否はせず、Requester の判断に委ねるアラートとして送信する。</p>
     *
     * @param contractId     対象契約 ID
     * @param distanceMeters 業務場所からの距離（メートル）
     */
    public void notifyGeoAnomaly(Long contractId, double distanceMeters) {
        log.debug("[stub] JOB_GEO_ANOMALY 通知: contractId={}, distance={}m, type={}",
                contractId, distanceMeters, TYPE_JOB_GEO_ANOMALY);
    }

    // 源流からオーバーロードを提供（Contract 単独から呼べる版、未使用時はコンパイラ警告にならない実装）。

    /**
     * 契約のみから {@link JobPostingEntity} 解決不要で呼び出したい場合の簡易版（Requester には source_id から遷移可能）。
     * 通知本文の求人タイトルを含められないため詳細版の利用を推奨。
     *
     * @param application 応募エンティティ（Requester ID は呼び出し側で解決済みのケース）
     * @param requesterId 通知先ユーザー（Requester）
     * @param teamId      スコープチームID
     * @param postingId   関連求人 ID（URL 生成のため）
     */
    public void notifyApplied(JobApplicationEntity application, Long requesterId, Long teamId, Long postingId) {
        notificationHelper.notify(
                requesterId,
                TYPE_JOB_APPLIED,
                "求人に応募がありました",
                "求人への応募が届きました。採否を判定してください。",
                SOURCE_TYPE_JOB_APPLICATION,
                application.getId(),
                NotificationScopeType.TEAM,
                teamId,
                "/jobs/postings/" + postingId + "/applications",
                application.getApplicantUserId()
        );
    }
}
