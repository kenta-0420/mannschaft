package com.mannschaft.app.effortrecord.service;

import java.util.List;

/**
 * F06.4.1 TEAM 定義の組み立てに使う activity テンプレートの写し（effort ドメイン内の値）。
 *
 * <p>非 TX の {@link EffortTeamDefinitionResolver} が activity Service の応答から作り、TX 本体と組み立て関数へ渡す
 * （TX 内から他ドメインの型・Service へ依存しない）。</p>
 *
 * @param id     既存 activity テンプレート ID（BIGINT）
 * @param name   テンプレート名
 * @param fields 項目（activity の並び順は組み立て時に sortOrder, id で確定する）
 */
public record EffortTeamTemplate(Long id, String name, List<Field> fields) {

    /** 1 項目の写し。type は activity の型名（TEXTAREA を含む）。 */
    public record Field(Long id, String label, String type, boolean required, String optionsJson, Integer sortOrder) {
    }
}
