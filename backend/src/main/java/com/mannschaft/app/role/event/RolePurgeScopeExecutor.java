package com.mannschaft.app.role.event;

import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.role.service.RoleSuccessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 柱①「ADMINゼロ根治」検分反映（P1-1） — {@link RolePurgeEventListener} の
 * スコープ単位処理を <b>独立した {@code REQUIRES_NEW} トランザクション</b>に切り出す。
 *
 * <p><b>なぜ切り出すか</b>: {@code RolePurgeEventListener#on} はリスナー全体を1つの
 * {@code @Transactional(REQUIRES_NEW)} で包んでいた。ループ内で1スコープの処理が
 * 例外を投げても catch して次のスコープへ進む設計だったが、{@code roleService}/
 * {@code roleSuccessionService} 側の {@code @Transactional}（デフォルト伝播＝
 * 既存トランザクションに参加）で例外が発生すると、Spring のトランザクション
 * インターセプタがその時点で <b>参加先トランザクションを rollback-only にマークする</b>。
 * ループ側で catch して処理を継続していても、rollback-only フラグは残ったままのため、
 * 最終コミット時に {@code UnexpectedRollbackException}（またはサイレントロールバック）が
 * 発生し、<b>それまでに処理できていた他スコープの分まで巻き添えでロールバックする</b>。
 * これでは「1スコープの失敗が他スコープ処理を止めない」という設計要件（§9 / §13）を
 * 満たせない。</p>
 *
 * <p>自己呼び出し（{@code this.processScope(...)}）では Spring AOP プロキシを経由せず
 * {@code @Transactional} が一切効かないため、<b>別 Bean に切り出す</b>必要がある
 * （Spring AOP の既知の制約）。本クラスがその別 Bean。</p>
 */
@Component
@RequiredArgsConstructor
public class RolePurgeScopeExecutor {

    private final RoleService roleService;
    private final RoleSuccessionService roleSuccessionService;

    /**
     * 1 スコープ分の purge 処理（承継フック + ロール除名）を独立トランザクションで実行する。
     *
     * <p>{@code REQUIRES_NEW} のため、呼び出し元（{@code RolePurgeEventListener}）の
     * トランザクションを一時中断し新規トランザクションを開始する。本メソッドが例外を投げても
     * ロールバックされるのは<b>このスコープの分だけ</b>であり、呼び出し元や他スコープの
     * トランザクションには影響しない。</p>
     *
     * <p><b>検分反映（Codex第4巡 P1-a）</b>: {@code isAdmin} の場合、承継フック
     * （{@link RoleSuccessionService#forceTransferForPurge}）の結果が
     * {@link RoleSuccessionService.PurgeSuccessionResult#RETRY_LATER} のときは、
     * <b>{@code removeMemberWithoutAdminCheck} を呼ばずに例外を投げる</b>。承継が未完了
     * （旧 ADMIN が引き続きこのスコープの唯一の ADMIN）のまま除名すると、承継しないまま
     * 旧 ADMIN 行だけが消えて「ADMIN 0」が発生するため。本メソッドは
     * {@code REQUIRES_NEW}（P1-1）なので、この例外はこのスコープ分だけをロールバックし、
     * 呼び出し元 {@code RolePurgeEventListener} の既存の失敗継続ルール（failed カウント・
     * completion_status を SUCCESS にしない）に乗って再配送 / 補正バッチでの再試行に委ねる。</p>
     *
     * @param userId    退会（purge）対象ユーザー ID
     * @param scopeId   対象スコープ ID
     * @param scopeType TEAM / ORGANIZATION
     * @param isAdmin   対象ユーザーがこのスコープで ADMIN であったか（承継フック要否の判定）
     * @param purgeId   冪等キー補助情報（監査ログ用）
     * @throws IllegalStateException 承継が RETRY_LATER で未完了のまま終わった場合
     *                                （呼び出し元の失敗カウントへ乗せるため意図的に投げる）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processScope(Long userId, Long scopeId, String scopeType, boolean isAdmin, UUID purgeId) {
        if (isAdmin) {
            RoleSuccessionService.PurgeSuccessionResult result =
                    roleSuccessionService.forceTransferForPurge(scopeId, scopeType, userId, purgeId);
            if (result == RoleSuccessionService.PurgeSuccessionResult.RETRY_LATER) {
                throw new IllegalStateException(
                        "承継未完了（RETRY_LATER）のためremoveMemberWithoutAdminCheckを実行せず失敗扱いにする: "
                                + "scopeType=" + scopeType + ", scopeId=" + scopeId + ", userId=" + userId);
            }
        }
        roleService.removeMemberWithoutAdminCheck(scopeId, scopeType, userId);
    }
}
