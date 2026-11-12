package method.rkhma.legacy.common;

import java.util.Random;

/** Opt-in Gaussian sampler for algorithm hot paths. */
public final class FastGaussian {

  public static final String FAST_GAUSSIAN_PROPERTY = "autohyb.fastGaussian";

  private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

  private FastGaussian() {}

  public static boolean isEnabled() {
    return Boolean.parseBoolean(System.getProperty(FAST_GAUSSIAN_PROPERTY, "false"));
  }

  public static double nextGaussian(Random random) {
    if (random == null) {
      throw new IllegalArgumentException("random cannot be null");
    }
    State state = STATE.get();
    if (state.owner != random) {
      state.owner = random;
      state.hasSpare = false;
    }
    if (state.hasSpare) {
      state.hasSpare = false;
      return state.spare;
    }

    double u1;
    do {
      u1 = random.nextDouble();
    } while (u1 <= 0.0);
    double u2 = random.nextDouble();
    double radius = Math.sqrt(-2.0 * Math.log(u1));
    double angle = 2.0 * Math.PI * u2;
    state.spare = radius * Math.sin(angle);
    state.hasSpare = true;
    return radius * Math.cos(angle);
  }

  private static final class State {
    private Random owner;
    private boolean hasSpare;
    private double spare;
  }
}
