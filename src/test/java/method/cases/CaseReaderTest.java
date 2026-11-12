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
import java.util.Set;
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
    assertNull(result.subLibrary());
  }

  @Test void readsBSubLibraryAndRejectsInvalidClassification() throws IOException {
    byte[] source = "source".getBytes(StandardCharsets.UTF_8);
    Path bDirectory = Files.createDirectory(scratch.resolve("B-901"));
    Files.write(bDirectory.resolve("Main.java"), source);
    Path metadata = bDirectory.resolve("B-901.json");
    String base = document("[\"description\"]", "single_file", entry("Main.java", sha256(source)))
        .replace("A-901", "B-901");
    for (String subLibrary : List.of("initial", "post_reassignment")) {
      Files.writeString(metadata, base.replace("\"category\":\"demo\"",
          "\"category\":\"demo\",\"sub_library\":\"" + subLibrary + "\""), StandardCharsets.UTF_8);
      assertEquals(subLibrary, CaseReader.read(metadata).subLibrary());
    }
    Files.writeString(metadata, base.replace("\"category\":\"demo\"",
        "\"category\":\"demo\",\"sub_library\":\"both\""), StandardCharsets.UTF_8);
    assertThrows(IOException.class, () -> CaseReader.read(metadata));
    Files.writeString(metadata, base.replace("\"category\":\"demo\"",
        "\"category\":\"demo\",\"sub_library\":null"), StandardCharsets.UTF_8);
    assertThrows(IOException.class, () -> CaseReader.read(metadata));

    Files.write(caseDirectory.resolve("Main.java"), source);
    Path aMetadata = metadata(document("[\"description\"]", "single_file", entry("Main.java", sha256(source)))
        .replace("\"category\":\"demo\"", "\"category\":\"demo\",\"sub_library\":\"initial\""));
    assertNull(CaseReader.read(aMetadata).subLibrary());
  }

  @Test void stagePrefixesMustMatchTheirSubLibrary() throws IOException {
    byte[] source = "source".getBytes(StandardCharsets.UTF_8);
    for (String id : List.of("BI-901", "BR-901", "BI-000")) {
      Path directory = Files.createDirectory(scratch.resolve(id));
      Files.write(directory.resolve("Main.java"), source);
      Path metadata = directory.resolve(id + ".json");
      String base = document("[\"description\"]", "single_file", entry("Main.java", sha256(source)))
          .replace("A-901", id);
      for (String phase : List.of("initial", "post_reassignment")) {
        Files.writeString(metadata, base.replace("\"category\":\"demo\"",
            "\"category\":\"demo\",\"sub_library\":\"" + phase + "\""), StandardCharsets.UTF_8);
        boolean valid = id.equals("BI-901") && phase.equals("initial")
            || id.equals("BR-901") && phase.equals("post_reassignment");
        if (valid) assertEquals(phase, CaseReader.read(metadata).subLibrary());
        else assertThrows(IOException.class, () -> CaseReader.read(metadata));
      }
      Files.writeString(metadata, base, StandardCharsets.UTF_8);
      assertThrows(IOException.class, () -> CaseReader.read(metadata));
    }
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

  @Test void renamedCaseCanReadAReferencedHistoricalSnapshot() throws IOException {
    Path current = Path.of("cases", "B", "initial_single_satellite_optimization", "BI-001");
    Path history = Path.of("cases", "backend", "versions", "B-004",
        "57a966eacb6b45926d3dc6c370158716c5341db5f51fc80aaf7a03ad55ed7f31.json");
    var latest = CaseReader.read(current.resolve("BI-001.json"));
    assertThrows(IOException.class, () -> CaseReader.read(history, current));
    assertThrows(IOException.class, () -> CaseReader.readHistorical(history, current, "B-005"));
    assertThrows(IOException.class, () -> CaseReader.readHistorical(current.resolve("BI-001.json"), current, "BI-001"));
    var previous = CaseReader.readHistorical(history, current, "B-004");
    assertEquals("B-004", previous.caseId());
    assertEquals("initial", previous.subLibrary());
    assertTrue(previous.description().contains("profit-first"));
    assertTrue(latest.description().contains("single-pass evaluation"));
    assertEquals(latest.code(), previous.code());
    assertEquals(sha256(Files.readAllBytes(history)), previous.caseSha256());
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
    int initialB = 0;
    int postReassignmentB = 0;
    for (String module : List.of("A", "B", "C")) {
      Path moduleDirectory = Path.of("cases", module);
      if (!Files.isDirectory(moduleDirectory)) {
        continue;
      }
      List<Path> roots = List.of(moduleDirectory);
      if (module.equals("B")) {
        Set<String> groups = Set.of("initial_single_satellite_optimization",
            "post_reassignment_adjustment");
        try (Stream<Path> directories = Files.list(moduleDirectory)) {
          assertEquals(groups, directories.filter(Files::isDirectory)
              .map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        roots = groups.stream().sorted().map(moduleDirectory::resolve).toList();
      }
      for (Path root : roots) {
        try (Stream<Path> directories = Files.list(root)) {
          for (Path directory : directories.filter(Files::isDirectory).sorted().toList()) {
            Path metadata = directory.resolve(directory.getFileName() + ".json");
            assertTrue(Files.isRegularFile(metadata), metadata.toString());
            metadataFiles.add(metadata);
          }
        }
      }
    }
    assertFalse(metadataFiles.isEmpty());
    for (Path metadata : metadataFiles) {
      var result = CaseReader.read(metadata);
      assertEquals(metadata.getParent().getFileName().toString(), result.caseId());
      assertFalse(result.code().isEmpty(), result.caseId());
      if (metadata.startsWith(Path.of("cases", "B"))) {
        String group = metadata.getParent().getParent().getFileName().toString();
        switch (group) {
          case "initial_single_satellite_optimization" -> {
              assertTrue(result.caseId().startsWith("BI-"), result.caseId());
              assertEquals("initial", result.subLibrary(), result.caseId());
          }
          case "post_reassignment_adjustment" -> {
              assertTrue(result.caseId().startsWith("BR-"), result.caseId());
              assertEquals("post_reassignment", result.subLibrary(), result.caseId());
          }
          default -> fail("Unexpected B directory: " + group);
        }
      }
      if ("initial".equals(result.subLibrary())) initialB++;
      if ("post_reassignment".equals(result.subLibrary())) postReassignmentB++;
    }
    if (Files.isDirectory(Path.of("cases", "B"))) {
      assertTrue(initialB > 0, "B initial sub-library is empty");
      assertTrue(postReassignmentB > 0, "B post-reassignment sub-library is empty");
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
