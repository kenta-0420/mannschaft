package com.mannschaft.app.bulletin.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

/**
 * 保管庫フォルダレスポンスDTO（設計書 F05.1 §4）。
 *
 * <p>ツリー構造で返す際は {@code children} に子フォルダを再帰的にネストする。
 * 単一フォルダの作成・更新レスポンスでは {@code children} は空リストで返す。</p>
 */
@Getter
@Builder
public class ArchiveFolderResponse {

    private final UUID id;

    /** 親フォルダ ID（NULL = ルート）。 */
    private final UUID parentId;

    private final String name;

    private final String color;

    private final String icon;

    private final Integer depth;

    private final Integer displayOrder;

    /** 直下の子フォルダ数。 */
    private final Integer childCount;

    /** このフォルダ直下に所属するアーカイブ済みスレッド数。 */
    private final Integer threadCount;

    /** 子フォルダ（ツリー構造）。単一レスポンスでは空リスト。 */
    @Builder.Default
    private final List<ArchiveFolderResponse> children = List.of();
}
