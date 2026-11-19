package method.runtime;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.zip.ZipFile;
import javax.tools.ToolProvider;
import method.cases.CaseReader;
import method.runtime.SolverContracts.C;
import method.runtime.SolverContracts.LocalContext;
import method.runtime.SolverContracts.LocalPlan;
import method.runtime.SolverContracts.LocalResult;
import method.runtime.SolverContracts.LocalStatus;
import method.runtime.SolverContracts.Phase;
import method.runtime.SolverContracts.SatelliteView;
import model.AttitudeRecord;
import model.ProblemInstance;
import model.Satellite;
import model.ScheduledTask;
import model.SchedulingPlan;
import model.Task;
import model.TimeWindow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import problem.ScheduleValidator;

import static org.junit.jupiter.api.Assertions.*;

/** Compiles the archived source, rather than testing copies in the production tree. */
class CCaseArchiveTest {
  private static ClassLoader archive;
  private static Path archivedSources;
  private static final Budget BUDGET = new Budget(() -> 0L, 0, Long.MAX_VALUE, Long.MAX_VALUE);

  @BeforeAll static void compileArchivedCases() throws Exception {
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a Java 21 JDK is required");
    Path output = Path.of("target", "c-case-archive-test").toAbsolutePath();
    Files.createDirectories(output);
    Path projectClasses = Path.of("target", "classes").toAbsolutePath();
    Path apiClasses = Path.of("target", "c-case-api").toAbsolutePath();
    try (var classes = Files.walk(projectClasses)) {
      for (Path source : classes.filter(Files::isRegularFile).toList()) {
        Path relative = projectClasses.relativize(source);
        String name = relative.toString().replace('\\', '/');
        if (!(name.startsWith("model/") || name.startsWith("problem/") || name.startsWith("utils/")
            || name.startsWith("method/ProblemAdapter") || name.startsWith("method/runtime/Budget")
            || name.startsWith("method/runtime/ProblemAccess") || name.startsWith("method/runtime/SolverContracts"))) {
          continue;
        }
        Path target = apiClasses.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    assertFalse(Files.exists(apiClasses.resolve("method/rkhma")), "isolated case API contains RKHMA classes");
    archivedSources = Path.of("target", "c-case-history").toAbsolutePath();
    Path historyArchive = Path.of("cases", "backend", "archives", "c-python-parity-20261117.zip");
    try (ZipFile history = new ZipFile(historyArchive.toFile())) {
      for (int id = 1; id <= 80; id++) {
        String caseId = String.format("C-%03d", id);
        String filename = "C" + String.format("%06d", id) + ".java";
        Path caseDirectory = Path.of("cases", "C", caseId);
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "--release", "21",
            "-classpath", apiClasses.toString(), "-d", output.toString()));
        var entry = history.getEntry(caseId + "/" + filename);
        if (entry != null) {
          assertEquals(1, history.stream().filter(item -> item.getName().equals(entry.getName())).count());
          byte[] sourceBytes;
          try (var input = history.getInputStream(entry)) { sourceBytes = input.readAllBytes(); }
          Path live = caseDirectory.resolve(filename);
          if (Files.exists(live)) assertArrayEquals(Files.readAllBytes(live), sourceBytes, caseId);
          Path extracted = archivedSources.resolve(caseId);
          Files.createDirectories(extracted);
          Path archivedJava = extracted.resolve(filename);
          Files.write(archivedJava, sourceBytes);
          var snapshotEntry = history.getEntry(caseId + "/snapshot.sha256");
          assertNotNull(snapshotEntry, caseId);
          String snapshot;
          try (var input = history.getInputStream(snapshotEntry)) {
            snapshot = new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim();
          }
          assertTrue(snapshot.matches("[0-9a-f]{64}"), caseId);
          var prior = CaseReader.read(Path.of("cases", "backend", "versions", caseId, snapshot + ".json"), extracted);
          assertEquals(snapshot, prior.caseSha256(), caseId);
          assertArrayEquals(sourceBytes, prior.code().getBytes(StandardCharsets.UTF_8), caseId);
          args.add(archivedJava.toString());
        } else if (id == 3) {
          args.add(caseDirectory.resolve(filename).toString());
        } else {
          try (var sources = Files.walk(caseDirectory)) {
            sources.filter(path -> path.toString().endsWith(".java")).sorted()
                .map(Path::toString).forEach(args::add);
          }
        }
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), caseId);
      }
    }
    ClassLoader caseParent = new ClassLoader(CCaseArchiveTest.class.getClassLoader()) {
      @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.matches("method\\.runtime\\.C\\d{6}(\\$.*)?")) throw new ClassNotFoundException(name);
        return super.loadClass(name, resolve);
      }
    };
    archive = new URLClassLoader(new URL[]{output.toUri().toURL()}, caseParent);
  }

  private static C decoder(int id) throws Exception {
    return (C) archive.loadClass("method.runtime.C" + String.format("%06d", id))
        .getConstructor().newInstance();
  }

  private static TimeWindow window(int task, int start, int end, int step, double angle) {
    TimeWindow w = new TimeWindow(0, task, 2026, 1, 1, 0, 0, start,
        2026, 1, 1, 0, 0, end);
    for (int second = start; second <= end; second += step) {
      w.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, second, angle, 0));
    }
    return w;
  }

  private static ProblemInstance instance(List<TimeWindow> windows) {
    var satellite = new Satellite(0);
    satellite.setTimeWindows(windows);
    return new ProblemInstance(List.of(new Task(0, 0, 0, 9, 2), new Task(1, 0, 0, 7, 2),
        new Task(2, 0, 0, 5, 2)), List.of(satellite));
  }

  private static ProblemInstance fixture(int step) {
    return instance(List.of(window(0, 10, 18, step, 0), window(0, 90, 98, step, 20),
        window(1, 38, 46, step, 0), window(1, 65, 73, step, 20),
        window(2, 115, 123, step, 0)));
  }

  private static SatelliteView view(List<ScheduledTask> observations) {
    return new SatelliteView(0, Set.of(0, 1, 2), observations, "case-view");
  }

  private static LocalContext context(Phase phase) {
    return new LocalContext(phase, Set.of(), Set.of(), List.of());
  }

  @Test void allCaseManifestsAndBackgroundVersionsMatchTheirArchivedSources() throws Exception {
    for (int id = 1; id <= 80; id++) {
      String caseId = String.format("C-%03d", id);
      Path metadata = Path.of("cases", "C", caseId, caseId + ".json");
      var data = CaseReader.read(metadata);
      assertEquals(caseId, data.caseId());
      assertNull(data.subLibrary());
      assertFalse(data.description().isBlank());
      Path python = metadata.getParent().resolve("C" + String.format("%06d", id) + ".py");
      if (Files.exists(python) && data.code().equals(Files.readString(python))) {
        assertTrue(data.code().contains("class Decoder:"), caseId);
        assertTrue(data.code().contains("def create():"), caseId);
      } else {
        Path java = metadata.getParent().resolve("C" + String.format("%06d", id) + ".java");
        assertEquals(Files.readString(java), data.code(), caseId);
        assertTrue(data.code().contains("class C" + String.format("%06d", id)), caseId);
      }
      String provenance = Files.readString(Path.of("cases", "backend", "sources", caseId + ".json"));
      assertTrue(provenance.contains(data.caseSha256()), "missing background version for " + caseId);
    }
  }

  @Test void historicalRkhmaCaseRemainsReadableAfterSourceEdits() throws Exception {
    String previous = "17c915018d10952687792bb350483836c1aca0a141c07932d824179bc3b3be21";
    Path versions = Path.of("cases", "backend", "versions", "C-003");
    var archived = CaseReader.read(versions.resolve(previous + ".json"),
        versions.resolve(previous).resolve("C-003"));
    var current = CaseReader.read(Path.of("cases", "C", "C-003", "C-003.json"));
    assertEquals(previous, archived.caseSha256());
    assertEquals("C-003", archived.caseId());
    assertNotEquals(archived.caseSha256(), current.caseSha256());
    String priorStandalone = "ef7ba97779a1a024f6bf6277684478de17d7d9f06db52a6861dcf37a8c9533d2";
    var priorArchived = CaseReader.read(versions.resolve(priorStandalone + ".json"), Path.of("cases", "C", "C-003"));
    assertEquals(priorStandalone, priorArchived.caseSha256());
    assertTrue(priorArchived.code().contains("class GreedyInsertion"));
    String javaSnapshot = "a0c74da49419d58cff03df590e7b439ad1d42bed407bca3ea9c74e47b420503b";
    Path priorDirectory = archivedSources.resolve("C-003");
    var priorJava = CaseReader.read(versions.resolve(javaSnapshot + ".json"), priorDirectory);
    assertEquals(Files.readString(priorDirectory.resolve("C000003.java")), priorJava.code());
    assertEquals(Files.readString(Path.of("cases", "C", "C-003", "C000003.py")), current.code());
  }

  @Test void archivedProjectBaselinesMatchTheOriginalCodeOnBothTimeGrids() throws Exception {
    for (int step : List.of(1, 2)) {
      ProblemInstance problem = fixture(step);
      ProblemAccess api = new ProblemAccess(problem, BUDGET);
      for (int id = 1; id <= 3; id++) {
        C original = switch (id) {
          case 1 -> ManualStrategies.earliestFinish();
          case 2 -> ManualStrategies.minimumAddedTransition();
          default -> new RkhmaGreedyDecoder();
        };
        C saved = decoder(id);
        List<Integer> order = List.of(0, 1, 2);
        List<ScheduledTask> expected = original.build(problem, api, view(List.of()), order,
            BUDGET, new SplittableRandom(8));
        List<ScheduledTask> actual = saved.build(problem, api, view(List.of()), order,
            BUDGET, new SplittableRandom(8));
        assertEquals(expected, actual, "build C-" + id + ", step=" + step);
        for (Phase phase : Phase.values()) {
          SatelliteView existing = view(expected);
          LocalPlan edit = new LocalPlan(existing.hash(), Set.of(1), List.of(1));
          LocalResult expectedLocal = original.applyLocal(problem, api, existing, context(phase), edit,
              BUDGET, new SplittableRandom(8));
          LocalResult actualLocal = saved.applyLocal(problem, api, existing, context(phase), edit,
              BUDGET, new SplittableRandom(8));
          assertEquals(expectedLocal, actualLocal, "local C-" + id + ", step=" + step + ", " + phase);
        }
        var preferred = new LocalContext(Phase.REASSIGN, Set.of(), Set.of(), List.of(), Set.of(0));
        assertEquals(original.rebuild(problem, api, view(expected), preferred, order, BUDGET, new SplittableRandom(8)),
            saved.rebuild(problem, api, view(expected), preferred, order, BUDGET, new SplittableRandom(8)),
            "rebuild C-" + id + ", step=" + step);
      }
    }
  }

  @Test void standaloneRkhmaGreedyPreservesWindowOrderAndApplicablePreference() throws Exception {
    for (int step : List.of(1, 2)) {
      ProblemInstance problem = instance(List.of(window(0, 50, 56, step, 0), window(0, 10, 20, step, 0)));
      ProblemAccess api = new ProblemAccess(problem, BUDGET);
      SatelliteView empty = view(List.of());
      C original = new RkhmaGreedyDecoder();
      C standalone = decoder(3);
      List<Integer> order = List.of(0);
      List<ScheduledTask> ordinary = standalone.build(problem, api, empty, order, BUDGET, new SplittableRandom(1));
      assertEquals(original.build(problem, api, empty, order, BUDGET, new SplittableRandom(1)), ordinary);
      assertSame(problem.satellite(0).getTimeWindows().getFirst(), ordinary.getFirst().timeWindow());
      LocalContext preferred = new LocalContext(Phase.REASSIGN, Set.of(), Set.of(), List.of(), Set.of(0));
      List<ScheduledTask> rebuilt = standalone.rebuild(problem, api, empty, preferred, order,
          BUDGET, new SplittableRandom(1));
      assertEquals(original.rebuild(problem, api, empty, preferred, order, BUDGET, new SplittableRandom(1)), rebuilt);
      assertSame(problem.satellite(0).getTimeWindows().get(step == 1 ? 1 : 0), rebuilt.getFirst().timeWindow());
    }
  }

  @Test void everyDecoderPreservesUnauthorizedObservationsAndProducesFeasibleTimelines() throws Exception {
    for (int step : List.of(1, 2)) {
      ProblemInstance problem = fixture(step);
      ProblemAccess api = new ProblemAccess(problem, BUDGET);
      for (int id = 1; id <= 80; id++) {
        C c = decoder(id);
        List<ScheduledTask> built = c.build(problem, api, view(List.of()), List.of(0, 1),
            BUDGET, new SplittableRandom(1));
        assertTrue(ScheduleValidator.evaluate(problem, new SchedulingPlan(built)).feasible(), "build C-" + id);
        ScheduledTask pinned = built.stream().filter(o -> o.getTaskId() == 0).findFirst().orElseThrow();
        SatelliteView existing = view(built);
        for (Phase phase : Phase.values()) {
          LocalPlan edit = new LocalPlan(existing.hash(), Set.of(1), List.of(1, 2));
          LocalResult result = c.applyLocal(problem, api, existing, context(phase), edit,
              BUDGET, new SplittableRandom(1));
          List<ScheduledTask> selected = result.status() == LocalStatus.APPLIED ? result.observations() : built;
          assertTrue(ScheduleValidator.evaluate(problem, new SchedulingPlan(selected)).feasible(),
              "local C-" + id + ", " + phase);
          ScheduledTask unchanged = selected.stream().filter(o -> o.getTaskId() == 0).findFirst().orElseThrow();
          assertSame(pinned.task(), unchanged.task(), "task changed by C-" + id);
          assertSame(pinned.timeWindow(), unchanged.timeWindow(), "window changed by C-" + id);
          assertSame(pinned.startRecord(), unchanged.startRecord(), "start changed by C-" + id);
          assertSame(pinned.endRecord(), unchanged.endRecord(), "end changed by C-" + id);
          assertEquals(selected.size(), selected.stream().map(ScheduledTask::getTaskId).distinct().count());
        }
      }
    }
  }

  @Test void futureAwareDecodersCanPreserveTheFollowingTask() throws Exception {
    ProblemInstance problem = instance(List.of(window(0, 10, 14, 1, 0), window(0, 30, 34, 1, 0),
        window(1, 10, 14, 1, 0)));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    List<Integer> order = List.of(0, 1);
    List<ScheduledTask> greedy = decoder(1).build(problem, api, view(List.of()), order,
        BUDGET, new SplittableRandom(1));
    assertEquals(1, greedy.size());
    for (int id = 11; id <= 16; id++) {
      List<ScheduledTask> result = decoder(id).build(problem, api, view(List.of()), order,
          BUDGET, new SplittableRandom(1));
      assertEquals(2, result.size(), "C-" + id);
      assertEquals(31, result.stream().filter(o -> o.getTaskId() == 0).findFirst().orElseThrow().getStartTime());
      assertTrue(ScheduleValidator.evaluate(problem, new SchedulingPlan(result)).feasible());
    }
  }

  @Test void nextTaskAndAllFutureOptionsCanChooseDifferentObservations() throws Exception {
    ProblemInstance problem = instance(List.of(window(0, 10, 14, 1, 0), window(0, 70, 74, 1, 0),
        window(1, 70, 74, 1, 0), window(1, 90, 94, 1, 0),
        window(2, 0, 4, 1, 0), window(2, 4, 8, 1, 0),
        window(2, 14, 18, 1, 0), window(2, 18, 22, 1, 0)));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    for (int id : List.of(11, 12)) {
      List<ScheduledTask> result = decoder(id).build(problem, api, view(List.of()), List.of(0, 1, 2),
          BUDGET, new SplittableRandom(1));
      ScheduledTask first = result.stream().filter(o -> o.getTaskId() == 0).findFirst().orElseThrow();
      assertEquals(id == 11 ? 11 : 71, first.getStartTime(), "C-" + id);
      assertTrue(ScheduleValidator.evaluate(problem, new SchedulingPlan(result)).feasible());
    }
  }

  @Test void windowDeadlineAndEarliestFinishAreNotTheSameDecision() throws Exception {
    ProblemInstance problem = instance(List.of(window(0, 10, 50, 1, 0), window(0, 20, 24, 1, 0)));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    assertEquals(11, decoder(1).build(problem, api, view(List.of()), List.of(0),
        BUDGET, new SplittableRandom(1)).getFirst().getStartTime());
    assertEquals(21, decoder(4).build(problem, api, view(List.of()), List.of(0),
        BUDGET, new SplittableRandom(1)).getFirst().getStartTime());
  }

  @Test void windowCenterAndMarginPoliciesMakeDifferentDecisions() throws Exception {
    ProblemInstance initial = instance(List.of(window(0, 10, 15, 1, 0), window(0, 30, 37, 1, 0)));
    ProblemAccess initialApi = new ProblemAccess(initial, BUDGET);
    List<TimeWindow> initialWindows = initial.satellite(0).getTimeWindows();
    for (int id : List.of(10, 26, 27)) {
      List<ScheduledTask> result = decoder(id).build(initial, initialApi, view(List.of()), List.of(0),
          BUDGET, new SplittableRandom(1));
      assertEquals(1, result.size(), "C-" + id);
      assertSame(initialWindows.get(id == 10 ? 0 : 1), result.getFirst().timeWindow(), "C-" + id);
      assertTrue(ScheduleValidator.evaluate(initial, new SchedulingPlan(result)).feasible());
    }

    ProblemInstance local = instance(List.of(window(0, 0, 6, 1, 0), window(0, 0, 59, 1, 0),
        window(1, 27, 31, 1, 0)));
    ProblemAccess localApi = new ProblemAccess(local, BUDGET);
    List<TimeWindow> localWindows = local.satellite(0).getTimeWindows();
    TimeWindow fixed = localWindows.get(2);
    ScheduledTask pinned = new ScheduledTask(local.task(1), fixed,
        fixed.getAttitudeRecord(1), fixed.getAttitudeRecord(3));
    SatelliteView existing = view(List.of(pinned));
    LocalPlan plan = new LocalPlan(existing.hash(), Set.of(), List.of(0));
    for (int id : List.of(26, 27)) {
      LocalResult result = decoder(id).applyLocal(local, localApi, existing, context(Phase.REASSIGN), plan,
          BUDGET, new SplittableRandom(1));
      assertEquals(LocalStatus.APPLIED, result.status(), "C-" + id);
      ScheduledTask selected = result.observations().stream().filter(o -> o.getTaskId() == 0)
          .findFirst().orElseThrow();
      assertSame(localWindows.get(id == 26 ? 1 : 0), selected.timeWindow(), "C-" + id);
      assertTrue(ScheduleValidator.evaluate(local, new SchedulingPlan(result.observations())).feasible());
    }
  }

  @Test void feasibleOpportunityCountDiffersFromRawWindowLength() throws Exception {
    ProblemInstance problem = instance(List.of(window(0, 10, 30, 1, 0), window(0, 50, 58, 1, 0),
        window(1, 24, 28, 1, 0)));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    TimeWindow fixedWindow = problem.satellite(0).getTimeWindows().get(2);
    ScheduledTask pinned = new ScheduledTask(problem.task(1), fixedWindow,
        fixedWindow.getAttitudeRecord(1), fixedWindow.getAttitudeRecord(3));
    SatelliteView existing = view(List.of(pinned));
    LocalPlan plan = new LocalPlan(existing.hash(), Set.of(), List.of(0));
    var shortest = decoder(5).applyLocal(problem, api, existing, context(Phase.REASSIGN), plan,
        BUDGET, new SplittableRandom(1));
    var fewest = decoder(36).applyLocal(problem, api, existing, context(Phase.REASSIGN), plan,
        BUDGET, new SplittableRandom(1));
    assertEquals(51, shortest.observations().stream().filter(o -> o.getTaskId() == 0)
        .findFirst().orElseThrow().getStartTime());
    assertEquals(11, fewest.observations().stream().filter(o -> o.getTaskId() == 0)
        .findFirst().orElseThrow().getStartTime());
  }

  @Test void randomizedPoliciesReproduceTheSameResultForTheSameSeed() throws Exception {
    ProblemInstance problem = instance(List.of(window(0, 10, 18, 1, 0), window(0, 40, 44, 1, 0)));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    for (int id : List.of(34, 35, 61, 62, 68, 69, 73, 76)) {
      C c = decoder(id);
      List<ScheduledTask> first = c.build(problem, api, view(List.of()), List.of(0),
          BUDGET, new SplittableRandom(27));
      List<ScheduledTask> repeated = c.build(problem, api, view(List.of()), List.of(0),
          BUDGET, new SplittableRandom(27));
      assertEquals(first, repeated, "C-" + id);
      assertTrue(ScheduleValidator.evaluate(problem, new SchedulingPlan(first)).feasible());
    }
  }

  @Test void everyDecoderCanRebuildACompleteSatelliteTimeline() throws Exception {
    ProblemInstance problem = fixture(1);
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    List<Integer> order = List.of(0, 1, 2);
    for (int id = 1; id <= 80; id++) {
      C c = decoder(id);
      List<ScheduledTask> initial = c.build(problem, api, view(List.of()), order,
          BUDGET, new SplittableRandom(2));
      List<ScheduledTask> rebuilt = c.rebuild(problem, api, view(initial), context(Phase.REASSIGN), order,
          BUDGET, new SplittableRandom(2));
      assertTrue(ScheduleValidator.evaluate(problem, new SchedulingPlan(rebuilt)).feasible(), "C-" + id);
      assertTrue(rebuilt.stream().allMatch(o -> o.getSatelliteId() == 0 && order.contains(o.getTaskId())), "C-" + id);
    }
  }

  @Test void chronologicalConstructionRestrictsWhereTheNextTaskIsInserted() throws Exception {
    ProblemInstance problem = instance(List.of(window(0, 30, 34, 1, 0), window(1, 10, 14, 1, 0)));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    assertEquals(2, decoder(1).build(problem, api, view(List.of()), List.of(0, 1),
        BUDGET, new SplittableRandom(1)).size());
    assertEquals(1, decoder(37).build(problem, api, view(List.of()), List.of(0, 1),
        BUDGET, new SplittableRandom(1)).size());
    assertEquals(2, decoder(38).build(problem, api, view(List.of()), List.of(0, 1),
        BUDGET, new SplittableRandom(1)).size());
  }

  @Test void exhaustiveDecodersUseTheirDifferentSelectionObjectives() throws Exception {
    ProblemInstance problem = instance(List.of(window(0, 30, 34, 1, 0), window(1, 19, 23, 1, 0),
        window(2, 41, 45, 1, 0)));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    List<Integer> order = List.of(0, 1, 2);
    var priority = decoder(47).build(problem, api, view(List.of()), order, BUDGET, new SplittableRandom(1));
    var profit = decoder(48).build(problem, api, view(List.of()), order, BUDGET, new SplittableRandom(1));
    assertEquals(Set.of(0), priority.stream().map(ScheduledTask::getTaskId).collect(java.util.stream.Collectors.toSet()));
    assertEquals(Set.of(1, 2), profit.stream().map(ScheduledTask::getTaskId).collect(java.util.stream.Collectors.toSet()));
    assertEquals(12, ScheduleValidator.evaluate(problem, new SchedulingPlan(profit)).totalProfit());
  }

  @Test void authorizedRetimeCanRecoverAnOtherwiseBlockedTask() throws Exception {
    ProblemInstance problem = instance(List.of(window(0, 10, 14, 1, 0), window(0, 30, 34, 1, 0),
        window(1, 10, 14, 1, 0)));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    for (int id : List.of(51, 52)) {
      List<ScheduledTask> result = decoder(id).build(problem, api, view(List.of()), List.of(0, 1),
          BUDGET, new SplittableRandom(1));
      assertEquals(2, result.size(), "C-" + id);
      assertEquals(31, result.stream().filter(o -> o.getTaskId() == 0).findFirst().orElseThrow().getStartTime());
      assertTrue(ScheduleValidator.evaluate(problem, new SchedulingPlan(result)).feasible());
    }
  }

  @Test void singleRetimeRepairRequiresTheNewTaskToFitBeforeReinsertion() throws Exception {
    TimeWindow pinnedWindow = window(2, 0, 4, 1, 0);
    TimeWindow earlyWindow = window(0, 15, 19, 1, 0);
    TimeWindow bridge = new TimeWindow(0, 0, 2026, 1, 1, 0, 0, 16, 2026, 1, 1, 0, 0, 20);
    for (int time = 16; time <= 20; time++) {
      bridge.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, time, time == 19 ? 80 : 0, 0));
    }
    TimeWindow lastWindow = window(1, 36, 40, 1, 80);
    ProblemInstance problem = instance(List.of(pinnedWindow, earlyWindow, bridge, lastWindow));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    ScheduledTask pinned = api.candidates(2, 0, BUDGET).iterator().next();
    ScheduledTask newTask = api.candidates(1, 0, BUDGET).iterator().next();
    ScheduledTask relocated = new ScheduledTask(problem.task(0), bridge,
        bridge.getAttitudeRecord(1), bridge.getAttitudeRecord(3));
    assertFalse(ScheduleValidator.evaluate(problem, new SchedulingPlan(List.of(pinned, newTask))).feasible());
    assertTrue(ScheduleValidator.evaluate(problem, new SchedulingPlan(List.of(pinned, relocated, newTask))).feasible());
    SatelliteView existing = view(List.of(pinned));
    LocalPlan edit = new LocalPlan(existing.hash(), Set.of(), List.of(0, 1));
    for (int id : List.of(51, 52)) {
      var result = decoder(id).applyLocal(problem, api, existing, context(Phase.REASSIGN), edit,
          BUDGET, new SplittableRandom(1));
      assertEquals(LocalStatus.APPLIED, result.status(), "C-" + id);
      assertEquals(List.of(2, 0), result.observations().stream().map(ScheduledTask::getTaskId).toList(), "C-" + id);
      assertEquals(16, result.observations().getLast().getStartTime(), "C-" + id);
    }
  }

  @Test void authorizedReplacementRequiresAProfitIncrease() throws Exception {
    ProblemInstance problem = instance(List.of(window(0, 10, 14, 1, 0), window(2, 10, 14, 1, 0)));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    for (int id : List.of(53, 54)) {
      List<ScheduledTask> improved = decoder(id).build(problem, api, view(List.of()), List.of(2, 0),
          BUDGET, new SplittableRandom(1));
      assertEquals(List.of(0), improved.stream().map(ScheduledTask::getTaskId).toList(), "C-" + id);
      List<ScheduledTask> retained = decoder(id).build(problem, api, view(List.of()), List.of(0, 2),
          BUDGET, new SplittableRandom(1));
      assertEquals(List.of(0), retained.stream().map(ScheduledTask::getTaskId).toList(), "C-" + id);
    }
  }

  @Test void cascadingRepairCanRetimeMoreThanOneAuthorizedTask() throws Exception {
    ProblemInstance problem = instance(List.of(window(0, 10, 14, 1, 0), window(0, 30, 34, 1, 0),
        window(1, 30, 34, 1, 0), window(1, 50, 54, 1, 0), window(2, 10, 14, 1, 0)));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    assertEquals(2, decoder(51).build(problem, api, view(List.of()), List.of(0, 1, 2),
        BUDGET, new SplittableRandom(1)).size());
    for (int id : List.of(57, 58)) {
      List<ScheduledTask> repaired = decoder(id).build(problem, api, view(List.of()), List.of(0, 1, 2),
          BUDGET, new SplittableRandom(1));
      assertEquals(3, repaired.size(), "C-" + id);
      assertTrue(ScheduleValidator.evaluate(problem, new SchedulingPlan(repaired)).feasible());
    }
  }

  @Test void multiReplacementCanReplaceTwoTasksButCannotTouchPinnedObservations() throws Exception {
    var satellite = new Satellite(0);
    satellite.setTimeWindows(List.of(window(0, 30, 34, 1, 0), window(1, 19, 23, 1, 0),
        window(2, 41, 45, 1, 0)));
    ProblemInstance problem = new ProblemInstance(List.of(new Task(0, 0, 0, 9, 2),
        new Task(1, 0, 0, 4, 2), new Task(2, 0, 0, 4, 2)), List.of(satellite));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    var single = decoder(53).build(problem, api, view(List.of()), List.of(1, 2, 0),
        BUDGET, new SplittableRandom(1));
    assertEquals(List.of(1, 2), single.stream().map(ScheduledTask::getTaskId).toList());
    for (int id : List.of(59, 60, 77)) {
      C c = decoder(id);
      var replaced = c.build(problem, api, view(List.of()), List.of(1, 2, 0), BUDGET, new SplittableRandom(1));
      assertEquals(List.of(0), replaced.stream().map(ScheduledTask::getTaskId).toList(), "C-" + id);
      SatelliteView existing = view(single);
      LocalPlan edit = new LocalPlan(existing.hash(), Set.of(2), List.of(2, 0));
      LocalResult local = c.applyLocal(problem, api, existing, context(Phase.REASSIGN), edit,
          BUDGET, new SplittableRandom(1));
      var timeline = local.status() == LocalStatus.APPLIED ? local.observations() : single;
      assertTrue(timeline.stream().anyMatch(o -> o.getTaskId() == 1), "pinned C-" + id);
      assertFalse(timeline.stream().anyMatch(o -> o.getTaskId() == 0), "pinned conflict C-" + id);
      assertTrue(ScheduleValidator.evaluate(problem, new SchedulingPlan(timeline)).feasible());
    }
  }

  @Test void dynamicProgramsUseDifferentObjectivesInChronologicalOrder() throws Exception {
    var satellite = new Satellite(0);
    satellite.setTimeWindows(List.of(window(0, 30, 34, 1, 0), window(1, 19, 23, 1, 0),
        window(2, 41, 45, 1, 0)));
    ProblemInstance problem = new ProblemInstance(List.of(new Task(0, 0, 0, 9, 2),
        new Task(1, 0, 0, 4, 2), new Task(2, 0, 0, 4, 2)), List.of(satellite));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    var profit = decoder(55).build(problem, api, view(List.of()), List.of(0, 1, 2), BUDGET, new SplittableRandom(1));
    var count = decoder(56).build(problem, api, view(List.of()), List.of(0, 1, 2), BUDGET, new SplittableRandom(1));
    assertEquals(List.of(0), profit.stream().map(ScheduledTask::getTaskId).toList());
    assertEquals(List.of(1, 2), count.stream().map(ScheduledTask::getTaskId).toList());
    assertTrue(ScheduleValidator.evaluate(problem, new SchedulingPlan(profit)).feasible());
    assertTrue(ScheduleValidator.evaluate(problem, new SchedulingPlan(count)).feasible());
  }

  @Test void repeatedInsertionCanUseAnObservationAsATransitionBridge() throws Exception {
    TimeWindow bridge = new TimeWindow(0, 1, 2026, 1, 1, 0, 0, 24, 2026, 1, 1, 0, 0, 28);
    for (int time = 24; time <= 28; time++) {
      bridge.addAttitudeRecord(new AttitudeRecord(2026, 1, 1, 0, 0, time, time <= 25 ? 0 : 90, 0));
    }
    TimeWindow first = window(2, 9, 13, 1, 0), last = window(3, 69, 73, 1, 90);
    var satellite = new Satellite(0);
    satellite.setTimeWindows(List.of(window(0, 44, 48, 1, 90), bridge, first, last));
    ProblemInstance problem = new ProblemInstance(List.of(new Task(0, 0, 0, 9, 2),
        new Task(1, 0, 0, 7, 2), new Task(2, 0, 0, 5, 2), new Task(3, 0, 0, 4, 2)), List.of(satellite));
    List<ScheduledTask> pinned = List.of(new ScheduledTask(problem.task(2), first,
        first.getAttitudeRecord(1), first.getAttitudeRecord(3)), new ScheduledTask(problem.task(3), last,
        last.getAttitudeRecord(1), last.getAttitudeRecord(3)));
    assertTrue(ScheduleValidator.evaluate(problem, new SchedulingPlan(pinned)).feasible());
    SatelliteView existing = new SatelliteView(0, Set.of(0, 1, 2, 3), pinned, "bridge-view");
    LocalPlan plan = new LocalPlan(existing.hash(), Set.of(), List.of(0, 1));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    var once = decoder(1).applyLocal(problem, api, existing, context(Phase.REASSIGN), plan,
        BUDGET, new SplittableRandom(1));
    var repeated = decoder(66).applyLocal(problem, api, existing, context(Phase.REASSIGN), plan,
        BUDGET, new SplittableRandom(1));
    assertEquals(3, once.observations().size());
    assertEquals(4, repeated.observations().size());
    assertTrue(ScheduleValidator.evaluate(problem, new SchedulingPlan(repeated.observations())).feasible());
  }

  @Test void descentCanRetimeAuthorizedObservationsAfterGreedyInsertion() throws Exception {
    ProblemInstance problem = instance(List.of(window(0, 10, 14, 1, 50), window(0, 30, 34, 1, 0),
        window(1, 70, 74, 1, 0)));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    var greedy = decoder(1).build(problem, api, view(List.of()), List.of(0, 1),
        BUDGET, new SplittableRandom(1));
    assertEquals(11, greedy.stream().filter(o -> o.getTaskId() == 0).findFirst().orElseThrow().getStartTime());
    for (int id : List.of(63, 64, 65, 67, 74, 78, 79)) {
      var improved = decoder(id).build(problem, api, view(List.of()), List.of(0, 1),
          BUDGET, new SplittableRandom(1));
      assertEquals(31, improved.stream().filter(o -> o.getTaskId() == 0).findFirst().orElseThrow().getStartTime(), "C-" + id);
      assertTrue(ScheduleValidator.evaluate(problem, new SchedulingPlan(improved)).feasible());
    }
  }

  @Test void localStabilityRulesUseTheReleasedObservationAsReference() throws Exception {
    ProblemInstance problem = instance(List.of(window(0, 10, 14, 1, 0), window(0, 30, 40, 1, 0)));
    TimeWindow oldWindow = problem.satellite(0).getTimeWindows().get(1);
    ScheduledTask old = new ScheduledTask(problem.task(0), oldWindow,
        oldWindow.getAttitudeRecord(5), oldWindow.getAttitudeRecord(7));
    SatelliteView existing = view(List.of(old));
    LocalPlan edit = new LocalPlan(existing.hash(), Set.of(0), List.of(0));
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    var preserveStart = decoder(70).applyLocal(problem, api, existing, context(Phase.REASSIGN), edit,
        BUDGET, new SplittableRandom(1));
    var preserveWindow = decoder(71).applyLocal(problem, api, existing, context(Phase.REASSIGN), edit,
        BUDGET, new SplittableRandom(1));
    assertEquals(LocalStatus.NO_FEASIBLE_CHANGE, preserveStart.status());
    assertEquals(31, preserveWindow.observations().getFirst().getStartTime());
    assertSame(oldWindow, preserveWindow.observations().getFirst().timeWindow());
    for (int id : List.of(75, 80)) {
      assertEquals(LocalStatus.NO_FEASIBLE_CHANGE, decoder(id).applyLocal(problem, api, existing,
          context(Phase.REASSIGN), edit, BUDGET, new SplittableRandom(1)).status(), "C-" + id);
    }
  }

  @Test void expiredBudgetStopsTheCasesBeforeDecoding() throws Exception {
    ProblemInstance problem = fixture(1);
    ProblemAccess api = new ProblemAccess(problem, BUDGET);
    for (int id = 1; id <= 80; id++) {
      C c = decoder(id);
      Budget expired = new Budget(() -> 1L, 0, 200, 1);
      assertThrows(Budget.SearchExpired.class, () -> c.build(problem, api, view(List.of()),
          List.of(0, 1), expired, new SplittableRandom(1)), "C-" + id);
    }
  }

  @Test void cachedRkhmaDecodersCheckTheCurrentBudgetAtEveryEntry() throws Exception {
    for (int step : List.of(1, 2)) {
      ProblemInstance problem = fixture(step);
      ProblemAccess api = new ProblemAccess(problem, BUDGET);
      for (C decoder : List.of(new RkhmaGreedyDecoder(), decoder(3))) {
        SatelliteView empty = view(List.of());
        decoder.build(problem, api, empty, List.of(0), BUDGET, new SplittableRandom(1));
        Budget expired = new Budget(() -> 1L, 0, 200, 1);
        String label = decoder.getClass().getSimpleName() + ", step=" + step;
        assertThrows(Budget.SearchExpired.class, () -> decoder.build(problem, api, empty,
            List.of(0), expired, new SplittableRandom(1)), "build " + label);
        assertThrows(Budget.SearchExpired.class, () -> decoder.rebuild(problem, api, empty,
            context(Phase.REASSIGN), List.of(), expired, new SplittableRandom(1)), "rebuild " + label);
        assertThrows(Budget.SearchExpired.class, () -> decoder.applyLocal(problem, api, empty,
            context(Phase.INIT), new LocalPlan(empty.hash(), Set.of(), List.of()), expired,
            new SplittableRandom(1)), "local " + label);
      }
    }
  }

  @Test void eachCaseWorksThroughTheExistingTwoStageRuntime() throws Exception {
    ProblemInstance problem = fixture(1);
    var limits = new TwoStageSolver.Limits(1000, 2000, 400, 200);
    for (int id = 1; id <= 80; id++) {
      var result = new TwoStageSolver(limits, () -> 0).solve(problem,
          ManualStrategies.allocationByCandidates(), ManualStrategies.priorityByDensity(), decoder(id), 3);
      assertEquals(TwoStageSolver.Status.SUCCESS, result.status(), "C-" + id + ": " + result.detail());
      assertTrue(ScheduleValidator.evaluate(problem, result.plan()).feasible(), "C-" + id);
    }
  }
}
