package com.mannschaft.app.village.dto;

import java.util.UUID;

/**
 * 村招待の受諾応答（骨格）。受諾に成功した場合にのみ村の情報を明かす。
 *
 * <p>本クラスは試練（テスト先行）が参照するための最小スタブである。</p>
 */
public record VillageInvitationAcceptResponse(
        UUID villageId,
        String villageName,
        UUID membershipId) {
}
