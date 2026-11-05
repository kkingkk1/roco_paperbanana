package method.runtime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 在独立 JVM 执行手工组合并强制硬截止；CPU 与进程组内存限制由启动方设置。 */
public final class ForkedSolver {
  private ForkedSolver() { }

  public record Observation(int taskId, int satelliteId, int windowIndex,
                            double startTime, double endTime) { }

  public record Result(TwoStageSolver.Status status, Long totalProfit, List<Observation> observations,
                       String detail, long elapsedNanos) {
    public Result {
      Objects.requireNonNull(status);
      observations = List.copyOf(observations);
      Objects.requireNonNull(detail);
    }
  }

  public static Result run(Path scene, int taskCount, int satelliteCount, String combination, long seed)
      throws IOException, InterruptedException {
    return run(scene, taskCount, satelliteCount, combination, seed, null);
  }

  /** 可在公共读取完成后通知外层资源监视器启动其端到端计时。 */
  public static Result run(Path scene, int taskCount, int satelliteCount, String combination, long seed,
                           Runnable readyCallback) throws IOException, InterruptedException {
    Objects.requireNonNull(scene);
    if (!Files.isDirectory(scene) || taskCount < 0 || satelliteCount <= 0
        || combination == null || !combination.matches("A[123]B[123]C[123]")) {
      throw new IllegalArgumentException("invalid scene, dimensions or combination");
    }
    String binary = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
        ? "java.exe" : "java";
    String java = Path.of(System.getProperty("java.home"), "bin", binary).toString();
    ProcessBuilder builder = new ProcessBuilder(java, "-Xmx2g", "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC",
        "-cp", System.getProperty("java.class.path"), ForkedWorker.class.getName(),
        scene.toAbsolutePath().normalize().toString(), Integer.toString(taskCount),
        Integer.toString(satelliteCount), combination, Long.toString(seed));
    builder.redirectError(ProcessBuilder.Redirect.INHERIT);
    return supervise(builder.start(), TwoStageSolver.Limits.defaults().hardNanos(), taskCount, readyCallback);
  }

  /** 等待公共数据读取完成后启动硬计时；结果接收与子进程退出均计入。 */
  static Result supervise(Process process, long hardNanos, int maximumObservations)
      throws IOException, InterruptedException {
    return supervise(process, hardNanos, maximumObservations, null);
  }

  private static Result supervise(Process process, long hardNanos, int maximumObservations, Runnable readyCallback)
      throws IOException, InterruptedException {
    if (hardNanos <= 0 || maximumObservations < 0) throw new IllegalArgumentException("invalid hard limit");
    ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "solver-result-reader");
      thread.setDaemon(true);
      return thread;
    });
    long started = 0;
    try (DataInputStream input = new DataInputStream(new BufferedInputStream(process.getInputStream()));
         DataOutputStream output = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()))) {
      readReady(input);
      if (readyCallback != null) readyCallback.run();
      started = System.nanoTime();
      output.writeByte(1);
      output.flush();
      Future<Result> pending = reader.submit(() -> readResult(input, maximumObservations));
      try {
        long remaining = hardNanos - (System.nanoTime() - started);
        if (remaining <= 0) throw new TimeoutException();
        Result result = pending.get(remaining, TimeUnit.NANOSECONDS);
        remaining = hardNanos - (System.nanoTime() - started);
        if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) throw new TimeoutException();
        if (process.exitValue() != 0) throw new IOException("worker exited with code " + process.exitValue());
        long elapsed = System.nanoTime() - started;
        if (elapsed >= hardNanos) throw new TimeoutException();
        return new Result(result.status(), result.totalProfit(), result.observations(), result.detail(), elapsed);
      } catch (TimeoutException expired) {
        pending.cancel(true);
        process.destroyForcibly();
        process.waitFor();
        return new Result(TwoStageSolver.Status.HARD_TIMEOUT, null, List.of(),
            "worker exceeded hard deadline", System.nanoTime() - started);
      } catch (ExecutionException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof IOException io) throw io;
        throw new IOException("worker result failed", cause);
      }
    } catch (EOFException failure) {
      throw new IOException("worker ended before completing its protocol", failure);
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor();
      }
      reader.shutdownNow();
    }
  }

  private static void readReady(DataInputStream input) throws IOException {
    int first = input.readUnsignedByte();
    int second = input.readUnsignedByte();
    if (first != 0 || second != 5) {
      byte[] tail = input.readNBytes(Math.min(4094, input.available()));
      byte[] prefix = new byte[tail.length + 2];
      prefix[0] = (byte) first;
      prefix[1] = (byte) second;
      System.arraycopy(tail, 0, prefix, 2, tail.length);
      String detail = new String(prefix, StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "?");
      throw new IOException("worker startup output: " + detail);
    }
    byte[] ready = new byte[5];
    input.readFully(ready);
    if (!"READY".equals(new String(ready, StandardCharsets.US_ASCII))) {
      throw new IOException("worker did not report READY");
    }
  }

  private static Result readResult(DataInputStream input, int maximumObservations) throws IOException {
    if (!"RESULT".equals(input.readUTF())) throw new IOException("invalid worker result");
    TwoStageSolver.Status status;
    try {
      status = TwoStageSolver.Status.valueOf(input.readUTF());
    } catch (IllegalArgumentException badStatus) {
      throw new IOException("invalid worker status", badStatus);
    }
    Long profit = input.readBoolean() ? input.readLong() : null;
    String detail = input.readUTF();
    long elapsed = input.readLong();
    int count = input.readInt();
    if (count < 0 || count > maximumObservations) throw new IOException("invalid observation count");
    List<Observation> observations = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      observations.add(new Observation(input.readInt(), input.readInt(), input.readInt(),
          input.readDouble(), input.readDouble()));
    }
    if ((status == TwoStageSolver.Status.SUCCESS) != (profit != null)
        || (status != TwoStageSolver.Status.SUCCESS && !observations.isEmpty())) {
      throw new IOException("inconsistent worker result");
    }
    return new Result(status, profit, observations, detail, elapsed);
  }
}
