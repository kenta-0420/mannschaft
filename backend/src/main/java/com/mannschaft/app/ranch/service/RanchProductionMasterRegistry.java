package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.security.SecureRandom;
import java.util.List;
import java.util.Comparator;

/** 明示承認された本番64組と全段階素材を起動時に検証・固定する。開発用2素材は登録対象外。 */
@Component
public final class RanchProductionMasterRegistry {
    private static final String PREFIX = "mannschaft.ranch.production-master.";
    private static final Set<String> HABITATS = Set.of("LAND", "SEA", "AIR");
    private static final Set<String> STAGES = Set.of("EGG", "BABY", "JUVENILE", "ADULT");
    private static final Set<String> STYLES = Set.of("PIXEL", "PAINT_2D");
    private final Optional<RegisteredMaster> current;

    public RanchProductionMasterRegistry(Environment environment, ObjectMapper json) {
        String resource = environment.getProperty(PREFIX + "resource");
        if (resource == null || resource.isBlank()) {
            current = Optional.empty();
            return;
        }
        String version = environment.getProperty(PREFIX + "version");
        String expectedHash = environment.getProperty(PREFIX + "sha256");
        if (!resource.matches("ranch/approved/[A-Za-z0-9._/-]+\\.json")
                || resource.contains("..") || !token(version)
                || expectedHash == null || !expectedHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("本番恐竜masterの明示登録が不正です");
        }
        try (var stream = new ClassPathResource(resource).getInputStream()) {
            byte[] source = stream.readAllBytes();
            String actualHash = java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source));
            if (!actualHash.equalsIgnoreCase(expectedHash)) {
                throw new IllegalStateException("本番恐竜masterの内容hashが一致しません");
            }
            JsonNode parsed = json.readTree(new String(source, StandardCharsets.UTF_8));
            current = Optional.of(validate(parsed, version, actualHash));
        } catch (IOException | NoSuchAlgorithmException invalid) {
            throw new IllegalStateException("本番恐竜masterを検証できません", invalid);
        }
    }

    public Optional<RegisteredMaster> current() {
        return current;
    }

    /** 管理writerが事前確認したRanch版から切り替わっていないことを自TX内で照合する。 */
    public void requireCurrentVersion(String version) {
        if (version == null || current.isEmpty()
                || !current.orElseThrow().version().equals(version)) {
            throw new IllegalStateException("本番恐竜master版が未登録または変更されました");
        }
    }

    static RegisteredMaster validate(JsonNode root, String registeredVersion, String hash) {
        if (root == null || !root.isObject() || !root.path("approved").isBoolean()
                || !root.path("approved").booleanValue()
                || !registeredVersion.equals(root.path("version").asText(null))) {
            throw new IllegalArgumentException("本番恐竜masterの承認版が不正です");
        }
        JsonNode catalog = root.path("catalog");
        JsonNode mappings = root.path("mappings");
        JsonNode pools = root.path("randomPools");
        JsonNode assets = root.path("assets");
        JsonNode birthMappings = root.path("birthMappings");
        JsonNode compatibility = root.path("compatibility");
        long catalogVersion = root.path("catalogVersion").asLong(0);
        if (!catalog.isArray() || catalog.size() != 16 || !mappings.isArray()
                || mappings.size() != 64 || !pools.isObject() || pools.size() != 3
                || !assets.isArray() || assets.size() != 512
                || !birthMappings.isArray() || birthMappings.size() != 81
                || !root.path("catalogVersion").isIntegralNumber()
                || catalogVersion <= 0 || !compatibility.isObject()) {
            throw new IllegalArgumentException("本番恐竜masterの64組・3生息地・素材coverageが不足しています");
        }
        Set<String> species = new HashSet<>();
        Set<Pair> pairs = new HashSet<>();
        Map<String, Set<Pair>> habitatPairs = new HashMap<>();
        Map<Pair, String> habitatByPair = new HashMap<>();
        HABITATS.forEach(habitat -> habitatPairs.put(habitat, new HashSet<>()));
        for (JsonNode item : catalog) {
            String speciesKey = key(item.path("speciesKey"));
            String habitat = item.path("habitat").asText(null);
            JsonNode variants = item.path("variants");
            if (!species.add(speciesKey) || habitat == null || !HABITATS.contains(habitat)
                    || !variants.isArray() || variants.size() != 4) throw incomplete();
            for (JsonNode variant : variants) {
                Pair pair = new Pair(speciesKey, key(variant));
                if (!pairs.add(pair)) throw incomplete();
                habitatPairs.get(habitat).add(pair);
                habitatByPair.put(pair, habitat);
            }
        }
        if (pairs.size() != 64 || habitatPairs.values().stream().anyMatch(Set::isEmpty)) throw incomplete();

        Set<String> typeCodes = new HashSet<>();
        Set<Pair> mapped = new HashSet<>();
        Map<String, Pair> byTypeCode = new HashMap<>();
        for (JsonNode item : mappings) {
            String type = item.path("typeCode").asText(null);
            Pair pair = pair(item);
            if (type == null || !type.matches("[01]{6}") || !typeCodes.add(type)
                    || !pairs.contains(pair) || !mapped.add(pair)) throw incomplete();
            byTypeCode.put(type, pair);
        }
        if (!mapped.equals(pairs)) throw incomplete();
        Map<BirthPair, Pair> byBirthNumbers = new HashMap<>();
        for (JsonNode item : birthMappings) {
            int life = item.path("lifePathNumber").asInt(0);
            int name = item.path("nameNumber").asInt(0);
            Pair target = pair(item);
            if (life < 1 || life > 9 || name < 1 || name > 9 || !pairs.contains(target)
                    || byBirthNumbers.putIfAbsent(new BirthPair(life, name), target) != null) {
                throw incomplete();
            }
        }
        if (byBirthNumbers.size() != 81) throw incomplete();
        String resultSchemaVersion = key(compatibility.path("resultSchemaVersion"));
        String questionnaireVersion = key(compatibility.path("questionnaireVersion"));
        String scoringVersion = key(compatibility.path("scoringVersion"));
        String birthNormalizationVersion = key(compatibility.path("birthNormalizationVersion"));
        String birthRuleVersion = key(compatibility.path("birthRuleVersion"));
        for (String habitat : HABITATS) {
            JsonNode source = pools.path(habitat);
            if (!source.isArray()) throw incomplete();
            Set<Pair> actual = new HashSet<>();
            for (JsonNode item : source) {
                if (!actual.add(pair(item))) throw incomplete();
            }
            if (!actual.equals(habitatPairs.get(habitat))) throw incomplete();
        }

        Set<Visual> seen = new HashSet<>();
        for (JsonNode item : assets) {
            Pair pair = pair(item);
            String stage = item.path("stage").asText(null);
            String style = item.path("style").asText(null);
            if (!pairs.contains(pair) || stage == null || style == null
                    || !STAGES.contains(stage) || !STYLES.contains(style)
                    || !requiredReaction(stage).equals(item.path("reactionKey").asText(null))
                    || !token(item.path("assetKey").asText(null))
                    || !token(item.path("staticFallbackKey").asText(null))
                    || !digest(item.path("sha256").asText(null))
                    || !digest(item.path("staticFallbackSha256").asText(null))
                    || !seen.add(new Visual(pair, stage, style))) throw incomplete();
        }
        if (seen.size() != 512) throw incomplete();
        return new RegisteredMaster(registeredVersion, hash, catalogVersion,
                Map.copyOf(byTypeCode), Map.copyOf(byBirthNumbers), Map.copyOf(habitatByPair),
                resultSchemaVersion, questionnaireVersion, scoringVersion,
                birthNormalizationVersion, birthRuleVersion);
    }

    private static Pair pair(JsonNode item) {
        return new Pair(key(item.path("speciesKey")), key(item.path("variantKey")));
    }

    private static String key(JsonNode value) {
        String key = value.asText(null);
        if (!token(key) || key.startsWith("DEV_")) throw incomplete();
        return key;
    }

    private static boolean token(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,80}");
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static String requiredReaction(String stage) {
        return "EGG".equals(stage) ? "EGG_TOUCH" : "DINOSAUR_TOUCH";
    }

    private static IllegalArgumentException incomplete() {
        return new IllegalArgumentException("本番恐竜masterの承認coverageが不完全です");
    }

    public record RegisteredMaster(String version, String sha256, long catalogVersion,
                                   Map<String, Pair> byTypeCode, Map<BirthPair, Pair> byBirthNumbers,
                                   Map<Pair, String> habitatByPair,
                                   String resultSchemaVersion, String questionnaireVersion,
                                   String scoringVersion, String birthNormalizationVersion,
                                   String birthRuleVersion) {
        public RegisteredMaster {
            Objects.requireNonNull(version);
            Objects.requireNonNull(sha256);
        }

        public Optional<Pair> diagnosis(String typeCode) {
            return Optional.ofNullable(byTypeCode.get(typeCode));
        }

        public Optional<Pair> birth(int lifePathNumber, int nameNumber) {
            return Optional.ofNullable(byBirthNumbers.get(new BirthPair(lifePathNumber, nameNumber)));
        }

        public Optional<String> habitat(Pair pair) {
            return Optional.ofNullable(habitatByPair.get(pair));
        }

        public Optional<Pair> random(String habitat, SecureRandom random) {
            List<Pair> candidates = habitatByPair.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(habitat))
                    .map(Map.Entry::getKey)
                    .sorted(Comparator.comparing(Pair::speciesKey).thenComparing(Pair::variantKey))
                    .toList();
            return candidates.isEmpty() ? Optional.empty()
                    : Optional.of(candidates.get(random.nextInt(candidates.size())));
        }
    }

    public record Pair(String speciesKey, String variantKey) { }
    public record BirthPair(int lifePathNumber, int nameNumber) { }
    private record Visual(Pair pair, String stage, String style) { }
}
