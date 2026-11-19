package method.cases;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Compiles and checks the current standalone Java adaptations of A cases. */
class ACaseArchiveTest {
  private static Class<?> mthg;
  private static Class<?> mtg;
  private static Class<?> greedy;
  private static Class<?> hungarian;
  private static Class<?> auction;
  private static Class<?> shortestPath;
  private static Class<?> simplex;
  private static Class<?> bottleneck;
  private static Class<?> murty;
  private static Class<?> costScaling;
  private static Class<?> quota;
  private static Class<?> asymmetric;

  @BeforeAll static void compileArchivedCase() throws Exception {
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a Java 21 JDK is required");
    Path output = Path.of("target", "a-case-archive-test").toAbsolutePath();
    Files.createDirectories(output);
    assertEquals(0, compiler.run(null, null, null, "--release", "21", "-encoding", "UTF-8",
        "-d", output.toString(), Path.of("cases", "A", "A-018", "A000018.java").toString(),
        Path.of("cases", "A", "A-019", "A000019.java").toString(),
        Path.of("cases", "A", "A-020", "A000020.java").toString(),
        Path.of("cases", "A", "A-021", "A000021.java").toString(),
        Path.of("cases", "A", "A-022", "A000022.java").toString(),
        Path.of("cases", "A", "A-023", "A000023.java").toString(),
        Path.of("cases", "A", "A-024", "A000024.java").toString(),
        Path.of("cases", "A", "A-025", "A000025.java").toString(),
        Path.of("cases", "A", "A-026", "A000026.java").toString(),
        Path.of("cases", "A", "A-027", "A000027.java").toString(),
        Path.of("cases", "A", "A-028", "A000028.java").toString(),
        Path.of("cases", "A", "A-029", "A000029.java").toString()));
    ClassLoader parent = new ClassLoader(ACaseArchiveTest.class.getClassLoader()) {
      @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.matches("method\\.runtime\\.A\\d{6}(\\$.*)?")) throw new ClassNotFoundException(name);
        return super.loadClass(name, resolve);
      }
    };
    ClassLoader archive = new URLClassLoader(new URL[]{output.toUri().toURL()}, parent);
    mthg = archive.loadClass("method.runtime.A000018");
    mtg = archive.loadClass("method.runtime.A000019");
    greedy = archive.loadClass("method.runtime.A000020");
    hungarian = archive.loadClass("method.runtime.A000021");
    auction = archive.loadClass("method.runtime.A000022");
    shortestPath = archive.loadClass("method.runtime.A000023");
    simplex = archive.loadClass("method.runtime.A000024");
    bottleneck = archive.loadClass("method.runtime.A000025");
    murty = archive.loadClass("method.runtime.A000026");
    costScaling = archive.loadClass("method.runtime.A000027");
    quota = archive.loadClass("method.runtime.A000028");
    asymmetric = archive.loadClass("method.runtime.A000029");
  }

  @Test void mthgPreservesMaximizationAndMinimization() throws Exception {
    var solve = mthg.getMethod("solve", int[][].class, int[][].class, int[].class, boolean.class);
    int[][] profit = {{10, 4, 6}, {5, 9, 7}}, weight = {{2, 2, 2}, {2, 2, 2}};
    var max = solve.invoke(null, profit, weight, new int[]{4, 4}, false);
    assertEquals(26, max.getClass().getMethod("value").invoke(max));
    assertArrayEquals(new int[]{0, 1, 1}, (int[]) max.getClass().getMethod("resourceByTask").invoke(max));
    var min = solve.invoke(null, profit, weight, new int[]{4, 4}, true);
    assertEquals(15, min.getClass().getMethod("value").invoke(min));
    assertArrayEquals(new int[]{1, 0, 0}, (int[]) min.getClass().getMethod("resourceByTask").invoke(min));
    assertArrayEquals(new int[]{10, 4, 6}, profit[0]);
  }

  @Test void mthgJavaAndItsHistoricalFortranVersionRemainReadable() throws Exception {
    assertVersions("A-018", "A000018.java", "684ccef45261b31475e543e494e75281808e0c686b212443f2d13ddc1209927a",
        "subroutine mthg (");
  }

  @Test void mtgAdaptationReportsExactAndLimitedSearch() throws Exception {
    var solve = mtg.getMethod("solve", int[][].class, int[][].class, int[].class, boolean.class, long.class);
    int[][] profit = {{14, 15, 13, 13, 13}, {20, 16, 14, 12, 18}, {20, 15, 16, 9, 13}};
    int[][] weight = {{1, 5, 3, 5, 2}, {2, 5, 2, 3, 1}, {1, 2, 1, 5, 5}};
    int[] capacity = {5, 5, 5};
    var exact = solve.invoke(null, profit, weight, capacity, false, -1L);
    assertEquals(82, exact.getClass().getMethod("value").invoke(exact));
    assertEquals(82, exact.getClass().getMethod("bound").invoke(exact));
    assertEquals(true, exact.getClass().getMethod("optimal").invoke(exact));
    assertArrayEquals(new int[]{1, 2, 2, 0, 1},
        (int[]) exact.getClass().getMethod("resourceByTask").invoke(exact));
    var limited = solve.invoke(null, profit, weight, capacity, false, 0L);
    assertEquals(81, limited.getClass().getMethod("value").invoke(limited));
    assertEquals(83, limited.getClass().getMethod("bound").invoke(limited));
    assertEquals(false, limited.getClass().getMethod("optimal").invoke(limited));
  }

  @Test void mtgJavaAndItsHistoricalFortranVersionRemainReadable() throws Exception {
    assertVersions("A-019", "A000019.java", "fcebfc04052459cf466a46e0fccb664716a15eb7d77eeaa5e54688474e57c1ff",
        "subroutine mtg (");
  }

  @Test void greedyMatchingPreservesSourceDecisions() throws Exception {
    assertGreedy(new double[][]{{4, 1, 3}, {2, 0, 5}, {3, 2, 2}},
        6.0, new int[]{0, 1, 2}, new int[]{0, 1, 2});
    assertGreedy(new double[][]{{Double.POSITIVE_INFINITY, 2, Double.NaN},
            {1, Double.NEGATIVE_INFINITY, 3}},
        3.0, new int[]{1, 0}, new int[]{1, 0, -1});
    assertGreedy(new double[][]{{-0.0, 0.0}, {0.0, -0.0}},
        -0.0, new int[]{0, 1}, new int[]{0, 1});
    assertGreedy(new double[][]{new double[0]}, -0.0, new int[]{-1}, new int[0]);
    assertGreedy(new double[0][], 0.0, new int[0], new int[0]);
  }

  @Test void greedyJavaAndItsHistoricalRustVersionRemainReadable() throws Exception {
    assertVersions("A-020", "A000020.java", "e79e9ad32bc65e03b0bee59cfb3165c2b3b1242158233120eb92cef36721a8bf");
  }

  @Test void hungarianMatchingPreservesSourceDecisions() throws Exception {
    assertMatching(hungarian, new double[][]{{4, 1, 3}, {2, 0, 5}, {3, 2, 2}},
        5.0, new int[]{1, 0, 2}, new int[]{1, 0, 2});
    assertMatching(hungarian, new double[][]{{-0.0, 0.0}, {0.0, -0.0}},
        -0.0, new int[]{0, 1}, new int[]{0, 1});
    assertMatching(hungarian, new double[][]{{1.5, -2, 3.25}, {4.5, 0, 2}},
        0.0, new int[]{1, 2}, new int[]{-1, 0, 1});
    assertMatching(hungarian, new double[][]{{Double.POSITIVE_INFINITY, 0}, {0, 0}},
        0.0, new int[]{1, 0}, new int[]{1, 0});
    assertMatching(hungarian, new double[0][], 0.0, new int[0], new int[0]);
    var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
        () -> hungarian.getMethod("solve", double[][].class)
            .invoke(null, (Object) new double[][]{{Double.POSITIVE_INFINITY}}));
    assertInstanceOf(IllegalArgumentException.class, failure.getCause());
  }

  @Test void hungarianJavaAndItsHistoricalRustVersionRemainReadable() throws Exception {
    assertVersions("A-021", "A000021.java", "a0f3d4a0702d5723e256f3525e90a79a33624a2f8ab522afbad7909081b27f8b");
  }

  @Test void auctionAndItsFallbacksPreserveSourceDecisions() throws Exception {
    double[][] square = {{4, 1, 3}, {2, 0, 5}, {3, 2, 2}};
    assertMatching(auction, square, 5.0, new int[]{1, 0, 2}, new int[]{1, 0, 2});
    assertMatching(auction, new double[][]{{1.5, -2, 3.25}, {4.5, 0, 2}},
        0.0, new int[]{1, 2}, new int[]{-1, 0, 1});
    assertMatching(auction, new double[][]{{1.2e151, 1.3e151}, {1.3e151, 1.2e151}},
        2.4e151, new int[]{0, 1}, new int[]{0, 1});
    assertMatching(auction, new double[][]{{-0.0, 0.0}, {0.0, -0.0}},
        -0.0, new int[]{0, 1}, new int[]{0, 1});
    var lapjv = auction.getDeclaredMethod("lapjv", double[][].class);
    lapjv.setAccessible(true);
    assertAssignment(lapjv.invoke(null, (Object) square),
        5.0, new int[]{1, 0, 2}, new int[]{1, 0, 2});
  }

  @Test void auctionJavaAndItsHistoricalRustVersionRemainReadable() throws Exception {
    assertVersions("A-022", "A000022.java", "c71407016a71a0776ab1a97509add4a242cd6d1b691932072fad53237d68713f");
  }

  @Test void shortestPathMatchingPreservesSourceDecisions() throws Exception {
    assertMatching(shortestPath, new double[][]{{4, 1, 3}, {2, 0, 5}, {3, 2, 2}},
        5.0, new int[]{1, 0, 2}, new int[]{1, 0, 2});
    assertMatching(shortestPath, new double[][]{{1.5, -2, 3.25}, {4.5, 0, 2}},
        0.0, new int[]{1, 2}, new int[]{-1, 0, 1});
    assertMatching(shortestPath, new double[][]{{Double.POSITIVE_INFINITY, 0}, {0, 0}},
        0.0, new int[]{1, 0}, new int[]{1, 0});
    assertMatching(shortestPath, new double[][]{new double[0]}, -0.0, new int[]{-1}, new int[0]);
  }

  @Test void shortestPathJavaAndItsHistoricalRustVersionRemainReadable() throws Exception {
    assertVersions("A-023", "A000023.java", "3a00b22a38bb09088e807972cbbcf6f9ea91e453e0b08f971aafc6ac46a0fe64");
  }

  @Test void transportationSimplexAndDualsPreserveSourceDecisions() throws Exception {
    double[][] matrix = {{4, 1, 3}, {2, 0, 5}, {3, 2, 2}};
    assertMatching(simplex, matrix, 5.0, new int[]{1, 0, 2}, new int[]{1, 0, 2});
    var result = simplex.getMethod("solveDuals", double[][].class).invoke(null, (Object) matrix);
    var type = result.getClass();
    assertAssignment(type.getMethod("assignment").invoke(result),
        5.0, new int[]{1, 0, 2}, new int[]{1, 0, 2});
    assertArrayEquals(new double[]{0, -2, -1}, (double[]) type.getMethod("rowDual").invoke(result));
    assertArrayEquals(new double[]{4, 1, 3}, (double[]) type.getMethod("columnDual").invoke(result));
  }

  @Test void simplexJavaAndItsHistoricalRustVersionRemainReadable() throws Exception {
    assertVersions("A-024", "A000024.java", "c176d92fee4a084f3c4bb54ff9eed8010ba466e94eacbc59dca120f55e3d8bd6");
  }

  @Test void bottleneckMatchingPreservesSourceDecisions() throws Exception {
    assertMatching(bottleneck, new double[][]{{1, 9, 3, 8}, {7, 2, 6, 4}},
        2.0, new int[]{0, 1}, new int[]{0, 1, -1, -1});
    assertMatching(bottleneck, new double[][]{{Double.POSITIVE_INFINITY, Double.NaN},
            {Double.NaN, Double.POSITIVE_INFINITY}},
        0.0, new int[]{-1, -1}, new int[]{-1, -1});
    assertMatching(bottleneck, new double[][]{{-2, 2, -1, -0.0, -0.0}, {-3, 2.5, 0, -0.0, 2.5},
            {0, 2.5, 1, 2.5, 0.5}, {3, 0, -3, 3, -2}, {-2, 0, -0.0, 0, -2}},
        -0.0, new int[]{4, 2, 0, 1, 3}, new int[]{2, 3, 1, 4, 0});
  }

  @Test void bottleneckJavaAndItsHistoricalRustVersionRemainReadable() throws Exception {
    assertVersions("A-025", "A000025.java", "d71f8eac28edb0c70cfee202e6e52af07c6e51ae682fffe4d72c6f6b1aef47a4");
  }

  @Test void murtyPreservesMultipleSolutionsAndTieOrder() throws Exception {
    double[][] matrix = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
    var results = (java.util.List<?>) murty.getMethod("solveKBest", double[][].class, int.class)
        .invoke(null, matrix, 10);
    assertEquals(6, results.size());
    assertAssignment(results.get(0), 0.0, new int[]{0, 1, 2}, new int[]{0, 1, 2});
    assertAssignment(results.get(1), 0.0, new int[]{1, 0, 2}, new int[]{1, 0, 2});
    assertAssignment(results.get(2), 0.0, new int[]{0, 2, 1}, new int[]{0, 2, 1});
    assertAssignment(results.get(3), 0.0, new int[]{2, 0, 1}, new int[]{1, 2, 0});
  }

  @Test void murtyJavaAndItsHistoricalRustVersionRemainReadable() throws Exception {
    assertVersions("A-026", "A000026.java", "531435cac77623badc98de56b263b9d8e78167fa37cb3fdeb9f6c83e52c9f480",
        "pub fn solve_kbest(");
  }

  @Test void costScalingAndItsColdFallbackPreserveSourceDecisions() throws Exception {
    assertMatching(costScaling, new double[][]{{4, 1, 3}, {2, 0, 5}, {3, 2, 2}},
        5.0, new int[]{1, 0, 2}, new int[]{1, 0, 2});
    assertMatching(costScaling, new double[][]{{1.2e151, 1.3e151}, {1.3e151, 1.2e151}},
        2.4e151, new int[]{0, 1}, new int[]{0, 1});
    assertMatching(costScaling, new double[][]{{-0.0, 0.0}, {0.0, -0.0}},
        -0.0, new int[]{0, 1}, new int[]{0, 1});
  }

  @Test void costScalingJavaAndItsHistoricalRustVersionRemainReadable() throws Exception {
    assertVersions("A-027", "A000027.java", "6fa3540aa7b9013a94f93897e7dcebd0039e6715b1a8db2f229f9a9ed86f592b");
  }

  @Test void quotaAssignmentPreservesGreedyAndPairedSwaps() throws Exception {
    float[][] matrix = {{6, 1, 8}, {8, 8, 6}, {10, 2, 7}, {4, 3, 9}};
    var solve = quota.getMethod("solve", float[][].class, int[].class, double.class,
        double.class, double.class, int.class, boolean.class);
    var result = solve.invoke(null, matrix, new int[]{1, 2, 1}, 300.0, 0.0, 0.0, 20, false);
    int[][] groups = (int[][]) result.getClass().getMethod("rowsByResource").invoke(result);
    assertArrayEquals(new int[]{2}, groups[0]);
    assertArrayEquals(new int[]{3, 1}, groups[1]);
    assertArrayEquals(new int[]{0}, groups[2]);
    assertEquals(29.0f, result.getClass().getMethod("totalScore").invoke(result));
    var initial = solve.invoke(null, matrix, new int[]{1, 2, 1}, 300.0, 0.0, 0.0, 0, true);
    groups = (int[][]) initial.getClass().getMethod("rowsByResource").invoke(initial);
    assertArrayEquals(new int[]{0}, groups[0]);
    assertArrayEquals(new int[]{1, 2}, groups[1]);
    assertArrayEquals(new int[]{3}, groups[2]);
    assertEquals(25.0f, initial.getClass().getMethod("totalScore").invoke(initial));
  }

  @Test void quotaJavaAndItsHistoricalNativeVersionRemainReadable() throws Exception {
    assertVersions("A-028", "A000028.java", "d34e8d2e134d7d67b04ee8035093dcb983644f0c5c73696ec817095b882e3ac7",
        "def solve(");
  }

  @Test void asymmetricSearchPreservesRowAndColumnMoves() throws Exception {
    assertAsymmetric(new double[][]{{0, 7, 6, 0}, {9, 2, 2, 2}, {3, 1, 3, 8}, {4, 10, 4, 3}},
        new int[]{0, 1, 2, 3}, new int[]{2, 0, 3, 1});
    assertAsymmetric(new double[][]{{6, 1, 8, 8, 0}, {8, 8, 6, 5, 5}, {10, 2, 7, 0, 5}},
        new int[]{0, 1, 2}, new int[]{2, 1, 0});
  }

  @Test void asymmetricJavaAndItsHistoricalPythonVersionRemainReadable() throws Exception {
    assertVersions("A-029", "A000029.java", "344462b9f2cf037c2d81a92824ed98c973a91dc2768f6e8ce4bf4dc70935cce0",
        "class AsymmetricGreedySearch:");
  }

  private static void assertAsymmetric(double[][] matrix, int[] order, int[] columns) throws Exception {
    var result = asymmetric.getMethod("optimize", double[][].class, boolean.class, int[].class,
        int.class, int.class, double.class, int.class).invoke(null, matrix, false, order, 15, 0, 0.001, 2);
    assertArrayEquals(order, (int[]) result.getClass().getMethod("rows").invoke(result));
    assertArrayEquals(columns, (int[]) result.getClass().getMethod("columns").invoke(result));
  }

  private static void assertVersions(String id, String javaName, String previousHash) throws Exception {
    assertVersions(id, javaName, previousHash, "pub fn solve(matrix: Vec<Vec<f64>>)");
  }

  private static void assertVersions(String id, String javaName, String previousHash, String previousEntry) throws Exception {
    Path directory = Path.of("cases", "A", id);
    var current = CaseReader.read(directory.resolve(id + ".json"));
    Path python = directory.resolve(javaName.replace(".java", ".py"));
    if (Files.exists(python)) {
      assertEquals(Files.readString(python), current.code());
      String javaHash = switch (id) {
        case "A-018" -> "f1f5d4523fba04e088fca2c1c3c1decaf503d5f095205371f01b8cfec4d4840f";
        case "A-019" -> "346b9f04ee4a7d894bfbc4e966170b5b9856115fa69b9c91d10ffec9c2defb38";
        case "A-020" -> "ad7e336c00c1f31a883f8b892b4b64f1e830b8b62386a08feed8f9739f383e2a";
        case "A-021" -> "178df5a009a83e7a24ef237abe713b1c8ca5511d373e09c2b8137a3071fbe917";
        case "A-022" -> "d990ada99c277274bea4eca4e3f4e9f3db63e09f9ac9fc92fcee9188dd2d9275";
        case "A-023" -> "f5924e3be04fbc0739bd2b23ef1d23d302e9d92965ec93cf576f59cb43dff71a";
        case "A-024" -> "4e99f8e5b62a18e89fe1f0a33c0a8c4d96d9fd8dd5e3cf14cb3598eb67c164a8";
        case "A-025" -> "58b17edf2cd490321735720f9a29d6af09956e03f0b12788df3d16d13e38bb58";
        case "A-026" -> "68cd05d852f7b5c1642b75593213a59104321c69ec1f53533fbabe996986479b";
        case "A-027" -> "a5bfccb1bc220cab009676859b52a33579ce4b067b87077f9c387c15774556a2";
        case "A-028" -> "e33b1ce18fd7321c1e6d3417ad961e326dab110c50672d173f16628d9b6f2e2b";
        case "A-029" -> "23aba7baefcebf4ab505e7be46aa452a9b52f392c6b2017ad3d782e8233ad8e7";
        default -> throw new IllegalStateException("missing Java comparison version for " + id);
      };
      var priorJava = CaseReader.read(Path.of("cases", "backend", "versions", id, javaHash + ".json"), directory);
      assertEquals(Files.readString(directory.resolve(javaName)), priorJava.code());
    } else {
      assertEquals(Files.readString(directory.resolve(javaName)), current.code());
    }
    assertTrue(Files.readString(Path.of("cases", "backend", "sources", id + ".json"))
        .contains(current.caseSha256()));
    var previous = CaseReader.read(Path.of("cases", "backend", "versions", id, previousHash + ".json"),
        directory);
    assertEquals(previousHash, previous.caseSha256());
    assertTrue(previous.code().contains(previousEntry));
  }

  private static void assertGreedy(double[][] matrix, double cost, int[] rows, int[] columns)
      throws Exception {
    assertMatching(greedy, matrix, cost, rows, columns);
  }

  private static void assertMatching(Class<?> solver, double[][] matrix, double cost, int[] rows, int[] columns)
      throws Exception {
    Object result = solver.getMethod("solve", double[][].class).invoke(null, (Object) matrix);
    assertAssignment(result, cost, rows, columns);
  }

  private static void assertAssignment(Object result, double cost, int[] rows, int[] columns) throws Exception {
    Class<?> type = result.getClass();
    double actual = (double) type.getMethod("totalCost").invoke(result);
    assertEquals(Double.doubleToRawLongBits(cost), Double.doubleToRawLongBits(actual));
    assertArrayEquals(rows, (int[]) type.getMethod("rowAssignment").invoke(result));
    assertArrayEquals(columns, (int[]) type.getMethod("columnAssignment").invoke(result));
  }
}
