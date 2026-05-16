package com.mannschaft.app.advertising.campaign.exception;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F09.17 メッセージ型キャンペーンのエラーコード。
 * 設計書 §4「共通エラーコード」に対応。
 */
@Getter
@RequiredArgsConstructor
public enum AdCampaignErrorCode implements ErrorCode {

    /** キャンペーン不存在 */
    AD_CAMPAIGN_NOT_FOUND(
            "AD_CAMPAIGN_NOT_FOUND",
            "指定されたキャンペーンが見つかりません",
            Severity.WARN),

    /** 状態遷移違反 */
    AD_CAMPAIGN_INVALID_STATE(
            "AD_CAMPAIGN_INVALID_STATE",
            "キャンペーンの状態が操作に適合しません",
            Severity.WARN),

    /** credit_limit 超過 */
    AD_CAMPAIGN_CREDIT_EXCEEDED(
            "AD_CAMPAIGN_CREDIT_EXCEEDED",
            "クレジット上限を超過したためキャンペーンを開始できません",
            Severity.WARN),

    /** モデレーションブロック中 */
    AD_CAMPAIGN_MODERATION_BLOCKED(
            "AD_CAMPAIGN_MODERATION_BLOCKED",
            "モデレーションによりブロックされています",
            Severity.WARN),

    /** セグメント条件不正 */
    AD_AUDIENCE_INVALID(
            "AD_AUDIENCE_INVALID",
            "ターゲティング条件が不正です",
            Severity.WARN),

    /** 必須チャネル未設定 */
    AD_CHANNEL_REQUIRED(
            "AD_CHANNEL_REQUIRED",
            "少なくとも 1 つのチャネルを設定してください",
            Severity.WARN),

    /** blocked_advertiser_account_ids 上限超過 */
    AD_PREFERENCES_BLOCKED_LIMIT(
            "AD_PREFERENCES_BLOCKED_LIMIT",
            "ブロック広告主は最大 100 件までです",
            Severity.WARN),

    /** unsubscribe トークン失効 */
    AD_UNSUBSCRIBE_TOKEN_EXPIRED(
            "AD_UNSUBSCRIBE_TOKEN_EXPIRED",
            "オプトアウト用のトークンが失効しています",
            Severity.WARN),

    /** 通報レート制限 */
    AD_REPORT_RATE_LIMITED(
            "AD_REPORT_RATE_LIMITED",
            "通報の回数上限に達しました。時間をおいて再度お試しください",
            Severity.WARN),

    /** モデレーション審査不可状態（DRAFT/REVIEW 以外で approve しようとした） */
    NOT_REVIEWABLE(
            "AD_CAMPAIGN_NOT_REVIEWABLE",
            "このキャンペーンは審査対象の状態ではありません",
            Severity.WARN),

    /** 既にブロック済みのキャンペーンへの重複ブロック (409 Conflict) */
    ALREADY_BLOCKED(
            "AD_CAMPAIGN_ALREADY_BLOCKED",
            "既にブロック済みのキャンペーンです",
            Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
