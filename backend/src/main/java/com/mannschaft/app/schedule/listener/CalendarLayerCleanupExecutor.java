package com.mannschaft.app.schedule.listener;

import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import com.mannschaft.app.schedule.repository.UserCalendarLayerSettingRepository;
import com.mannschaft.app.schedule.repository.UserCalendarSyncSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * カレンダーレイヤー設定の後始末を<b>親とは別の新規トランザクションで</b>実行する Bean（F03.19 §10.4）。
 *
 * <h2>なぜ {@link CalendarLayerLifecycleListener} と別クラスなのか</h2>
 * <p>{@code @Transactional(REQUIRES_NEW)} の commit / rollback は<b>メソッドを抜けた後に
 * プロキシが行う</b>。したがって削除 SQL が失敗して新規トランザクションが rollback-only になった場合、
 * メソッド<b>内部</b>の {@code catch} が SQL 例外を捕まえても、その後のコミット試行で生じる
 * 完了例外（{@code UnexpectedRollbackException} / {@code TransactionSystemException}）は
 * {@code try} の<b>外側</b>へ伝播する。同一メソッドに「REQUIRES_NEW」と「握り漏らさない catch」を
 * 同居させる形は原理的に成立しない。</p>
 *
 * <p>本リポジトリでは同じ欠陥形が過去に 3 ドメインで発見されている
 * （「バッチ全体を単一 TX で包み 1 件ずつ catch は機能しない」）。定石どおり、
 * <b>REQUIRES_NEW の処理を別 Bean へ切り出し、その呼び出し全体を非トランザクションな
 * 呼び出し側（リスナー）で捕捉する</b>。同一クラス内のメソッド分割では自己呼び出しとなり
 * プロキシが挟まらないため、切り出し先は必ず別 Bean でなければならない。</p>
 *
 * <p>本 Bean のメソッドは<b>例外を握り潰さない</b>。失敗はそのまま呼び出し側へ返し、
 * ログとベストエフォートの判断はリスナー側に一本化する（「どこで失敗を飲んだか」を 1 箇所にする）。</p>
 */
@Component
@RequiredArgsConstructor
public class CalendarLayerCleanupExecutor {

    private final UserCalendarSyncSettingRepository syncRepository;
    private final AccountPurgeCompletionService completionService;

    private final UserCalendarLayerSettingRepository repository;

    /**
     * 指定スコープ（チーム／組織）の設定行を全ユーザー分、新規トランザクションで物理削除する。
     *
     * @param scopeType レイヤー種別（{@code TEAM} / {@code ORGANIZATION}）
     * @param scopeId   レイヤー対象ID
     * @return 削除した行数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteScope(String scopeType, Long scopeId) {
        return repository.deleteByScopeTypeAndScopeId(scopeType, scopeId);
    }

    /**
     * 指定ユーザーの設定行を全スコープ分、新規トランザクションで物理削除する。
     *
     * @param userId 対象ユーザー
     * @return 削除した行数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteByUser(Long userId) {
        return repository.deleteByUserId(userId);
    }

    /** 30日後の強匿名化。所有データの削除コミット後にのみ完了を記録する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purgeAccountSettings(Long userId) {
        purgeSettings(userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                completionService.markDomainSuccess(userId, "schedule");
            }
        });
    }

    /** 手動再試行。呼出元はこの新規TXのコミット成立後に完了状態を更新する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryPurge(Long userId) {
        purgeSettings(userId);
        return true;
    }

    /** 同じ所有domain内の全削除を一つのTXで実行し、途中失敗を伝播させる。 */
    private void purgeSettings(Long userId) {
        syncRepository.deleteByUserId(userId);
        repository.deleteByUserId(userId);
    }
}
