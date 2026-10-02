package com.mannschaft.app.notification.confirmable.entity;

/**
 * CMP-260920-1040: 確認通知の宛先ターゲット種別（軍議第8版確定稿 §3.1）。
 *
 * <p>{@code ORGANIZATION(id)} は、その組織を頂点とする配下全体
 * （子孫組織と、それらに所属する ACTIVE チーム）を表す。
 * {@code TEAM(id)} は、そのチームだけを表す。</p>
 */
public enum ConfirmableTargetType {

    /** 組織（配下全体を表す） */
    ORGANIZATION,

    /** チーム単体 */
    TEAM
}
