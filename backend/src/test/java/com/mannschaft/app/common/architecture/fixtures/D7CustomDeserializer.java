package com.mannschaft.app.common.architecture.fixtures;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * D-7 番人メタテスト用のダミー deserializer。
 *
 * <p>{@code @JsonDeserialize(using = ...)}（クラス自身の生成手段＝免責される）と
 * {@code @JsonDeserialize(contentUsing = ...)}（要素型の指定にすぎず免責されない）の
 * 差を fixture で作り分けるためだけに存在する。実際にデシリアライズには使わない。
 */
public class D7CustomDeserializer extends JsonDeserializer<Object> {

    @Override
    public Object deserialize(JsonParser parser, DeserializationContext context) {
        throw new UnsupportedOperationException("fixture 専用（実行されない）");
    }
}
