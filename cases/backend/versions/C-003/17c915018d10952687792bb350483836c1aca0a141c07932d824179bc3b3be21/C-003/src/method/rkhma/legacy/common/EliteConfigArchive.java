package method.rkhma.legacy.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class EliteConfigArchive {

  private static final int CONFIG_DIMENSION = AlgorithmConstants.HYBRID_CONFIG_DIMENSION;
  private static final int MAX_ELITE_CONFIGS = 4;
  private static final Path DEFAULT_ARCHIVE_DIRECTORY = Paths.get("outputs", "elite-config-archive");
  private static final HexFormat HEX_FORMAT = HexFormat.of();

  private EliteConfigArchive() {
    throw new UnsupportedOperationException("Utility class should not be instantiated");
  }

  public record ArchiveContext(
    String dataDir,
    int numTasks,
    int numSatellites,
    double populationFactor,
    int maxFes,
    String insertionMode
  ) {
    public ArchiveContext {
      Objects.requireNonNull(dataDir, "dataDir");
      Objects.requireNonNull(insertionMode, "insertionMode");
      if (numTasks <= 0) {
        throw new IllegalArgumentException("numTasks must be positive");
      }
      if (numSatellites <= 0) {
        throw new IllegalArgumentException("numSatellites must be positive");
      }
      if (!Double.isFinite(populationFactor) || populationFactor <= 0.0) {
        throw new IllegalArgumentException("populationFactor must be a positive finite value");
      }
      if (maxFes <= 0) {
        throw new IllegalArgumentException("maxFes must be positive");
      }
    }
  }

  public static int[][] loadEliteConfigs(ArchiveContext context) {
    return loadEliteConfigs(DEFAULT_ARCHIVE_DIRECTORY, context);
  }

  static int[][] loadEliteConfigs(Path archiveDirectory, ArchiveContext context) {
    Path archivePath = getArchivePath(archiveDirectory, context);
    if (!Files.exists(archivePath)) {
      return new int[0][];
    }

    try {
      String json = Files.readString(archivePath, StandardCharsets.UTF_8);
      return parseEliteConfigs(json, context);
    } catch (Exception e) {
      System.err.printf("[EliteConfigArchive] Failed to load %s: %s%n",
        archivePath.toAbsolutePath(), e.getMessage());
      return new int[0][];
    }
  }

  public static void saveBestConfig(ArchiveContext context, int[] config) {
    saveConfig(DEFAULT_ARCHIVE_DIRECTORY, context, config, true);
  }

  public static void saveCandidateConfig(ArchiveContext context, int[] config) {
    saveConfig(DEFAULT_ARCHIVE_DIRECTORY, context, config, false);
  }

  static void saveBestConfig(Path archiveDirectory, ArchiveContext context, int[] config) {
    saveConfig(archiveDirectory, context, config, true);
  }

  static void saveCandidateConfig(Path archiveDirectory, ArchiveContext context, int[] config) {
    saveConfig(archiveDirectory, context, config, false);
  }

  static String buildInstanceKey(ArchiveContext context) {
    String signature = String.join("|",
      normalizeDataDir(context.dataDir()),
      Integer.toString(context.numTasks()),
      Integer.toString(context.numSatellites()),
      Double.toString(context.populationFactor()),
      Integer.toString(context.maxFes()),
      context.insertionMode()
    );
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest(signature.getBytes(StandardCharsets.UTF_8));
      return String.format("%dt-%ds-%s",
        context.numTasks(),
        context.numSatellites(),
        HEX_FORMAT.formatHex(digest, 0, 12));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  static Path getArchivePath(Path archiveDirectory, ArchiveContext context) {
    return archiveDirectory.resolve(buildInstanceKey(context) + ".json");
  }

  private static void saveConfig(
    Path archiveDirectory,
    ArchiveContext context,
    int[] config,
    boolean prioritizeNewConfig
  ) {
    if (!isValidConfig(config)) {
      System.err.println("[EliteConfigArchive] Skip saving invalid elite config");
      return;
    }
    int[] normalizedConfig = AlgorithmConstants.normalizeHybridConfig(config);

    try {
      Files.createDirectories(archiveDirectory);
      int[][] existingConfigs = loadEliteConfigs(archiveDirectory, context);
      int[][] mergedConfigs = mergeConfigs(normalizedConfig, existingConfigs, prioritizeNewConfig);
      Path archivePath = getArchivePath(archiveDirectory, context);
      Files.writeString(
        archivePath,
        toJson(context, mergedConfigs),
        StandardCharsets.UTF_8
      );
      System.out.printf("[EliteConfigArchive] Saved %d config(s) to %s%n",
        mergedConfigs.length, archivePath.toAbsolutePath());
    } catch (IOException e) {
      Path archivePath = getArchivePath(archiveDirectory, context);
      System.err.printf("[EliteConfigArchive] Failed to save %s: %s%n",
        archivePath.toAbsolutePath(), e.getMessage());
    }
  }

  private static int[][] parseEliteConfigs(String json, ArchiveContext context) {
    String expectedKey = buildInstanceKey(context);
    String storedKey = extractStringField(json, "instanceKey");
    if (!expectedKey.equals(storedKey)) {
      throw new IllegalArgumentException("Archive instanceKey mismatch");
    }

    String arrayContent = extractArrayFieldContent(json, "eliteConfigs");
    List<int[]> configs = new ArrayList<>();
    int index = 0;
    while (index < arrayContent.length()) {
      char current = arrayContent.charAt(index);
      if (current == '[') {
        int end = findMatchingBracket(arrayContent, index);
        String rawConfig = arrayContent.substring(index + 1, end);
        configs.add(parseConfig(rawConfig));
        index = end + 1;
      } else {
        index++;
      }
    }

    return configs.toArray(new int[0][]);
  }

  private static String toJson(ArchiveContext context, int[][] eliteConfigs) {
    StringBuilder builder = new StringBuilder();
    builder.append("{\n");
    builder.append("  \"instanceKey\": \"").append(buildInstanceKey(context)).append("\",\n");
    builder.append("  \"dataDir\": \"").append(escapeJson(normalizeDataDir(context.dataDir()))).append("\",\n");
    builder.append("  \"numTasks\": ").append(context.numTasks()).append(",\n");
    builder.append("  \"numSatellites\": ").append(context.numSatellites()).append(",\n");
    builder.append("  \"populationFactor\": ").append(context.populationFactor()).append(",\n");
    builder.append("  \"maxFes\": ").append(context.maxFes()).append(",\n");
    builder.append("  \"insertionMode\": \"").append(escapeJson(context.insertionMode())).append("\",\n");
    builder.append("  \"updatedAt\": \"").append(Instant.now()).append("\",\n");
    builder.append("  \"eliteConfigs\": [\n");
    for (int i = 0; i < eliteConfigs.length; i++) {
      builder.append("    ").append(configToJson(eliteConfigs[i]));
      if (i + 1 < eliteConfigs.length) {
        builder.append(',');
      }
      builder.append('\n');
    }
    builder.append("  ]\n");
    builder.append("}\n");
    return builder.toString();
  }

  private static String configToJson(int[] config) {
    StringBuilder builder = new StringBuilder();
    builder.append('[');
    for (int i = 0; i < config.length; i++) {
      if (i > 0) {
        builder.append(", ");
      }
      builder.append(config[i]);
    }
    builder.append(']');
    return builder.toString();
  }

  private static int[][] mergeConfigs(int[] newConfig, int[][] existingConfigs, boolean prioritizeNewConfig) {
    List<int[]> orderedConfigs = new ArrayList<>();
    if (prioritizeNewConfig) {
      orderedConfigs.add(newConfig.clone());
    }
    for (int[] config : existingConfigs) {
      if (isValidConfig(config)) {
        orderedConfigs.add(config.clone());
      }
    }
    if (!prioritizeNewConfig) {
      orderedConfigs.add(newConfig.clone());
    }

    List<int[]> merged = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (int[] config : orderedConfigs) {
      String signature = configSignature(config);
      if (seen.add(signature)) {
        merged.add(config.clone());
      }
      if (merged.size() >= MAX_ELITE_CONFIGS) {
        break;
      }
    }
    return merged.toArray(new int[0][]);
  }

  private static String configSignature(int[] config) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < config.length; i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append(config[i]);
    }
    return builder.toString();
  }

  private static int[] parseConfig(String rawConfig) {
    String[] parts = rawConfig.split(",");
    if (parts.length != CONFIG_DIMENSION) {
      throw new IllegalArgumentException("Invalid elite config length: " + parts.length);
    }
    int[] config = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      config[i] = Integer.parseInt(parts[i].trim());
    }
    return AlgorithmConstants.normalizeHybridConfig(config);
  }

  private static String extractStringField(String json, String fieldName) {
    String fieldToken = "\"" + fieldName + "\"";
    int fieldIndex = json.indexOf(fieldToken);
    if (fieldIndex < 0) {
      throw new IllegalArgumentException("Missing field: " + fieldName);
    }
    int colonIndex = json.indexOf(':', fieldIndex);
    int openingQuote = json.indexOf('"', colonIndex + 1);
    int closingQuote = findClosingQuote(json, openingQuote);
    return json.substring(openingQuote + 1, closingQuote);
  }

  private static String extractArrayFieldContent(String json, String fieldName) {
    String fieldToken = "\"" + fieldName + "\"";
    int fieldIndex = json.indexOf(fieldToken);
    if (fieldIndex < 0) {
      throw new IllegalArgumentException("Missing field: " + fieldName);
    }
    int arrayStart = json.indexOf('[', fieldIndex);
    if (arrayStart < 0) {
      throw new IllegalArgumentException("Missing array start for field: " + fieldName);
    }
    int arrayEnd = findMatchingBracket(json, arrayStart);
    return json.substring(arrayStart + 1, arrayEnd);
  }

  private static int findClosingQuote(String json, int openingQuote) {
    for (int i = openingQuote + 1; i < json.length(); i++) {
      if (json.charAt(i) == '"' && json.charAt(i - 1) != '\\') {
        return i;
      }
    }
    throw new IllegalArgumentException("Unterminated JSON string");
  }

  private static int findMatchingBracket(String content, int openingIndex) {
    int depth = 0;
    for (int i = openingIndex; i < content.length(); i++) {
      char current = content.charAt(i);
      if (current == '[') {
        depth++;
      } else if (current == ']') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    throw new IllegalArgumentException("Unmatched array bracket");
  }

  private static String normalizeDataDir(String dataDir) {
    try {
      return Paths.get(dataDir).toAbsolutePath().normalize().toString();
    } catch (Exception ignored) {
      return dataDir;
    }
  }

  private static String escapeJson(String text) {
    return text
      .replace("\\", "\\\\")
      .replace("\"", "\\\"");
  }

  private static boolean isValidConfig(int[] config) {
    try {
      return AlgorithmConstants.normalizeHybridConfig(config) != null;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
