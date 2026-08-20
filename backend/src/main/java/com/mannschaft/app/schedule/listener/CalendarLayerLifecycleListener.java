package com.mannschaft.app.schedule.listener;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.organization.event.OrganizationDeletedEvent;
import com.mannschaft.app.schedule.repository.UserCalendarLayerSettingRepository;
import com.mannschaft.app.team.event.TeamDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * カレンダーレイヤー表示設定のライフサイクル・フック（F03.19 §10.4・R9 裁定）。
 *
 * <h2>R9 裁定 — 何で消し、何で消さないか</h2>
 * <table>
 *   <tr><th>出来事</th><th>設定行</th><th>理由</th></tr>
 *   <tr><td>チーム／組織を<b>脱退</b>する</td><td><b>残す</b></td>
 *       <td>再加入したときに以前の色で復活するのが望ましい（保存値優先）。
 *           「一度離れて戻ったら色が変わっていた」はユーザーが何もしていないのに状態が変わったことになり
 *           P2（意図しない状態変化の排除）に反する。脱退中は所属していないため
 *           {@code /me/calendar-layers} に現れず実害は無い。
 *           <b>本クラスが脱退系イベント（{@code MembershipEndedEvent} /
 *           {@code TeamMemberRemovedEvent}）を購読していないことが、この裁定の実装そのものである。</b></td></tr>
 *   <tr><td>チーム／組織が<b>削除</b>される</td><td><b>消す</b></td>
 *       <td>二度と復活しない参照先の設定を残す理由が無い</td></tr>
 *   <tr><td>ユーザーが<b>退会</b>する</td><td><b>消す</b></td>
 *       <td>色設定は個人の嗜好情報（PII）であり、匿名化後のユーザーに紐づけて残す意味が無い</td></tr>
 * </table>
 *
 * <h2>なぜアプリ層で消すのか（原則1）</h2>
 * <p>{@code user_calendar_layer_settings.user_id} / {@code scope_id} は<b>クロスドメイン論理参照であり
 * FK を張っていない</b>。加えてチーム削除・組織削除は<b>論理削除</b>であり親行は物理的に残る。
 * よって DB の CASCADE は二重の意味で発火しない（定義が無く、あったとしても親が消えていない）。
 * アプリ層のドメインイベントで整合を取る。</p>
 *
 * <h2>親をロールバックさせない切り離し（設計書 §10.4「同期・ベストエフォート」）</h2>
 * <p>三点セットで親トランザクションから切り離す。いずれも好みではなく必須である。</p>
 * <ul>
 *   <li>{@link TransactionalEventListener}({@code AFTER_COMMIT}) — 親（チーム削除・退会）の
 *       コミットが<b>成立してから</b>動く。親がロールバックすれば本処理は<b>そもそも走らない</b>ので、
 *       「チームは残ったのに色設定だけ消えた」が起きない。</li>
 *   <li>{@link Transactional}({@code REQUIRES_NEW}) — 親とは<b>別の新規トランザクション</b>で消す。
 *       ここでの失敗は自分の TX だけをロールバックし、親には届かない。
 *       素の {@code REQUIRED} は {@code AFTER_COMMIT} フェーズでは既にコミット済みの TX に参加できず
 *       silently 破棄されるため使えない。</li>
 *   <li>try/catch + {@code warn} ログ — 例外を呼び出し元へ伝播させず、同一イベントを購読する
 *       <b>他ドメインのリスナーを巻き添えにしない</b>。</li>
 * </ul>
 * <p><b>これは握りつぶし（{@code catch} して無言）ではない。</b>失敗は必ず
 * スタックトレース付きの {@code warn} で記録され、成功時も削除件数を {@code info} に残すので、
 * 「動いて0件だった」と「落ちて消せなかった」がログ上で区別できる。
 * 孤児行が残っても実害が無く（所属列挙経由でレイヤー一覧に現れない）、
 * §10.1 の 1000 行上限で頭打ちになるため、親の操作を巻き戻してまで整合を取る価値が無い、
 * という設計判断に基づく意図的な切り離しである。</p>
 *
 * <h2>{@code @Transactional} の閉じ方（原則5）</h2>
 * <p>本リスナーは team / organization / auth ドメインのイベントを購読するが、
 * <b>トランザクションは schedule ドメイン内に閉じている</b>。{@code REQUIRES_NEW} により
 * 発行元ドメインの TX とは完全に分離され、本 TX が触るのは
 * {@code user_calendar_layer_settings}（schedule ドメインの表）のみである。
 * すなわち「越境しているのはイベントの購読であって、トランザクションではない」。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalendarLayerLifecycleListener {

    /** {@code scope_type} 値（{@code CalendarLayerService} と同一の文字列体系）。 */
    private static final String SCOPE_TEAM = "TEAM";
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    private final UserCalendarLayerSettingRepository repository;

    /**
     * チーム削除時、そのチームレイヤーの設定行を全ユーザー分削除する（§10.4）。
     *
     * @param event チーム削除イベント
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTeamDeleted(TeamDeletedEvent event) {
        deleteScope(SCOPE_TEAM, event.getTeamId());
    }

    /**
     * 組織削除時、その組織レイヤーの設定行を全ユーザー分削除する（§10.4）。
     *
     * @param event 組織削除イベント
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrganizationDeleted(OrganizationDeletedEvent event) {
        deleteScope(SCOPE_ORGANIZATION, event.getOrganizationId());
    }

    /**
     * 退会（即時匿名化）時、当該ユーザーの設定行を全スコープ分削除する（§10.4）。
     *
     * <p>色設定は個人の嗜好情報（PII）であり復元価値が無いため、30日後の
     * {@code AccountPurgedEvent} ではなく<b>即時</b>の {@link UserAnonymizedEvent} で消す
     * （二層削除モデルの即時側）。</p>
     *
     * @param event 退会即時匿名化イベント
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            int deleted = repository.deleteByUserId(userId);
            log.info("ユーザー退会: カレンダーレイヤー設定を削除しました: userId={}, deleted={}",
                    userId, deleted);
        } catch (Exception ex) {
            log.warn("ユーザー退会に伴うカレンダーレイヤー設定の削除に失敗: userId={}, error={}",
                    userId, ex.getMessage(), ex);
        }
    }

    /**
     * 指定スコープの設定行を全ユーザー分削除する。失敗は warn ログに残して伝播させない。
     */
    private void deleteScope(String scopeType, Long scopeId) {
        try {
            int deleted = repository.deleteByScopeTypeAndScopeId(scopeType, scopeId);
            log.info("スコープ削除: カレンダーレイヤー設定を削除しました: scopeType={}, scopeId={}, deleted={}",
                    scopeType, scopeId, deleted);
        } catch (Exception ex) {
            log.warn("スコープ削除に伴うカレンダーレイヤー設定の削除に失敗: scopeType={}, scopeId={}, error={}",
                    scopeType, scopeId, ex.getMessage(), ex);
        }
    }
}
