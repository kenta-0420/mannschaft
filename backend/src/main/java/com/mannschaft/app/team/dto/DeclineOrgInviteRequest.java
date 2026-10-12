package com.mannschaft.app.team.dto;

/**
 * 組織からの加盟招待を辞退するリクエスト（F01.2.1 §6.5）。
 *
 * @param block true なら、この組織からの招待を期限なしで止める（既定 false＝30日の冷却）
 */
public record DeclineOrgInviteRequest(Boolean block) {
}
