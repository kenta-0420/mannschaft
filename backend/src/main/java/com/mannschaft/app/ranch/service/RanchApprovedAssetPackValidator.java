package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.io.ClassPathResource;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** 正式v2の512tuple内にある4clipのbytes・geometry・時間を共通素材契約どおり検証する。 */
final class RanchApprovedAssetPackValidator {
    private static final Set<String> ROOT = fields("schemaVersion,catalogVersion,packVersion,approvalStatus,environment,entries");
    private static final Set<String> ENTRY = fields("speciesKey,variantKey,stage,renderStyle,assetKey,filePath,sourceSha256,frameWidth,frameHeight,frameCount,fallbackPath,fallbackSha256,sourceSpeciesKey,habitat,logicalCanvas,careSpriteRect,rendererProfile,clips");
    private static final Set<String> CLIP = fields("filePath,sourceSha256,frameWidth,frameHeight,frameCount,durationMs,phaseStarts,phaseMs,loop,returnTo,floorByFrame,sourceFloor384,sourceFloorByFrame384,tailMarks,shadowByFrame");
    private static final Set<String> ACTIONS = Set.of("idle", "walk", "feed", "happy");
    private static final long FILE_BOUND = 20L * 1024 * 1024;
    // 承認v4原本1922PNGの実測280355204bytesを改変せず収容するv2専用の有限上限。
    private static final long PACK_BOUND = 320L * 1024 * 1024;
    private static final double TOLERANCE = 0.000001;

    private RanchApprovedAssetPackValidator() { }

    static void validate(JsonNode manifest, String root) throws IOException, NoSuchAlgorithmException {
        exact(manifest, ROOT);
        for (String field : Set.of("packVersion", "approvalStatus", "environment")) {
            if (!manifest.path(field).isTextual()) throw invalid();
        }
        Images images = new Images();
        for (JsonNode entry : manifest.path("entries")) validateEntry(entry, root, images);

    }

    private static void validateEntry(JsonNode entry, String root, Images images)
            throws IOException, NoSuchAlgorithmException {
        exact(entry, ENTRY);
        for (String field : Set.of("speciesKey", "variantKey", "stage", "renderStyle", "assetKey",
                "sourceSpeciesKey", "habitat", "rendererProfile")) {
            if (!entry.path(field).isTextual()) throw invalid();
        }
        if (!entry.path("speciesKey").isTextual()
                || !entry.path("speciesKey").equals(entry.path("sourceSpeciesKey"))
                || !Set.of("LAND", "SEA", "AIR").contains(entry.path("habitat").asText())) throw invalid();
        String style = entry.path("renderStyle").asText();
        if (!Set.of("PIXEL", "PAINT_2D").contains(style)) throw invalid();
        int cell = "PIXEL".equals(style) ? 96 : 384;
        boolean egg = "EGG".equals(entry.path("stage").asText());
        int count = egg ? 1 : 4;
        geometry(entry, cell, count);
        exact(entry.path("logicalCanvas"), fields("width,height"));
        exact(entry.path("careSpriteRect"), fields("x,y,width,height"));
        fixed(entry.path("logicalCanvas"), "width", egg ? 96 : 112);
        fixed(entry.path("logicalCanvas"), "height", egg ? 96 : 112);
        fixed(entry.path("careSpriteRect"), "x", egg ? 0 : 8);
        fixed(entry.path("careSpriteRect"), "y", egg ? 0 : 8);
        fixed(entry.path("careSpriteRect"), "width", 96);
        fixed(entry.path("careSpriteRect"), "height", 96);
        Image source = image(root, entry.path("filePath"), entry.path("sourceSha256"), images);
        Image fallback = image(root, entry.path("fallbackPath"), entry.path("fallbackSha256"), images);
        if (source.width() != cell * count || source.height() != cell
                || fallback.width() != cell || fallback.height() != cell) throw invalid();
        if (egg) {
            if (!"COMMON_EGG_SVG_V1".equals(entry.path("rendererProfile").asText())
                    || !entry.path("clips").isNull()
                    || !entry.path("filePath").equals(entry.path("fallbackPath"))
                    || !entry.path("sourceSha256").equals(entry.path("fallbackSha256"))) throw invalid();
            return;
        }
        if (!"APPROVED_APPEARANCE_V4".equals(entry.path("rendererProfile").asText())) throw invalid();
        exact(entry.path("clips"), ACTIONS);
        for (String action : ACTIONS) {
            JsonNode clip = entry.path("clips").path(action);
            exact(clip, CLIP);
            geometry(clip, cell, 4);
            Image clipImage = image(root, clip.path("filePath"), clip.path("sourceSha256"), images);
            if (clipImage.width() != cell * 4 || clipImage.height() != cell) throw invalid();
            timing(clip, action);
            optionalNumber(clip.path("sourceFloor384"), 0, 384, false);
            optionalFloors(clip.path("floorByFrame"));
            optionalFloors(clip.path("sourceFloorByFrame384"));
            optionalMarks(clip.path("tailMarks"), false);
            optionalMarks(clip.path("shadowByFrame"), true);
            if ("idle".equals(action)) {
                for (String field : Set.of("filePath", "sourceSha256", "frameWidth", "frameHeight", "frameCount")) {
                    if (!clip.path(field).equals(entry.path(field))) throw invalid();
                }
            }
        }
    }

    private static void geometry(JsonNode node, int cell, int frames) {
        fixed(node, "frameWidth", cell);
        fixed(node, "frameHeight", cell);
        fixed(node, "frameCount", frames);
    }

    private static void timing(JsonNode clip, String action) {
        JsonNode durationNode = clip.path("durationMs");
        if (!durationNode.isIntegralNumber() || !durationNode.canConvertToInt()
                || durationNode.intValue() < 1 || durationNode.intValue() > 60000) throw invalid();
        double duration = durationNode.doubleValue();
        JsonNode starts = clip.path("phaseStarts");
        JsonNode phases = clip.path("phaseMs");
        four(starts); four(phases);
        if (number(starts.get(0), 0, 1, false) != 0) throw invalid();
        double total = 0;
        for (int i = 0; i < 4; i++) {
            double start = number(starts.get(i), 0, 1, false);
            double next = i == 3 ? 1 : number(starts.get(i + 1), 0, 1, false);
            double phase = number(phases.get(i), 0, 60000, true);
            if (start >= 1 || next <= start || Math.abs((next - start) * duration - phase) > TOLERANCE) throw invalid();
            total += phase;
        }
        if (Math.abs(total - duration) > TOLERANCE || !clip.path("loop").isBoolean()) throw invalid();
        boolean loops = "idle".equals(action) || "walk".equals(action);
        if (clip.path("loop").booleanValue() != loops
                || (loops ? !clip.path("returnTo").isNull() : !"idle".equals(clip.path("returnTo").asText()))) throw invalid();
    }

    private static void optionalFloors(JsonNode values) {
        if (values.isNull()) return;
        four(values);
        values.forEach(value -> optionalNumber(value, 0, 384, false));
    }

    private static void optionalMarks(JsonNode values, boolean shadow) {
        if (values.isNull()) return;
        four(values);
        for (JsonNode value : values) {
            exact(value, shadow ? fields("x,y,radius") : fields("x,y"));
            number(value.path("x"), 0, 112, false);
            number(value.path("y"), 0, 112, false);
            if (shadow) number(value.path("radius"), 0, 112, true);
        }
    }

    private static void optionalNumber(JsonNode value, double min, double max, boolean exclusiveMin) {
        if (!value.isNull()) number(value, min, max, exclusiveMin);
    }

    private static double number(JsonNode value, double min, double max, boolean exclusiveMin) {
        if (!value.isNumber()) throw invalid();
        double number = value.doubleValue();
        if (!Double.isFinite(number) || number < min || number > max || (exclusiveMin && number == min)) throw invalid();
        return number;
    }

    private static void four(JsonNode node) {
        if (!node.isArray() || node.size() != 4) throw invalid();
    }

    private static void fixed(JsonNode node, String field, int expected) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() != expected) throw invalid();
    }

    private static Image image(String root, JsonNode pathNode, JsonNode hashNode, Images images)
            throws IOException, NoSuchAlgorithmException {
        if (!pathNode.isTextual() || !hashNode.isTextual()) throw invalid();
        String path = pathNode.textValue(), hash = hashNode.textValue();
        if (!path.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.[A-Za-z0-9]+")
                || path.contains("..") || !hash.matches("[0-9a-fA-F]{64}")) throw invalid();
        Image cached = images.byPath.get(path);
        if (cached != null) {
            if (!cached.hash().equalsIgnoreCase(hash)) throw invalid();
            return cached;
        }
        byte[] bytes;
        try (var input = new ClassPathResource(root + path).getInputStream()) {
            bytes = input.readNBytes((int) FILE_BOUND + 1);
        }
        if (bytes.length > FILE_BOUND || bytes.length < 33
                || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equalsIgnoreCase(hash)
                || bytes[0] != (byte) 137 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') throw invalid();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(input);
                Image value = new Image(hash.toLowerCase(java.util.Locale.ROOT), reader.getWidth(0), reader.getHeight(0), bytes.length);
                long pixels = (long) value.width() * value.height();
                if (value.width() <= 0 || value.height() <= 0 || pixels > 128L * 1024 * 1024 / 4
                        || pixels * 4 + value.height() > 128L * 1024 * 1024) throw invalid();
                images.bytes += bytes.length;
                if (images.bytes > PACK_BOUND) throw invalid();
                images.byPath.put(path, value);
                return value;
            } finally { reader.dispose(); }
        }
    }

    private static Set<String> fields(String names) {
        return Set.of(names.split(","));
    }

    private static void exact(JsonNode node, Set<String> expected) {
        if (!node.isObject()) throw invalid();
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("正式恐竜v2素材のgeometry・clip・hashが不正です");
    }

    private static final class Images {
        private final Map<String,Image> byPath = new HashMap<>();
        private long bytes;
    }

    private record Image(String hash, int width, int height, long bytes) { }
}
