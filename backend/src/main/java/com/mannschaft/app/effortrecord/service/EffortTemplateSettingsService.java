package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.effortrecord.dto.EffortTemplateSettingsResponse;
import com.mannschaft.app.effortrecord.dto.UpdateEffortTemplateSettingsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * F06.4.1 チームテンプレートの「個人の頑張り記録の指標」設定（API 契約 §7、AC-21/22）。非 TX の入口。
 *
 * <ul>
 *   <li>認可は既存 TEAM テンプレート管理権限（ADMIN / DEPUTY_ADMIN）＋ template の scope 一致。
 *       MEMBER・非所属・SYSTEM_ADMIN（非所属）は 403 COMMON_002（template の存否より先に判定し、存在を漏らさない）、
 *       path の team に属さない template（別チーム・組織スコープ・不在）は 404 EFFORT_001。親組織は判定に使わない。</li>
 *   <li>保存先は effort_definition_revisions.definition_json。activity テンプレートの既存列へ属性を足さない。</li>
 *   <li>全 field の設定必須。未知 key / 重複 / 不互換 semantic / duration・subject 二重指定は 400、version 競合 409。
 *       semantic・unit・型の変更は新しい fieldKey（label 変更だけは同じ key）。min/max/scale はサーバが決める。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EffortTemplateSettingsService {

    private final EffortTeamAccessGuard teamAccess;
    private final EffortTeamDefinitionResolver teamDefinitions;

    /** 現在の指標設定を返す（activity の現定義へ同期した現行 revision）。 */
    public EffortTemplateSettingsResponse get(Long userId, Long teamId, Long templateId) {
        teamAccess.requireTemplateManager(userId, teamId);
        EffortTeamDefinitionTxService.Current current = teamDefinitions.sync(teamId, templateId);
        return new EffortTemplateSettingsResponse(current.version(), current.snapshot());
    }

    /** 指標設定を全置換で更新する。 */
    public EffortTemplateSettingsResponse update(Long userId, Long teamId, Long templateId,
                                                 UpdateEffortTemplateSettingsRequest request) {
        teamAccess.requireTemplateManager(userId, teamId);
        EffortTeamDefinitionTxService.Current current = teamDefinitions.updateSettings(teamId, templateId, request);
        return new EffortTemplateSettingsResponse(current.version(), current.snapshot());
    }
}
