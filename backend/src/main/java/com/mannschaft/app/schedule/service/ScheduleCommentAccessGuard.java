package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.schedule.ScheduleCommentErrorCode;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.entity.ScheduleCommentEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.visibility.ScheduleCommentViewerFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * F03.16 予定コメントスレッドの<b>認可ゲート</b>（設計書 §2.1 / §4.5.2 / §5.2）。
 *
 * <h2>置き場所の規律 — なぜ Controller から辿れる位置に置くのか</h2>
 * <p>ArchUnit 認可番人（{@code AuthzControllerGuardArchTest}）は Controller のメソッド本体から
 * <b>深さ 2 以内</b>で {@code ContentVisibilityChecker} / {@code AccessControlService} /
 * {@code *AccessGuard} / {@code *AccessService} へ到達することを検査する。
 * <b>委譲先 Service の内部に隠れた認可は拾われない。</b>
 * よって本クラスは {@code *AccessGuard} 命名で Controller／Controller 直下の Service から
 * 直接呼ばれる位置に置く。</p>
 *
 * <p><b>ただし「番人を通すために認可を薄くする」のは本末転倒である。</b>
 * 本クラスが持つのは飾りのシグナルではなく実際の判定であり、番人が要求する位置に
 * <b>本物の判定</b>を置くことでのみ両立させている。</p>
 *
 * <h2>認可の 2 層構造</h2>
 * <ol>
 *   <li><b>閲覧（存在秘匿）</b> — 親予定が見えるか。{@link ContentVisibilityChecker#canView}
 *       単体で判定する（§4.5.0）。見えなければ<b>すべての操作</b>が
 *       {@code 404 SCHEDULE_COMMENT_002} で止まる。書き込み系も必ずここを最初に通し、
 *       「読めない予定にコメントを書ける」経路を作らない（§4.5.2）。</li>
 *   <li><b>操作権限</b> — 閲覧できることを前提とした、コメント固有の権限（投稿・編集・削除・開閉）。</li>
 * </ol>
 *
 * <h2>「死んだ引数」を作らない</h2>
 * <p>各メソッドは受け取った {@code userId} を必ず判定に到達させる。引数として持つだけで
 * 本体で使わないと、番人は合格するのに実際には誰でも通る穴になる
 * （memory {@code feedback_dead_authz_param_deceives_guard}）。</p>
 *
 * <h2>公開入口に置く（共有ヘルパに置かない）</h2>
 * <p>本ゲートはユーザー操作の入口からのみ呼ぶ。内部共有ヘルパやバッチ処理の通り道に置くと、
 * 将来のバッチが巻き添えで 403 になる
 * （memory {@code feedback_authz_gate_on_public_entry_not_shared_method}）。</p>
 */
@Component
@RequiredArgsConstructor
public class ScheduleCommentAccessGuard {

    /** 他者コンテンツ削除権限。TEAM スコープにしか seed されていない（§2.1.2）。 */
    private static final String PERMISSION_DELETE_OTHERS_CONTENT = "DELETE_OTHERS_CONTENT";

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String SCOPE_TYPE_TEAM = "TEAM";

    /**
     * 投稿に必要な最小ロール（§2.1）。
     *
     * <p><b>MEMBER ではなく SUPPORTER</b>である。§2.1 の表は SUPPORTER に投稿・返信を明示的に
     * 許可しており、除外対象は GUEST だけだからである。ここを MEMBER に締めると、
     * 「出欠は答えなくてよいが質問はしたい」保護者・応援者を締め出してしまう（§2.1 の既定に反する）。</p>
     */
    private static final String ROLE_MIN_POSTABLE = "SUPPORTER";

    private final ContentVisibilityChecker contentVisibilityChecker;
    private final AccessControlService accessControlService;

    // ─── 1. 閲覧（全 8 エンドポイントの入口） ────────────────────────

    /**
     * 親予定を閲覧できることを要求する。できなければ <b>404</b>（存在秘匿）。
     *
     * <p>判定は {@code canView} 単体（§4.5.0）。所属・{@code ORGANIZATION} 昇格・
     * {@code CUSTOM_TEMPLATE} の F00 ラダーに加え {@code min_view_role} 閾値まで
     * これ 1 回で評価される（CMP-017b 着地後の {@code ScheduleVisibilityResolver}）。
     * ここで独自の所属判定を書くと閾値の写像が二重化して漏洩源になる。</p>
     *
     * <p><b>個人予定は問答無用で 404</b>（§2.2）。本人が叩いても 404 にするのは
     * 「個人予定の存在自体を伏せる」ためであり、権限不足ではなくスコープ外の表明である。</p>
     *
     * @param userId   操作ユーザー（{@code null} 可＝未認証。{@code canView} 側が fail-closed）
     * @param schedule 親予定（{@code null} 可＝不存在）
     * @throws BusinessException {@code SCHEDULE_COMMENT_002}（404）
     */
    public void requireScheduleViewable(Long userId, ScheduleEntity schedule) {
        if (schedule == null || schedule.getDeletedAt() != null) {
            // 親が論理削除済みでも 404（AC-20）。
            throw new BusinessException(ScheduleCommentErrorCode.SCHEDULE_NOT_VISIBLE);
        }
        if (!schedule.isTeamScope() && !schedule.isOrganizationScope()) {
            // 個人予定はコメント機能のスコープ外（AC-17）。
            throw new BusinessException(ScheduleCommentErrorCode.SCHEDULE_NOT_VISIBLE);
        }
        if (!contentVisibilityChecker.canView(ReferenceType.SCHEDULE, schedule.getId(), userId)) {
            throw new BusinessException(ScheduleCommentErrorCode.SCHEDULE_NOT_VISIBLE);
        }
    }

    /**
     * 親予定を閲覧できるかを例外なしで返す（一覧の可視性分岐など、404 に落とさずに知りたい経路用）。
     *
     * @param userId   操作ユーザー（{@code null} 可）
     * @param schedule 親予定（{@code null} 可）
     * @return 閲覧できるなら true
     */
    public boolean canViewSchedule(Long userId, ScheduleEntity schedule) {
        if (schedule == null || schedule.getDeletedAt() != null) {
            return false;
        }
        if (!schedule.isTeamScope() && !schedule.isOrganizationScope()) {
            return false;
        }
        return contentVisibilityChecker.canView(ReferenceType.SCHEDULE, schedule.getId(), userId);
    }

    // ─── 2. 投稿 ────────────────────────────────────────────────

    /**
     * コメントを投稿・返信できることを要求する（§2.1 / §5.2）。
     *
     * <p>判定順は <b>閲覧 → writable → ロール</b> の順に固定する。順序を入れ替えると
     * 「読めない予定に対して 409 が返る」＝存在が漏れる。</p>
     *
     * <p>ロール要件は「読める人は書ける（GUEST を除く）」（§2.1 既定）。
     * {@code min_response_role}（出欠回答の閾値）には結びつけない — コメントは会話であって
     * 回答ではなく、「出欠は答えないが質問はしたい」保護者・応援者を締め出すため（§2.1）。</p>
     *
     * <h3>投稿の下限は SUPPORTER であって MEMBER ではない</h3>
     * <p>§2.1 の表は <b>SUPPORTER に「投稿・返信・自分のコメントの編集/削除が可能」を明示的に許可</b>
     * しており（親予定を閲覧できる場合＝{@code min_view_role IN (ANYONE, SUPPORTER_PLUS)}）、
     * 同節の根拠文も「読める人は書ける（<b>GUEST を除く</b>）」と、除外対象を GUEST だけに限っている。
     * よって「MEMBER 未満は一律不可」ではなく「<b>GUEST のみ不可</b>」が正である。</p>
     *
     * <h3>⚠️ GUEST は「未ログイン」ではなく実在する最下位の認証済みロールである</h3>
     * <p>設計書 §2.1 の欄外注記は「本書の GUEST は未ログインの閲覧者を指す」と定義しているが、
     * <b>実装上の GUEST はそれとは別に実在する</b>:</p>
     * <ul>
     *   <li>{@code AccessControlService#resolveEffectiveRoleName} の Javadoc は
     *       「user_roles GUEST のみ → {@code "GUEST"}」と明記しており、
     *       <b>認証済みユーザーに対して文字列 {@code "GUEST"} を返しうる</b></li>
     *   <li>ロール序列は {@code ... > MEMBER(4) > SUPPORTER(5) > GUEST(6)} で GUEST は最下位</li>
     *   <li>{@code StandardVisibility} も「SUPPORTER / GUEST は不可視」と GUEST を認証済みロールとして扱う</li>
     * </ul>
     * <p>したがって <b>{@code userId == null} の 401 に頼っても GUEST ロール保持者は捕まらない</b>。
     * 同じ節が「認証済みだが投稿要件を満たさないユーザーは 403 {@code SCHEDULE_COMMENT_004}」と
     * 定めているとおり、ここで明示的に 403 で弾く。</p>
     *
     * <p><b>独自のロール判定を書かない</b>: {@code "GUEST".equals(roleName)} という文字列比較は
     * 序列の写像を増やす行為であり、本戦役が一貫して禁じている形である
     * （ロールが増減したとき、この 1 箇所だけが取り残される）。判定は既存の単一正準
     * {@link RolePriority#isAtLeast} に委ねる。{@code priority(null)} も
     * {@code priority(未登録ロール)} も {@code Integer.MAX_VALUE} なので、
     * <b>ロール無し・並行ロールのみの利用者もこの 1 本の式で fail-closed に倒れる</b>
     * （旧実装の {@code roleName == null} 判定はこの式に吸収された）。</p>
     *
     * @param userId   操作ユーザー（認証済みであること。未認証は Spring Security 側で 401）
     * @param schedule 親予定
     * @throws BusinessException {@code SCHEDULE_COMMENT_002}（404）/ {@code _005}（409）/ {@code _004}（403）
     */
    public void requirePostable(Long userId, ScheduleEntity schedule) {
        requireScheduleViewable(userId, schedule);
        requireWritable(schedule);

        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        String roleName = accessControlService.resolveEffectiveRoleName(
                userId, scopeIdOf(schedule), scopeTypeOf(schedule));
        // SUPPORTER 以上のみ投稿可。GUEST(6) は SUPPORTER(5) 未満なのでここで 403 に落ちる。
        // min_view_role=ANYONE の予定は無所属でも「読める」ため、ここが唯一の書き込み側の関門になる。
        if (!RolePriority.isAtLeast(roleName, ROLE_MIN_POSTABLE)) {
            throw new BusinessException(ScheduleCommentErrorCode.POST_NOT_ALLOWED);
        }
    }

    /**
     * 書き込み可否の<b>単一述語</b>（§5.2）。
     *
     * <pre>writable(schedule) := comments_enabled == TRUE AND status != CANCELLED</pre>
     *
     * <p>false のときは理由が「閉じた」でも「中止」でも<b>常に同一</b>の
     * {@code 409 SCHEDULE_COMMENT_005}。呼び分けない（設計書が「相当」という曖昧表現を
     * 排除して一本化した箇所であり、分岐させると片方だけ実装される温床になる）。</p>
     *
     * <p><b>DELETE はこの述語の対象外</b>（閉じたスレッド・中止された予定でも削除は常に許可）。
     * よって本メソッドは {@link #requirePostable} と編集経路からのみ呼ぶ。</p>
     */
    public void requireWritable(ScheduleEntity schedule) {
        boolean commentsEnabled = Boolean.TRUE.equals(schedule.getCommentsEnabled());
        boolean cancelled = schedule.getStatus() == ScheduleStatus.CANCELLED;
        if (!commentsEnabled || cancelled) {
            throw new BusinessException(ScheduleCommentErrorCode.NOT_WRITABLE);
        }
    }

    // ─── 3. 編集（本人のみ） ─────────────────────────────────────

    /**
     * コメント本文を編集できることを要求する（§4.4 / AC-14）。
     *
     * <p><b>ADMIN であっても他者コメントの本文編集は不可</b>。モデレーションは削除であって
     * 改竄ではない — 他人の発言を書き換えられる権限は誰にも与えない。</p>
     *
     * @throws BusinessException {@code SCHEDULE_COMMENT_002}（404）/ {@code _005}（409）/ {@code _009}（403）
     */
    public void requireEditable(Long userId, ScheduleEntity schedule, ScheduleCommentEntity comment) {
        requireScheduleViewable(userId, schedule);
        requireWritable(schedule);
        if (userId == null || comment.getUserId() == null || !userId.equals(comment.getUserId())) {
            throw new BusinessException(ScheduleCommentErrorCode.EDIT_NOT_OWNER);
        }
    }

    // ─── 4. 削除（本人 / モデレーター / 予定作成者） ───────────────

    /**
     * コメントを削除できることを要求する（§2.1 / §2.1.2）。
     *
     * <p>許可されるのは次のいずれか:</p>
     * <ol>
     *   <li>投稿者本人</li>
     *   <li>{@code SYSTEM_ADMIN}</li>
     *   <li>当該スコープの {@code ADMIN}</li>
     *   <li>予定の作成者（{@code schedules.created_by}）</li>
     *   <li>TEAM 予定に限り {@code DELETE_OTHERS_CONTENT} 権限保持者</li>
     * </ol>
     *
     * <p><b>組織予定でのフォールバック規則</b>（§2.1.2）: {@code DELETE_OTHERS_CONTENT} は
     * {@code V2.015__seed_permissions.sql} で <b>{@code scope = 'TEAM'} としてのみ</b> seed されており、
     * ORGANIZATION スコープの行が存在しない。よって組織予定に対して
     * {@code hasPermission(..., "ORGANIZATION", "DELETE_OTHERS_CONTENT")} を問い合わせると
     * <b>必ず false</b> ＝ 組織予定のコメントを誰もモデレートできないという詰みになる。
     * 権限テーブルへ ORGANIZATION 行を足すのはコメント機能のスコープを超える権限モデルの変更なので採らず、
     * <b>組織 ADMIN であることを条件に</b>埋める（上記 3. が実質そのフォールバックである）。</p>
     *
     * <p>DELETE は {@link #requireWritable} の対象外である（§5.2 — 閉じたスレッド・中止された
     * 予定でも削除は 204 で成功する）。ここで writable を呼んでいないのは書き忘れではない。</p>
     *
     * @throws BusinessException {@code SCHEDULE_COMMENT_002}（404）/ {@code _010}（403）
     */
    public void requireDeletable(Long userId, ScheduleEntity schedule, ScheduleCommentEntity comment) {
        requireScheduleViewable(userId, schedule);

        if (userId != null && userId.equals(comment.getUserId())) {
            return;
        }
        if (isModerator(userId, schedule)) {
            return;
        }
        // TEAM 予定に限り権限テーブルを引く（ORGANIZATION には行が無いので引かない）。
        if (SCOPE_TYPE_TEAM.equals(scopeTypeOf(schedule))
                && accessControlService.hasPermission(
                        userId, scopeIdOf(schedule), SCOPE_TYPE_TEAM, PERMISSION_DELETE_OTHERS_CONTENT)) {
            return;
        }
        throw new BusinessException(ScheduleCommentErrorCode.DELETE_NOT_ALLOWED);
    }

    // ─── 5. スレッド開閉 ────────────────────────────────────────

    /**
     * スレッドの開閉（{@code comments_enabled}）を変更できることを要求する（§2.1.1）。
     *
     * <p>許可されるのは <b>SYSTEM_ADMIN / 当該スコープの ADMIN / 予定の作成者</b> の 3 者のみ。</p>
     *
     * <p><b>{@code MANAGE_SCHEDULES} を条件に使わない</b>【御裁可済・§2.1.1】:
     * {@code V2.016__seed_role_permissions.sql} は MEMBER × {@code MANAGE_SCHEDULES} を
     * {@code is_default = 1}（既定付与）で seed している。これを条件にすると
     * <b>一般 MEMBER が誰の予定のスレッドでも閉じられる</b>ことになり、荒れた議論を当事者が
     * 一方的に封じる運用事故につながる。{@code DELETE_OTHERS_CONTENT} も条件に使わない。
     * 判定は「ロール ＋ 作成者一致」のみで、権限テーブルを一切引かない。</p>
     *
     * <p>組織予定では上記 ADMIN 判定が組織 ADMIN として評価される（§2.1.2 のフォールバックと同じ考え方）。</p>
     *
     * @throws BusinessException {@code SCHEDULE_COMMENT_002}（404）/ {@code _011}（403）
     */
    public void requireThreadSettingsManageable(Long userId, ScheduleEntity schedule) {
        requireScheduleViewable(userId, schedule);
        if (!isModerator(userId, schedule)) {
            throw new BusinessException(ScheduleCommentErrorCode.THREAD_SETTINGS_NOT_ALLOWED);
        }
    }

    // ─── 内部ヘルパ ────────────────────────────────────────────

    /**
     * SYSTEM_ADMIN／当該スコープの ADMIN／予定作成者のいずれかであるかを返す。
     *
     * <p>削除（§2.1.2）と開閉（§2.1.1）が共有する 3 者判定。設計書 §2.1.2 が
     * 「判定は必ず<b>単一のヘルパ</b>に集約し、Controller/Service の各所で条件を書き分けない」と
     * 定めているため、条件をここ以外に複製しないこと（分岐が散ると片方だけ直る事故になる）。</p>
     */
    private boolean isModerator(Long userId, ScheduleEntity schedule) {
        if (userId == null) {
            return false;
        }
        if (userId.equals(schedule.getCreatedBy())) {
            return true;
        }
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        String scopeType = scopeTypeOf(schedule);
        Long scopeId = scopeIdOf(schedule);
        if (scopeType == null || scopeId == null) {
            return false;
        }
        return ROLE_ADMIN.equals(accessControlService.resolveEffectiveRoleName(userId, scopeId, scopeType));
    }

    private String scopeTypeOf(ScheduleEntity schedule) {
        return ScheduleCommentViewerFilter.scopeTypeOf(schedule);
    }

    private Long scopeIdOf(ScheduleEntity schedule) {
        return ScheduleCommentViewerFilter.scopeIdOf(schedule);
    }
}
