package com.mannschaft.app.effortrecord.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;

/**
 * F06.4.1 チーム定義 snapshot 登録リクエスト（API 契約 §3 POST /effort-records/me/definition-snapshots）。
 * teamId / teamTemplateId は既存 BIGINT。所属・template の scope 一致はサーバが検証する。
 */
public record CreateDefinitionSnapshotRequest(
        @NotNull Long teamId,
        @NotNull Long teamTemplateId) {

    /** 契約にない JSON プロパティは 400（API 契約 §1）。値は例外メッセージへ載せない。 */
    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("許可されていない入力項目です");
    }
}
