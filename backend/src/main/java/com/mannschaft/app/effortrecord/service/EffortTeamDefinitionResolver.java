package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.dto.ActivityTemplateResponse;
import com.mannschaft.app.activity.service.ActivityTemplateService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.effortrecord.EffortErrorCode;
import com.mannschaft.app.effortrecord.dto.UpdateEffortTemplateSettingsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * F06.4.1 TEAM 定義の非 TX Facade（データ設計 §1「snapshot revision の作成は activity Service から認可済み定義を取得し、
 * 非 TX Facade から effort definition Service へ渡す。activity の更新 TX を共有しない」）。
 *
 * <p><b>認可は呼び出し側</b>（{@link EffortTeamAccessGuard}）で済ませてから呼ぶこと。本クラスは path の team に
 * 属する有効な TEAM テンプレートかだけを判定する: 別チーム・組織スコープ・不在は区別せず {@code EFFORT_001}（404）、
 * 既知の定義のテンプレートが廃止（論理削除）されていれば {@code EFFORT_002}（409）。親組織は判定に使わない。</p>
 */
@Service
@RequiredArgsConstructor
public class EffortTeamDefinitionResolver {

    private final ActivityTemplateService activityTemplates;
    private final EffortTeamDefinitionTxService tx;

    /** activity の現定義へ同期した現行 TEAM 定義（初回利用時は作成する）。 */
    public EffortTeamDefinitionTxService.Current sync(Long teamId, Long templateId) {
        EffortTeamTemplate template = template(teamId, templateId);
        return tx.sync(teamId, templateId, loader(teamId, templateId)).orElseGet(() -> {
            createOnce(teamId, template);
            return tx.sync(teamId, templateId, loader(teamId, templateId))
                    .orElseThrow(() -> new IllegalStateException("チーム定義を作成できませんでした"));
        });
    }

    /** 指標設定を全置換で更新する（定義が未作成なら先に作成する）。 */
    public EffortTeamDefinitionTxService.Current updateSettings(Long teamId, Long templateId,
                                                               UpdateEffortTemplateSettingsRequest request) {
        EffortTeamTemplate template = template(teamId, templateId);
        if (!tx.exists(teamId, templateId)) {
            createOnce(teamId, template);
        }
        return tx.updateSettings(teamId, templateId, loader(teamId, templateId), request);
    }

    /**
     * 定義行の行ロック取得後に呼ばれる activity の現定義の読み出し。ロック前に読んだ写し（{@link #template}）は、
     * 並行する別要求が新しい revision を確定した後では古いため、同期の入力には使わない（巻き戻し防止）。
     * activity には版の列が無く migration も足さないので、「ロック取得後に読み直す」ことで新旧を保証する。
     */
    private EffortTeamDefinitionTxService.TemplateLoader loader(Long teamId, Long templateId) {
        return () -> activityTemplates.findInScope(templateId, ActivityScopeType.TEAM, teamId)
                .map(EffortTeamDefinitionResolver::toTemplate);
    }

    private EffortTeamTemplate template(Long teamId, Long templateId) {
        return activityTemplates.findInScope(templateId, ActivityScopeType.TEAM, teamId)
                .map(EffortTeamDefinitionResolver::toTemplate)
                .orElseThrow(() -> tx.markRetired(teamId, templateId)
                        ? new BusinessException(EffortErrorCode.EFFORT_002)
                        : new BusinessException(EffortErrorCode.EFFORT_001));
    }

    /** activity の応答を effort ドメイン内の値へ写す（TX 本体へ他ドメインの型を渡さない）。 */
    private static EffortTeamTemplate toTemplate(ActivityTemplateResponse response) {
        List<EffortTeamTemplate.Field> fields = new ArrayList<>(response.getFields().size());
        response.getFields().forEach(f -> fields.add(new EffortTeamTemplate.Field(f.getId(), f.getFieldLabel(),
                f.getFieldType(), Boolean.TRUE.equals(f.getIsRequired()), f.getOptionsJson(), f.getSortOrder())));
        return new EffortTeamTemplate(response.getId(), response.getName(), List.copyOf(fields));
    }

    /**
     * 初回利用時の一意作成。同時に作成した別要求が先に確定した場合は一意制約違反になるが、
     * そのときは定義行が実在することを再確認して収束させる（実在しなければ原因不明の違反として再送出する）。
     */
    private void createOnce(Long teamId, EffortTeamTemplate template) {
        try {
            tx.create(teamId, template);
        } catch (DataIntegrityViolationException e) {
            if (!tx.exists(teamId, template.id())) {
                throw e;
            }
        }
    }
}
