package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.JsonNode;

/** 正規化済み入力と操作資源・版から、再送照合用 SHA-256 を作る。 */
public class RanchCommandHasher {
    public byte[] hash(String commandType, String resource, String ifMatch, JsonNode normalizedBody) {
        throw new UnsupportedOperationException("red 骨格");
    }
}
