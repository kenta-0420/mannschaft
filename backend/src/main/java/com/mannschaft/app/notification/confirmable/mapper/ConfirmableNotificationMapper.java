package com.mannschaft.app.notification.confirmable.mapper;

import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDetailResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationRecipientResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationSettingsResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationTemplateResponse;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationSettingsEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationTemplateEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * F04.9 確認通知システムの Entity → DTO 変換マッパー（MapStruct）。
 *
 * <p><b>confirmedCount について</b>:
 * {@link ConfirmableNotificationResponse} および {@link ConfirmableNotificationDetailResponse} の
 * {@code confirmedCount} は Repository のカウントメソッドを使って Controller 側でセットすること。
 * MapStruct はそのフィールドを無視する（{@code ignore = true}）。</p>
 */
@Mapper(componentModel = "spring")
public interface ConfirmableNotificationMapper {

    /**
     * 確認通知 Entity → 一覧用レスポンスDTO に変換する。
     *
     * <p>confirmedCount は Controller 側で別途セットすること。</p>
     */
    @Mapping(target = "confirmedCount", ignore = true)
    ConfirmableNotificationResponse toResponse(ConfirmableNotificationEntity entity);

    /**
     * 確認通知エンティティリスト → 一覧用レスポンスDTOリストに変換する。
     *
     * <p>confirmedCount は Controller 側で個別にセットすること。</p>
     */
    @Mapping(target = "confirmedCount", ignore = true)
    List<ConfirmableNotificationResponse> toResponseList(List<ConfirmableNotificationEntity> entities);

    /**
     * 確認通知 Entity → 詳細レスポンスDTO に変換する。
     *
     * <p>createdBy は UserEntity の id にマッピングする。
     * confirmedCount は Controller 側で別途セットすること。</p>
     */
    @Mapping(target = "createdBy", source = "createdBy.id")
    @Mapping(target = "confirmedCount", ignore = true)
    ConfirmableNotificationDetailResponse toDetailResponse(ConfirmableNotificationEntity entity);

    /**
     * 確認通知受信者 Entity → レスポンスDTO に変換する。
     */
    @Mapping(target = "userId", source = "user.id")
    ConfirmableNotificationRecipientResponse toRecipientResponse(
            ConfirmableNotificationRecipientEntity entity);

    /**
     * 確認通知受信者エンティティリスト → レスポンスDTOリストに変換する。
     */
    @Mapping(target = "userId", source = "user.id")
    List<ConfirmableNotificationRecipientResponse> toRecipientResponseList(
            List<ConfirmableNotificationRecipientEntity> entities);

    /**
     * 確認通知テンプレート Entity → レスポンスDTO に変換する。
     */
    ConfirmableNotificationTemplateResponse toTemplateResponse(
            ConfirmableNotificationTemplateEntity entity);

    /**
     * 確認通知テンプレートエンティティリスト → レスポンスDTOリストに変換する。
     */
    List<ConfirmableNotificationTemplateResponse> toTemplateResponseList(
            List<ConfirmableNotificationTemplateEntity> entities);

    /**
     * 確認通知設定 Entity → レスポンスDTO に変換する。
     */
    ConfirmableNotificationSettingsResponse toSettingsResponse(
            ConfirmableNotificationSettingsEntity entity);
}
