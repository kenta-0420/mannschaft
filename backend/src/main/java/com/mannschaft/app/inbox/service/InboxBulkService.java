package com.mannschaft.app.inbox.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.inbox.dto.BulkInboxRequest;
import com.mannschaft.app.inbox.dto.BulkResultResponse;
import com.mannschaft.app.inbox.dto.TriageTargetRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * F04.11 統合通知インボックス：一括操作サービス。
 *
 * <p>設計書: 02_api_design.md §3.5。複数通知への triage / ラベル付与を一括適用する。
 * 各 item を {@link InboxTriageService} / {@link InboxLabelService} に委譲し、件数を集計する。
 * 個々の item の失敗（可視性検証失敗・上限超過など {@link BusinessException}）は全体を止めず
 * スキップ件数に計上し、全体は 200 を返す（部分失敗許容・{@code BulkResultResponse} 手本）。</p>
 *
 * <p><b>意図的に当サービスは {@code @Transactional} を持たない</b>。委譲先（{@link InboxTriageService}
 * /{@link InboxLabelService}）の各メソッドが自前で {@code @Transactional} を持ち、item ごとに
 * 独立したトランザクション境界となる。仮に当サービスが外側トランザクションを張ると、1 item の
 * {@link BusinessException} が共有トランザクションを rollback-only にマークし、ここで捕捉しても
 * commit 時に {@code UnexpectedRollbackException} となる（部分失敗許容が成立しない）。
 * 外側 tx を張らないことで、各 item は自身のトランザクションで独立に commit/rollback され、
 * 失敗 item のみがロールバックされる。CLAUDE.md 原則5（@Transactional はドメイン内）も満たす。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboxBulkService {

    private final InboxTriageService triageService;
    private final InboxLabelService labelService;
    private final InboxAccessGuard inboxAccessGuard;

    /**
     * 一括操作を実行する。
     *
     * <p>{@code LABEL_ADD} のラベル所有検証は<b>item ループより前</b>に一度だけ行う
     * （{@link InboxAccessGuard#requireOwnedLabel}）。他者所有・不存在のラベル ID は
     * 全体を 404 で止め、存在を秘匿する。認可を業務処理より前に置くことで、ラベル ID の
     * 妥当性が item ごとのスキップ件数の差として観測されるのを防ぐ。</p>
     *
     * @return 成功/スキップ件数
     */
    public BulkResultResponse bulk(Long userId, BulkInboxRequest request) {
        validatePreconditions(request);
        if (request.getAction() == BulkInboxRequest.BulkAction.LABEL_ADD) {
            inboxAccessGuard.requireOwnedLabel(userId, request.getLabelId());
        }

        int processed = 0;
        int skipped = 0;
        for (TriageTargetRequest item : request.getItems()) {
            try {
                applyOne(userId, request, item);
                processed++;
            } catch (BusinessException e) {
                // 個々の item の業務エラー（不可視・上限超過・過去時刻など）は全体を止めずスキップ計上
                log.info("inbox_bulk_item_skipped: userId={} action={} source={}:{} code={}",
                        userId, request.getAction(), item.getSourceType(), item.getSourceId(),
                        e.getErrorCode().getCode());
                skipped++;
            }
        }
        return new BulkResultResponse(processed, skipped);
    }

    /**
     * action 必須パラメータの事前検証（全 item 共通の致命的不備は全体 400 で弾く）。
     */
    private void validatePreconditions(BulkInboxRequest request) {
        switch (request.getAction()) {
            case SNOOZE -> {
                if (request.getSnoozedUntil() == null) {
                    throw new BusinessException(CommonErrorCode.COMMON_001);
                }
            }
            case LABEL_ADD -> {
                if (request.getLabelId() == null) {
                    throw new BusinessException(CommonErrorCode.COMMON_001);
                }
            }
            default -> {
                // ARCHIVE / UNARCHIVE は追加パラメータ不要
            }
        }
    }

    private void applyOne(Long userId, BulkInboxRequest request, TriageTargetRequest item) {
        switch (request.getAction()) {
            case ARCHIVE -> triageService.archive(userId, item.getSourceType(), item.getSourceId());
            case UNARCHIVE -> triageService.unarchive(userId, item.getSourceType(), item.getSourceId());
            case SNOOZE -> triageService.snooze(
                    userId, item.getSourceType(), item.getSourceId(), request.getSnoozedUntil());
            case LABEL_ADD -> labelService.assignLabel(
                    userId, request.getLabelId(), item.getSourceType(), item.getSourceId());
        }
    }
}
