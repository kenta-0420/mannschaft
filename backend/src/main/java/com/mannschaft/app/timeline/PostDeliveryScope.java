package com.mannschaft.app.timeline;

/**
 * 組織投稿の「配下配信」範囲。
 *
 * <p>ORGANIZATION スコープの投稿を、直接所属者だけでなく配下組織（およびそこに紐づくチーム）の
 * 所属者へも届けるための配信指定。チームには階層が存在しない（{@code parent_team_id} は全体で 0 件）ため、
 * <b>配下配信が実効を持つのは ORGANIZATION スコープの投稿のみ</b>である。
 * TEAM / PUBLIC / PERSONAL / VILLAGE スコープの投稿に本値を指定しても配信範囲は変わらない。</p>
 *
 * <p><b>閲覧者から見た距離</b>で規則を述べると次のとおり（距離＝閲覧者の起点組織から投稿元組織まで
 * 親方向に何ホップか。起点＝閲覧者の所属組織、およびチームのみ所属の場合はそのチームのアンカー組織を
 * 距離 1 とみなす）:</p>
 * <ul>
 *   <li>{@link #DIRECT} — 距離 0（直接所属）のみ。現行挙動と同一の既定値。</li>
 *   <li>{@link #CHILDREN} — 距離 0 に加えて距離 1 まで（直下の子組織まで）。</li>
 *   <li>{@link #DESCENDANTS} — 距離 0 に加えて距離 1 以上すべて（配下すべて）。
 *       ただし {@code app.org.max-depth} を超える深さには届かない。</li>
 * </ul>
 *
 * <p><b>配信は入場権ではない</b>: 配下配信を受け取ったユーザーが上位組織のタイムライン画面
 * （{@code GET /timeline/feed?scopeType=ORGANIZATION}）を開けるようにはならない。
 * あくまで「自分のフィードに流れてくる」および「その投稿個別に到達できる」までである。</p>
 */
public enum PostDeliveryScope {

    /** 直接所属者のみ（既定・現行挙動）。 */
    DIRECT,

    /** 直下の子組織まで配信する。 */
    CHILDREN,

    /** 配下すべて（子孫組織すべて）へ配信する。 */
    DESCENDANTS
}
