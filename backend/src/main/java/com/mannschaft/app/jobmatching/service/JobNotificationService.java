package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.repository.JobContractRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

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
    private final JobContractRepository contractRepository;
    private final JobPostingRepository postingRepository;

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
     * Worker のチェックイン成立を Requester へ通知する（設計書 §2.7 JOB_CHECKED_IN）。
     *
     * <p>Requester 宛・{@link NotificationPriority#NORMAL}。契約や求人が存在しない場合は
     * 警告ログを出力して黙って抜ける（業務トランザクションを妨げない）。</p>
     *
     * @param contractId 対象契約 ID
     */
    public void notifyCheckedIn(Long contractId) {
        Optional<JobContractEntity> contractOpt = contractRepository.findById(contractId);
        if (contractOpt.isEmpty()) {
            log.warn("JOB_CHECKED_IN 通知送信時に契約が見つかりません: contractId={}", contractId);
            return;
        }
        JobContractEntity contract = contractOpt.get();
        Optional<JobPostingEntity> postingOpt = postingRepository.findById(contract.getJobPostingId());
        if (postingOpt.isEmpty()) {
            log.warn("JOB_CHECKED_IN 通知送信時に求人が見つかりません: contractId={}, postingId={}",
                    contractId, contract.getJobPostingId());
            return;
        }
        JobPostingEntity posting = postingOpt.get();

        String title = "Worker がチェックインしました";
        String body = String.format("「%s」で Worker の業務開始が記録されました。", posting.getTitle());
        String actionUrl = "/jobs/contracts/" + contractId;

        notificationHelper.notify(
                contract.getRequesterUserId(),
                TYPE_JOB_CHECKED_IN,
                title,
                body,
                SOURCE_TYPE_JOB_CONTRACT,
                contractId,
                NotificationScopeType.TEAM,
                posting.getTeamId(),
                actionUrl,
                contract.getWorkerUserId()
        );
    }

    /**
     * Worker のチェックアウト成立を Requester へ通知する（設計書 §2.7 JOB_CHECKED_OUT）。
     *
     * <p>Requester 宛・{@link NotificationPriority#NORMAL}。契約や求人が存在しない場合は
     * 警告ログを出力して黙って抜ける（業務トランザクションを妨げない）。</p>
     *
     * @param contractId 対象契約 ID
     */
    public void notifyCheckedOut(Long contractId) {
        Optional<JobContractEntity> contractOpt = contractRepository.findById(contractId);
        if (contractOpt.isEmpty()) {
            log.warn("JOB_CHECKED_OUT 通知送信時に契約が見つかりません: contractId={}", contractId);
            return;
        }
        JobContractEntity contract = contractOpt.get();
        Optional<JobPostingEntity> postingOpt = postingRepository.findById(contract.getJobPostingId());
        if (postingOpt.isEmpty()) {
            log.warn("JOB_CHECKED_OUT 通知送信時に求人が見つかりません: contractId={}, postingId={}",
                    contractId, contract.getJobPostingId());
            return;
        }
        JobPostingEntity posting = postingOpt.get();

        String title = "Worker がチェックアウトしました";
        String body = String.format("「%s」で Worker の業務終了が記録されました。完了報告を待機してください。",
                posting.getTitle());
        String actionUrl = "/jobs/contracts/" + contractId;

        notificationHelper.notify(
                contract.getRequesterUserId(),
                TYPE_JOB_CHECKED_OUT,
                title,
                body,
                SOURCE_TYPE_JOB_CONTRACT,
                contractId,
                NotificationScopeType.TEAM,
                posting.getTeamId(),
                actionUrl,
                contract.getWorkerUserId()
        );
    }

    /**
     * Geolocation 乖離（業務場所から閾値超の離れ）を Requester へアラート通知する
     *（設計書 §2.7 JOB_GEO_ANOMALY）。
     *
     * <p>強制配信（opt-out 不可）とするため {@link NotificationPriority#URGENT} で送信する。
     * 自動拒否はせず、Requester の判断に委ねるアラートとして扱う。契約や求人が見つからない
     * 場合は警告ログを出力してスキップ。</p>
     *
     * @param contractId     対象契約 ID
     * @param distanceMeters 業務場所からの距離（メートル、計算不能時は負値が渡される）
     */
    public void notifyGeoAnomaly(Long contractId, double distanceMeters) {
        Optional<JobContractEntity> contractOpt = contractRepository.findById(contractId);
        if (contractOpt.isEmpty()) {
            log.warn("JOB_GEO_ANOMALY 通知送信時に契約が見つかりません: contractId={}", contractId);
            return;
        }
        JobContractEntity contract = contractOpt.get();
        Optional<JobPostingEntity> postingOpt = postingRepository.findById(contract.getJobPostingId());
        if (postingOpt.isEmpty()) {
            log.warn("JOB_GEO_ANOMALY 通知送信時に求人が見つかりません: contractId={}, postingId={}",
                    contractId, contract.getJobPostingId());
            return;
        }
        JobPostingEntity posting = postingOpt.get();

        String title = "業務場所からの乖離が検知されました";
        String distanceLabel = distanceMeters >= 0
                ? String.format("約 %.0f m", distanceMeters)
                : "距離不明";
        String body = String.format(
                "「%s」でチェックイン位置が業務場所から %s 離れています。内容を確認してください。",
                posting.getTitle(), distanceLabel);
        String actionUrl = "/jobs/contracts/" + contractId;

        notificationHelper.notify(
                contract.getRequesterUserId(),
                TYPE_JOB_GEO_ANOMALY,
                NotificationPriority.URGENT,
                title,
                body,
                SOURCE_TYPE_JOB_CONTRACT,
                contractId,
                NotificationScopeType.TEAM,
                posting.getTeamId(),
                actionUrl,
                contract.getWorkerUserId()
        );
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
