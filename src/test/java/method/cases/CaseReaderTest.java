package method.cases;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CaseReaderTest {
  private Path scratch;
  private Path caseDirectory;

  @BeforeEach void prepare() throws IOException {
    Path target = Path.of("target").toAbsolutePath();
    Files.createDirectories(target);
    scratch = Files.createTempDirectory(target, "case-reader-test-");
    caseDirectory = Files.createDirectory(scratch.resolve("A-901"));
  }

  @Test void readsSingleFileWithoutChangingWhitespaceOrJsonEscapes() throws IOException {
    byte[] source = "first\tline\r\n第二\n".getBytes(StandardCharsets.UTF_8);
    Files.write(caseDirectory.resolve("Main.java"), source);
    String description = "[\"alpha\\n\", \"tab:\\t\", \"\\u4f60\\ud83d\\ude80\","
        + " \" quote:\\\" slash:\\\\\"]";
    String json = document(description, "single_file", entry("Main.java", sha256(source)))
        .replace("\"category\":\"demo\"", "\"category\":\"demo\","
            + "\"extra\":{\"enabled\":true,\"value\":-12.5e+2,\"other\":null}");
    Path metadata = metadata(json);

    CaseReader.CaseData result = CaseReader.read(metadata);

    assertEquals("A-901", result.caseId());
    assertEquals("demo", result.category());
    assertEquals("alpha\ntab:\t你🚀 quote:\" slash:\\", result.description());
    assertEquals("first\tline\r\n第二\n", result.code());
    assertEquals(sha256(Files.readAllBytes(metadata)), result.caseSha256());
  }

  @Test void assemblesMultiFileCodeInListedOrderWithOriginalBoundaries() throws IOException {
    byte[] first = "license".getBytes(StandardCharsets.UTF_8);
    byte[] second = "class Main {\n}\n".getBytes(StandardCharsets.UTF_8);
    Files.createDirectories(caseDirectory.resolve("src/pkg"));
    Files.write(caseDirectory.resolve("src/LICENSE.txt"), first);
    Files.write(caseDirectory.resolve("src/pkg/Main.java"), second);
    String files = entry("src/LICENSE.txt", sha256(first)) + ","
        + entry("src/pkg/Main.java", sha256(second));

    var result = CaseReader.read(metadata(document("[\"one\", \" two\"]", "file_markers", files)));

    assertEquals("one two", result.description());
    assertEquals("// FILE: LICENSE.txt\nlicense\n// FILE: pkg/Main.java\nclass Main {\n}\n",
        result.code());
  }

  @Test void historicalMetadataUsesExplicitCaseDirectory() throws IOException {
    byte[] source = "old code\n".getBytes(StandardCharsets.UTF_8);
    Files.write(caseDirectory.resolve("Main.java"), source);
    Path history = Files.createDirectory(scratch.resolve("versions"));
    Path metadata = history.resolve("previous.json");
    Files.writeString(metadata, document("[\"old\"]", "single_file",
        entry("Main.java", sha256(source))), StandardCharsets.UTF_8);

    assertThrows(IOException.class, () -> CaseReader.read(metadata));
    var result = CaseReader.read(metadata, caseDirectory);
    assertEquals("old code\n", result.code());
    assertEquals(sha256(Files.readAllBytes(metadata)), result.caseSha256());
  }

  @Test void rejectsMissingOrModifiedSourceAndInvalidHashes() throws IOException {
    byte[] source = "source".getBytes(StandardCharsets.UTF_8);
    Path file = caseDirectory.resolve("Main.java");
    Files.write(file, source);
    Path metadata = metadata(document("[\"description\"]", "single_file",
        entry("Main.java", sha256(source))));
    Files.writeString(file, "modified", StandardCharsets.UTF_8);
    assertTrue(assertThrows(IOException.class, () -> CaseReader.read(metadata))
        .getMessage().contains("SHA-256 mismatch"));

    Files.writeString(metadata, document("[\"description\"]", "single_file",
        entry("missing.java", sha256(source))), StandardCharsets.UTF_8);
    assertThrows(IOException.class, () -> CaseReader.read(metadata));
    Files.writeString(metadata, document("[\"description\"]", "single_file",
        entry("Main.java", "not-a-hash")), StandardCharsets.UTF_8);
    assertTrue(assertThrows(IOException.class, () -> CaseReader.read(metadata))
        .getMessage().contains("Invalid SHA-256"));
  }

  @Test void rejectsSourcePathsOutsideTheCaseDirectory() throws IOException {
    byte[] outside = "outside".getBytes(StandardCharsets.UTF_8);
    Files.write(scratch.resolve("outside.java"), outside);
    String hash = sha256(outside);
    for (String path : List.of("../outside.java", "src/../../outside.java",
        "/outside.java", "C:/outside.java")) {
      Path metadata = metadata(document("[\"description\"]", "file_markers", entry(path, hash)));
      assertThrows(IOException.class, () -> CaseReader.read(metadata), path);
    }
    Path metadata = metadata(document("[\"description\"]", "single_file",
        "{\"path\":\"..\\\\outside.java\",\"sha256\":\"" + hash + "\"}"));
    assertThrows(IOException.class, () -> CaseReader.read(metadata));
  }

  @Test void rejectsSingleFileStoredInASubdirectory() throws IOException {
    byte[] source = "source".getBytes(StandardCharsets.UTF_8);
    Files.createDirectory(caseDirectory.resolve("nested"));
    Files.write(caseDirectory.resolve("nested/Main.java"), source);
    Path metadata = metadata(document("[\"description\"]", "single_file",
        entry("nested/Main.java", sha256(source))));
    assertTrue(assertThrows(IOException.class, () -> CaseReader.read(metadata))
        .getMessage().contains("beside case metadata"));
  }

  @Test void rejectsSymbolicLinkToAFileOutsideTheCaseDirectory() throws IOException {
    byte[] outside = "outside".getBytes(StandardCharsets.UTF_8);
    Path outsidePath = scratch.resolve("outside.java");
    Files.write(outsidePath, outside);
    Path link = caseDirectory.resolve("link.java");
    try {
      Files.createSymbolicLink(link, outsidePath);
    } catch (UnsupportedOperationException | SecurityException | IOException e) {
      assumeTrue(false, "Symbolic links unavailable: " + e.getMessage());
    }
    Path metadata = metadata(document("[\"description\"]", "single_file",
        entry("link.java", sha256(outside))));
    assertTrue(assertThrows(IOException.class, () -> CaseReader.read(metadata))
        .getMessage().contains("inside case directory"));
  }

  @Test void rejectsInvalidShapesFormatsAndMalformedJson() throws IOException {
    byte[] source = "source".getBytes(StandardCharsets.UTF_8);
    Files.write(caseDirectory.resolve("Main.java"), source);
    String valid = document("[\"description\"]", "single_file",
        entry("Main.java", sha256(source)));
    List<String> invalid = List.of(
        valid.replace("\"single_file\"", "\"unknown\""),
        valid.replace("\"files\":[", "\"files\":[] , \"other\":["),
        valid.replace("\"description\":[\"description\"]", "\"description\":\"description\""),
        valid.replace("\"case_id\":\"A-901\"", "\"case_id\":1"),
        valid.replace("\"case_id\":\"A-901\"", "\"case_id\":\"A-902\""),
        valid.replace("\"case_id\":\"A-901\"", "\"case_id\":\"A-901\",\"case_id\":\"A-902\""),
        valid.replace("\"description\"", "\"description\\q\""),
        valid + " trailing",
        valid.substring(0, valid.lastIndexOf('}')));
    for (String json : invalid) {
      Path metadata = metadata(json);
      assertThrows(IOException.class, () -> CaseReader.read(metadata), json);
    }
    String two = entry("Main.java", sha256(source)) + "," + entry("Main.java", sha256(source));
    assertThrows(IOException.class,
        () -> CaseReader.read(metadata(document("[\"description\"]", "single_file", two))));
    assertThrows(IOException.class,
        () -> CaseReader.read(metadata(document("[\"description\"]", "file_markers",
            entry("Main.java", sha256(source))))));
  }

  @Test void rejectsInvalidUtf8InMetadataAndSource() throws IOException {
    byte[] invalid = {(byte) 0xc3, (byte) 0x28};
    Path metadata = caseDirectory.resolve("A-901.json");
    Files.write(metadata, invalid);
    assertTrue(assertThrows(IOException.class, () -> CaseReader.read(metadata))
        .getMessage().contains("Invalid UTF-8"));
    Files.write(caseDirectory.resolve("Main.java"), invalid);
    Files.writeString(metadata, document("[\"description\"]", "single_file",
        entry("Main.java", sha256(invalid))), StandardCharsets.UTF_8);
    assertTrue(assertThrows(IOException.class, () -> CaseReader.read(metadata))
        .getMessage().contains("Invalid UTF-8"));
  }

  @Test void readsTheCurrentCaseLibrary() throws IOException {
    List<Path> metadataFiles = new ArrayList<>();
    for (String module : List.of("A", "B", "C")) {
      Path moduleDirectory = Path.of("cases", module);
      if (!Files.isDirectory(moduleDirectory)) {
        continue;
      }
      try (Stream<Path> directories = Files.list(moduleDirectory)) {
        for (Path directory : directories.filter(Files::isDirectory).sorted().toList()) {
          Path metadata = directory.resolve(directory.getFileName() + ".json");
          assertTrue(Files.isRegularFile(metadata), metadata.toString());
          metadataFiles.add(metadata);
        }
      }
    }
    assertFalse(metadataFiles.isEmpty());
    for (Path metadata : metadataFiles) {
      var result = CaseReader.read(metadata);
      assertEquals(metadata.getParent().getFileName().toString(), result.caseId());
      assertFalse(result.code().isEmpty(), result.caseId());
    }
  }

  private Path metadata(String json) throws IOException {
    Path path = caseDirectory.resolve("A-901.json");
    Files.writeString(path, json, StandardCharsets.UTF_8);
    return path;
  }

  private static String document(String description, String format, String files) {
    return "{\"case_id\":\"A-901\",\"category\":\"demo\",\"description\":" + description
        + ",\"code\":{\"format\":\"" + format + "\",\"files\":[" + files + "]}}";
  }

  private static String entry(String path, String hash) {
    return "{\"path\":\"" + path + "\",\"sha256\":\"" + hash + "\"}";
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
