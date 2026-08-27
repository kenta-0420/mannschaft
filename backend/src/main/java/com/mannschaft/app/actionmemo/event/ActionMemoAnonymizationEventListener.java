package com.mannschaft.app.actionmemo.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagRepository;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * actionmemo ドメインの退会データ削除リスナー（クロスドメインFK撤廃キャンペーン 第二陣D）。
 *
 * <p>users を親とする ON DELETE CASCADE のクロスドメインFK
 * （{@code fk_action_memos_user} / {@code fk_action_memo_tags_user} /
 * {@code fk_user_action_memo_settings_user}）を V99.001 で撤廃するにあたり、
 * 退会フローでリスナーが先行削除することで CASCADE を冗長化する
 * （第一陣 notification・第二陣 pointcard / search と同じ論法）。</p>
 *
 * <p><b>二層削除モデル（CLAUDE.md「PII 消去のタイミング §13.12」）:</b>
 * <ul>
 *   <li><b>行動メモ本体（action_memos）= 退会時即時削除</b> —
 *       {@code content}（メモ本文）・{@code mood}・{@code memo_date} は「ユーザーが何をしたか」を
 *       表す行動ログ＝個人の内容（PII）であり、再設定で復旧する性質のものでもない。
 *       漏洩リスクを最小化するため {@link UserAnonymizedEvent}（退会受付直後・即時消去）を
 *       購読して削除する。</li>
 *   <li><b>タグマスタ（action_memo_tags）= 30日後の物理削除時削除</b> —
 *       タグは「ユーザーが意図的に作成した分類設定」＝個人設定であり、退会撤回時に復元価値がある。
 *       GDPR Art.17 の30日撤回ウィンドウを保持するため {@link AccountPurgedEvent}
 *       （30日後の物理削除完了）を購読して削除する。</li>
 *   <li><b>ユーザー設定（user_action_memo_settings）= 30日後の物理削除時削除</b> —
 *       mood 入力 ON/OFF・デフォルト投稿先・リマインド設定の 1:1 個人設定で、退会撤回時に
 *       復元価値がある（PK=user_id）。{@link AccountPurgedEvent} を購読して削除する。</li>
 * </ul>
 * </p>
 *
 * <p><b>同一ドメイン内 FK の削除順（即時削除の整合性）:</b>
 * action_memos を親とする actionmemo ドメイン内の子テーブル {@code action_memo_tag_links} は
 * {@code fk_amtl_memo}（memo_id → action_memos ON DELETE CASCADE）/
 * {@code fk_amtl_tag}（tag_id → action_memo_tags ON DELETE CASCADE）の両方を持つ。
 * 即時削除で action_memos を消した時点で、link 行は memo_id 経由の同一ドメイン CASCADE により
 * 自動削除される（子→親の手動順序削除は不要）。よって30日後に action_memo_tags を消す時点では
 * link は既に空＝tag_id 経由 CASCADE は冗長で、即時/30日分割によるダングリング中間行は発生しない。</p>
 *
 * <p><b>論理削除済み行も完全に消す:</b>
 * action_memos / action_memo_tags は {@code @SQLRestriction("deleted_at IS NULL")} を持つため、
 * 各 Repository の {@code deleteAllByUserIdIncludingDeleted}（native DELETE）を用いて
 * 論理削除済み行も含めて物理削除する（派生クエリだと消し残しが発生し GDPR 物理削除として不完全）。</p>
 *
 * <p><b>三重防御パターン（過去の ApplicationContext 全滅事故の再発防止）:</b>
 * <ul>
 *   <li>{@code @Async(...)} — 呼び出し元 TX とスレッド分離（即時=event-pool / 30日=purge-pool）</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — 呼出元コミット成立後のみ実行</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — 独立した新規 TX。
 *       素の {@code REQUIRED} は AFTER_COMMIT では起動時バリデーションで弾かれるか
 *       silently 破棄されるため必須。</li>
 * </ul>
 * </p>
 *
 * <p>例外は WARN ログのみで伝播させない（他ドメインリスナーの処理を妨げない／
 * GDPR タイムリミットを優先する）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionMemoAnonymizationEventListener {

    private final ActionMemoRepository actionMemoRepository;
    private final ActionMemoTagRepository actionMemoTagRepository;
    private final UserActionMemoSettingsRepository userActionMemoSettingsRepository;

    /**
     * 退会即時匿名化（{@link UserAnonymizedEvent}）を購読し、行動メモ本体（行動ログ＝PII）を即時削除する。
     *
     * <p>action_memos の削除により、actionmemo ドメイン内の子テーブル action_memo_tag_links は
     * memo_id CASCADE で自動削除される。</p>
     *
     * @param event 退会即時匿名化イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会・完全削除済み利用者の個人情報がアクションメモ側に残存し、退会済みなのに PII が残るという不整合になる")
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            int deleted = actionMemoRepository.deleteAllByUserIdIncludingDeleted(userId);
            log.info("ユーザー退会: 行動メモ（行動ログ＝PII）を即時削除完了: userId={}, deleted={}",
                    userId, deleted);
        } catch (Exception e) {
            log.warn("ユーザー退会: 行動メモの即時削除失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }

    /**
     * 退会30日後の物理削除（{@link AccountPurgedEvent}）を購読し、
     * タグマスタ・ユーザー設定（個人設定・復元価値）を削除する。
     *
     * @param event アカウント物理削除完了イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会・完全削除済み利用者の個人情報がアクションメモ側に残存し、退会済みなのに PII が残るという不整合になる")
    @Async("purge-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        try {
            int deletedTags = actionMemoTagRepository.deleteAllByUserIdIncludingDeleted(userId);
            int deletedSettings = userActionMemoSettingsRepository.deleteByUserId(userId);
            log.info("ユーザー退会 actionmemo purge 完了: タグ・設定削除: userId={}, "
                    + "deletedTags={}, deletedSettings={}", userId, deletedTags, deletedSettings);
        } catch (Exception e) {
            log.warn("ユーザー退会 actionmemo purge: タグ・設定削除失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }
}
