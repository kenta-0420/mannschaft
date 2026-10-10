package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 告知範囲テンプレートを書き込めるかの認可（F02.8 / F01.2.1 6-B）。
 *
 * <p>{@link AnnouncementRangeTemplateService} はクラス単位で {@code @Transactional} のため、そこへ認可メソッドを
 * 置くと全メソッドが D-3T（推移的クロスドメイン {@code @Transactional}）の入口になり、
 * {@code AccessControlService} 経由で role ドメインの Repository に届く。認可は何も書かないので、
 * トランザクション宣言を持たない本クラスに分離する（コントローラーからも、トランザクションの外で呼べる）。</p>
 */
@Component
@RequiredArgsConstructor
public class AnnouncementRangeTemplateAuthorizer {

    private final AccessControlService accessControlService;

    /** グループ項目つきのテンプレートを保存する DEPUTY_ADMIN に要る権限。 */
    private static final String GROUP_ITEMS_PERMISSION = "MANAGE_CONTENT";

    /**
     * テンプレートを書き込める人か確かめる（作成・更新の前提。何も書かない）。
     *
     * <p>従来どおり ADMIN / DEPUTY_ADMIN。グループ項目つきの保存だけは、さらに ADMIN か MANAGE_CONTENT を持つ
     * DEPUTY_ADMIN に絞る（F01.2.1 §12。宛先を絞った告知の push 権限と同じ線）。グループ項目は組織スコープのみで、
     * チームスコープでは 400 {@code BROADCAST_012}。</p>
     *
     * @param hasGroupItems リクエストにグループ項目（個別・範囲・未分類）が含まれるか
     */
    public void checkWritable(String scopeType, Long scopeId, Long callerUserId, boolean hasGroupItems) {
        if (!accessControlService.isAdminOrAbove(callerUserId, scopeId, scopeType)) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_009);
        }
        if (!hasGroupItems) {
            return;
        }
        if (!"ORGANIZATION".equals(scopeType)) {
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_012);
        }
        if (!accessControlService.hasAdminOrPermissionInScope(
                callerUserId, scopeId, scopeType, GROUP_ITEMS_PERMISSION)) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_009);
        }
    }
}
