package com.mannschaft.app.corkboard;

import com.mannschaft.app.corkboard.dto.CorkboardCardResponse;
import com.mannschaft.app.corkboard.dto.CorkboardDetailResponse;
import com.mannschaft.app.corkboard.dto.CorkboardGroupResponse;
import com.mannschaft.app.corkboard.dto.CorkboardResponse;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardGroupEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * コルクボード機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface CorkboardMapper {

    CorkboardResponse toBoardResponse(CorkboardEntity entity);

    List<CorkboardResponse> toBoardResponseList(List<CorkboardEntity> entities);

    @Mapping(target = "zIndex", expression = "java(entity.getZIndex())")
    CorkboardCardResponse toCardResponse(CorkboardCardEntity entity);

    List<CorkboardCardResponse> toCardResponseList(List<CorkboardCardEntity> entities);

    CorkboardGroupResponse toGroupResponse(CorkboardGroupEntity entity);

    List<CorkboardGroupResponse> toGroupResponseList(List<CorkboardGroupEntity> entities);

    /**
     * ボード詳細レスポンスを組み立てる。
     *
     * @param entity        ボードエンティティ
     * @param cards         アクティブカード一覧
     * @param groups        セクション一覧
     * @param viewerCanEdit 閲覧ユーザーがこのボードを編集可能か
     *                      （F09.8 件A: フロントの編集ボタン disabled 制御用）
     */
    @Mapping(target = "cards", source = "cards")
    @Mapping(target = "groups", source = "groups")
    default CorkboardDetailResponse toDetailResponse(CorkboardEntity entity,
                                                      List<CorkboardCardEntity> cards,
                                                      List<CorkboardGroupEntity> groups,
                                                      boolean viewerCanEdit) {
        return new CorkboardDetailResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getOwnerId(),
                entity.getName(),
                entity.getBackgroundStyle(),
                entity.getEditPolicy(),
                entity.getIsDefault(),
                entity.getVersion(),
                toCardResponseList(cards),
                toGroupResponseList(groups),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                viewerCanEdit
        );
    }
}
