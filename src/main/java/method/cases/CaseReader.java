package method.cases;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 读取案例元数据与源码，不读取后台来源或执行案例代码。 */
public final class CaseReader {
  private CaseReader() { }

  /** 案例版本使用元数据文件实际字节的SHA-256。 */
  public record CaseData(String caseId, String category, String description, String code,
                         String caseSha256) { }

  /** 读取案例目录中的元数据；源码路径相对元数据所在目录。 */
  public static CaseData read(Path metadataPath) throws IOException {
    Objects.requireNonNull(metadataPath, "metadataPath");
    Path absolute = metadataPath.toAbsolutePath().normalize();
    return read(absolute, absolute.getParent());
  }

  /** 读取指定元数据，并在指定案例目录中查找源码；适用于后台历史快照。 */
  public static CaseData read(Path metadataPath, Path caseDirectory) throws IOException {
    Objects.requireNonNull(metadataPath, "metadataPath");
    Objects.requireNonNull(caseDirectory, "caseDirectory");
    Path root = caseDirectory.toRealPath();
    if (!Files.isDirectory(root)) {
      throw new IOException("Case directory is not a directory: " + caseDirectory);
    }

    byte[] metadataBytes = Files.readAllBytes(metadataPath);
    Object parsed = new JsonParser(decodeUtf8(metadataBytes, metadataPath)).parse();
    Map<?, ?> metadata = object(parsed, "case metadata");
    String caseId = nonBlankString(metadata.get("case_id"), "case_id");
    if (root.getFileName() == null || !caseId.equals(root.getFileName().toString())) {
      throw new IOException("case_id does not match case directory: " + caseId);
    }
    String category = nonBlankString(metadata.get("category"), "category");
    List<?> descriptionParts = array(metadata.get("description"), "description");
    StringBuilder description = new StringBuilder();
    for (Object part : descriptionParts) {
      description.append(string(part, "description element"));
    }

    Map<?, ?> code = object(metadata.get("code"), "code");
    String format = string(code.get("format"), "code.format");
    List<?> files = array(code.get("files"), "code.files");
    if (files.isEmpty()) {
      throw new IOException("code.files must not be empty");
    }
    if (!format.equals("single_file") && !format.equals("file_markers")) {
      throw new IOException("Unsupported code format: " + format);
    }
    if (format.equals("single_file") && files.size() != 1) {
      throw new IOException("single_file requires exactly one source file");
    }

    StringBuilder assembled = new StringBuilder();
    for (int i = 0; i < files.size(); i++) {
      Map<?, ?> file = object(files.get(i), "code.files element");
      String path = nonBlankString(file.get("path"), "code.files.path");
      String expectedHash = string(file.get("sha256"), "code.files.sha256");
      if (!expectedHash.matches("(?i)[0-9a-f]{64}")) {
        throw new IOException("Invalid SHA-256 for source: " + path);
      }
      if (format.equals("file_markers") && (!path.startsWith("src/") || path.length() == 4)) {
        throw new IOException("file_markers source must start with src/: " + path);
      }
      Path source = resolveSource(root, path);
      if (format.equals("single_file") && Path.of(path).getNameCount() != 1) {
        throw new IOException("single_file source must be beside case metadata: " + path);
      }
      byte[] sourceBytes = Files.readAllBytes(source);
      if (!sha256(sourceBytes).equalsIgnoreCase(expectedHash)) {
        throw new IOException("SHA-256 mismatch for source: " + path);
      }
      String sourceText = decodeUtf8(sourceBytes, source);
      if (i > 0) {
        assembled.append('\n');
      }
      if (format.equals("file_markers")) {
        assembled.append("// FILE: ").append(path.substring(4)).append('\n');
      }
      assembled.append(sourceText);
    }
    return new CaseData(caseId, category, description.toString(), assembled.toString(),
        sha256(metadataBytes));
  }

  private static Path resolveSource(Path root, String value) throws IOException {
    if (value.startsWith("/") || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0) {
      throw new IOException("Source path must be relative and use /: " + value);
    }
    try {
      Path relative = Path.of(value);
      if (relative.isAbsolute()) {
        throw new IOException("Source path must be relative: " + value);
      }
      for (Path component : relative) {
        if (component.toString().equals("..")) {
          throw new IOException("Source path must stay inside case directory: " + value);
        }
      }
      Path resolved = root.resolve(relative).normalize();
      if (!resolved.startsWith(root)) {
        throw new IOException("Source path must stay inside case directory: " + value);
      }
      Path actual = resolved.toRealPath();
      if (!actual.startsWith(root) || !Files.isRegularFile(actual)) {
        throw new IOException("Source must be a file inside case directory: " + value);
      }
      return actual;
    } catch (InvalidPathException e) {
      throw new IOException("Invalid source path: " + value, e);
    }
  }

  private static String decodeUtf8(byte[] bytes, Path path) throws IOException {
    try {
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      throw new IOException("Invalid UTF-8: " + path, e);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 unavailable", e);
    }
  }

  private static Map<?, ?> object(Object value, String name) throws IOException {
    if (value instanceof Map<?, ?> result) {
      return result;
    }
    throw new IOException(name + " must be an object");
  }

  private static List<?> array(Object value, String name) throws IOException {
    if (value instanceof List<?> result) {
      return result;
    }
    throw new IOException(name + " must be an array");
  }

  private static String string(Object value, String name) throws IOException {
    if (value instanceof String result) {
      return result;
    }
    throw new IOException(name + " must be a string");
  }

  private static String nonBlankString(Object value, String name) throws IOException {
    String result = string(value, name);
    if (result.isBlank()) {
      throw new IOException(name + " must not be blank");
    }
    return result;
  }

  /** 仅供案例读取器使用的JSON解析器，避免引入生产依赖。 */
  private static final class JsonParser {
    private final String input;
    private int position;

    private JsonParser(String input) {
      this.input = input;
    }

    private Object parse() throws IOException {
      Object result = value();
      whitespace();
      if (position != input.length()) {
        throw error("Unexpected trailing content");
      }
      return result;
    }

    private Object value() throws IOException {
      whitespace();
      if (position >= input.length()) {
        throw error("Expected a value");
      }
      return switch (input.charAt(position)) {
        case '{' -> parseObject();
        case '[' -> parseArray();
        case '"' -> parseString();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default -> parseNumber();
      };
    }

    private Map<String, Object> parseObject() throws IOException {
      position++;
      Map<String, Object> result = new LinkedHashMap<>();
      whitespace();
      if (take('}')) {
        return result;
      }
      do {
        whitespace();
        if (position >= input.length() || input.charAt(position) != '"') {
          throw error("Expected an object key");
        }
        String key = parseString();
        whitespace();
        expect(':');
        Object item = value();
        if (result.containsKey(key)) {
          throw error("Duplicate object key: " + key);
        }
        result.put(key, item);
        whitespace();
        if (take('}')) {
          return result;
        }
        expect(',');
      } while (true);
    }

    private List<Object> parseArray() throws IOException {
      position++;
      List<Object> result = new ArrayList<>();
      whitespace();
      if (take(']')) {
        return result;
      }
      do {
        result.add(value());
        whitespace();
        if (take(']')) {
          return result;
        }
        expect(',');
      } while (true);
    }

    private String parseString() throws IOException {
      expect('"');
      StringBuilder result = new StringBuilder();
      while (position < input.length()) {
        char c = input.charAt(position++);
        if (c == '"') {
          return result.toString();
        }
        if (c < 0x20) {
          throw error("Unescaped control character");
        }
        if (c != '\\') {
          result.append(c);
          continue;
        }
        if (position >= input.length()) {
          throw error("Unterminated escape");
        }
        char escaped = input.charAt(position++);
        switch (escaped) {
          case '"', '\\', '/' -> result.append(escaped);
          case 'b' -> result.append('\b');
          case 'f' -> result.append('\f');
          case 'n' -> result.append('\n');
          case 'r' -> result.append('\r');
          case 't' -> result.append('\t');
          case 'u' -> result.append(unicodeEscape());
          default -> throw error("Invalid string escape");
        }
      }
      throw error("Unterminated string");
    }

    private char unicodeEscape() throws IOException {
      if (input.length() - position < 4) {
        throw error("Incomplete Unicode escape");
      }
      int value = 0;
      for (int i = 0; i < 4; i++) {
        int digit = Character.digit(input.charAt(position++), 16);
        if (digit < 0 || input.charAt(position - 1) > 127) {
          throw error("Invalid Unicode escape");
        }
        value = value * 16 + digit;
      }
      return (char) value;
    }

    private Object literal(String text, Object value) throws IOException {
      if (!input.startsWith(text, position)) {
        throw error("Invalid literal");
      }
      position += text.length();
      return value;
    }

    private BigDecimal parseNumber() throws IOException {
      int start = position;
      take('-');
      if (!take('0')) {
        digits();
      }
      if (take('.')) {
        digits();
      }
      if (take('e') || take('E')) {
        if (!take('+')) {
          take('-');
        }
        digits();
      }
      try {
        return new BigDecimal(input.substring(start, position));
      } catch (NumberFormatException e) {
        throw error("Invalid number");
      }
    }

    private void digits() throws IOException {
      int start = position;
      while (position < input.length() && input.charAt(position) >= '0'
          && input.charAt(position) <= '9') {
        position++;
      }
      if (start == position) {
        throw error("Expected a digit");
      }
    }

    private void whitespace() {
      while (position < input.length()) {
        char c = input.charAt(position);
        if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
          return;
        }
        position++;
      }
    }

    private boolean take(char expected) {
      if (position < input.length() && input.charAt(position) == expected) {
        position++;
        return true;
      }
      return false;
    }

    private void expect(char expected) throws IOException {
      if (!take(expected)) {
        throw error("Expected '" + expected + "'");
      }
    }

    private IOException error(String message) {
      return new IOException("Invalid JSON at character " + position + ": " + message);
    }
  }
}
