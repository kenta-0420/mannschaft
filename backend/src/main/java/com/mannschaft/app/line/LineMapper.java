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
import org.mapstruct.Named;

/**
 * LINE/SNS機能のMapStructマッパー。
 */
@Mapper(componentModel = "spring")
public interface LineMapper {

    /**
     * LINE BOT設定エンティティからレスポンスに変換する。
     *
     * <p>webhookSecret はシークレット平文露出是正（認可根治戦役 Wave2 トランシェ2C）のため
     * {@link #maskSecret(String)} でprefixマスクして返す。生値はクライアントが作成/更新リクエストで
     * 自ら送信した値のみであり、応答で平文をエコーバックする必要はない
     * （先行 #2259 webhook ドメインの listTokens マスク化と同方針）。</p>
     */
    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "webhookSecret", expression = "java(maskSecret(entity.getWebhookSecret()))")
    LineBotConfigResponse toLineBotConfigResponse(LineBotConfigEntity entity);

    /**
     * シークレット文字列をマスクする。先頭8文字+末尾4文字のみ表示し、中間は "****" に置換する。
     * 短いシークレット（12文字未満）は全体をマスクする（#2259 webhook ドメインの maskToken と同仕様）。
     *
     * <p>{@code @Named} 必須（過剰マスクバグの根治・CI #2295 で実測発覚）: このマッパーの中で
     * {@code String -> String} 型の変換メソッドがこれ1つしかないため、{@code @Named} を付けずに
     * default メソッドとして置くと、MapStruct が channelId／botUserId／accountUsername など
     * 明示 {@code @Mapping} を書いていない他の {@code String} プロパティにも「唯一マッチする変換候補」
     * として本メソッドを暗黙的に適用してしまう（MapStruct の implicit method selection の既知の罠）。
     * {@code @Named} を付けることで暗黙適用の候補から除外し、{@link #toLineBotConfigResponse}
     * の {@code expression} 経由での明示呼び出しにのみ限定する。</p>
     */
    @Named("maskSecret")
    default String maskSecret(String secret) {
        if (secret == null) {
            return null;
        }
        if (secret.length() < 12) {
            return "*".repeat(secret.length());
        }
        return secret.substring(0, 8) + "****" + secret.substring(secret.length() - 4);
    }

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
