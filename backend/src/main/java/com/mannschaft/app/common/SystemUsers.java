package com.mannschaft.app.common;

/**
 * ユーザー操作を伴わない自動処理が {@code users} を参照するときに用いる、
 * シードで作られた特別なユーザー ID。
 *
 * <p>本プロジェクトには以前から {@code V1.012__seed_system_user.sql} が投入する
 * システムユーザー（id=1）と、退会センチネル（id=0）が存在するが、
 * <b>定数として一元化されていなかった</b>。そのため
 * {@code AdCampaignStateTransitionScheduler} はクラス private の {@code SYSTEM_USER_ID = 1L} を
 * 自前で持ち、{@code PropertyWorkPackageEventListener} は Javadoc に
 * 「専用システムユーザー ID 定数は確認できなかった」と書いて代替策を取っている。
 * 本クラスはその重複と欠落を埋める唯一の宣言地点である。</p>
 *
 * <h2>なぜ実在の運営者個人を使わないか</h2>
 * <ul>
 *   <li><b>監査で人／自動を一目で区別できる</b>。{@code issued_by = 1} なら自動発行、
 *       それ以外なら人が発行した、と判定が 1 列で済む。</li>
 *   <li><b>実在の運営者個人の名前が法的文書に残らない</b>。「ID 最小の SYSTEM_ADMIN を
 *       代表とする」ような方式だと、実際には操作していない人物が発行者として
 *       記録され続けてしまう。</li>
 * </ul>
 *
 * <h2>退会・匿名化バッチとの関係</h2>
 * <p>退会・匿名化・物理削除の各バッチは <b>{@code deleted_at} が入った行だけ</b>を対象に走る
 * （{@code UserRepository#findPurgeTargets} / {@code #findPendingDeletionUsers}）。
 * システムユーザーは退会経路を持たず（{@code password_hash} が NULL でログインできない）、
 * {@code deleted_at} が立つことがないため、<b>これらのバッチの対象に入らない</b>。
 * 追加の除外条件は不要である。逆に言えば、将来この行に {@code deleted_at} を立てる経路を
 * 作ってはならない。立てた瞬間に過去の領収書の {@code issued_by} 参照が匿名化される。</p>
 *
 * <p>PII は暗号化導入（V9.053）より前のシードであるため<b>平文のまま</b>格納されている。
 * {@code EncryptionService} はこの形を「暗号化前のレガシー平文」と判定してそのまま返すため、
 * 読み取り経路は壊れない。</p>
 */
public final class SystemUsers {

    /**
     * システムユーザー（{@code system@mannschaft.local}）。
     *
     * <p>ユーザー操作を伴わない自動処理の actor / 作成者 / 発行者として用いる。
     * F08.12 の運営領収書は、入金確定イベントを契機に自動発行されるため
     * {@code receipts.issued_by} にこの ID を記録する。</p>
     */
    public static final Long SYSTEM_USER_ID = 1L;

    /**
     * 退会センチネル。退会済みユーザーの参照先を寄せるための行であり、
     * 物理削除バッチからは明示的に除外されている（{@code id != 0}）。
     */
    public static final Long WITHDRAWN_SENTINEL_USER_ID = 0L;

    private SystemUsers() {
    }
}
