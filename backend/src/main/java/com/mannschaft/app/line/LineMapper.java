package com.mannschaft.app.line;

import com.mannschaft.app.line.dto.LineBotConfigResponse;
import com.mannschaft.app.line.dto.LineMessageLogResponse;
import com.mannschaft.app.line.dto.SnsFeedConfigResponse;
import com.mannschaft.app.line.dto.UserLineStatusResponse;
import com.mannschaft.app.line.entity.LineBotConfigEntity;
import com.mannschaft.app.line.entity.LineMessageLogEntity;
import com.mannschaft.app.line.entity.SnsFeedConfigEntity;
import com.mannschaft.app.line.entity.UserLineConnectionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * LINE/SNS機能のMapStructマッパー。
 */
@Mapper(componentModel = "spring")
public interface LineMapper {

    /**
     * LINE BOT設定エンティティからレスポンスに変換する。
     */
    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    LineBotConfigResponse toLineBotConfigResponse(LineBotConfigEntity entity);

    /**
     * メッセージログエンティティからレスポンスに変換する。
     */
    @Mapping(target = "direction", expression = "java(entity.getDirection().name())")
    @Mapping(target = "messageType", expression = "java(entity.getMessageType().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    LineMessageLogResponse toLineMessageLogResponse(LineMessageLogEntity entity);

    /**
     * ユーザーLINE連携エンティティからステータスレスポンスに変換する。
     */
    @Mapping(target = "isLinked", constant = "true")
    UserLineStatusResponse toUserLineStatusResponse(UserLineConnectionEntity entity);

    /**
     * SNSフィード設定エンティティからレスポンスに変換する。
     */
    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "provider", expression = "java(entity.getProvider().name())")
    SnsFeedConfigResponse toSnsFeedConfigResponse(SnsFeedConfigEntity entity);
}
