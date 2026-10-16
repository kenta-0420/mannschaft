package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Objects;

/** 操作・資源・版・正規化bodyを境界付きで束ねる再送照合用SHA-256。 */
public class RanchCommandHasher {
    public byte[] hash(String commandType, String resource, String ifMatch, JsonNode normalizedBody) {
        Objects.requireNonNull(commandType, "操作種別は必須です");
        Objects.requireNonNull(resource, "資源は必須です");
        Objects.requireNonNull(normalizedBody, "bodyは必須です");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writePart(output, commandType);
            writePart(output, resource);
            writePart(output, ifMatch);
            writePart(output, canonical(normalizedBody));
            output.flush();
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("再送照合hashを計算できません", exception);
        }
    }

    private void writePart(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private String canonical(JsonNode node) {
        if (node.isObject()) {
            ArrayList<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            StringBuilder result = new StringBuilder("{");
            for (int index = 0; index < keys.size(); index++) {
                if (index > 0) {
                    result.append(',');
                }
                String key = keys.get(index);
                result.append(com.fasterxml.jackson.databind.node.TextNode.valueOf(key));
                result.append(':').append(canonical(node.get(key)));
            }
            return result.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) {
                    result.append(',');
                }
                result.append(canonical(node.get(index)));
            }
            return result.append(']').toString();
        }
        return node.toString();
    }
}
