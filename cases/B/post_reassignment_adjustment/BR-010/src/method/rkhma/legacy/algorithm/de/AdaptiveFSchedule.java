package method.rkhma.legacy.algorithm.de;

import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.common.RandomProvider;

import java.util.Random;

public final class AdaptiveFSchedule {

  public static double[] build(
    int buckets,
    int mode,
    double alpha,
    double fMax,
    double fMin,
    double piecewiseP0,
    double piecewiseFHigh,
    double piecewiseFLow,
    double sigmoidP0,
    double sigmoidK,
    double noiseSigma,
    Random rand) {
    int safeBuckets = Math.max(1, buckets);

    if (mode != DEConfig.DE_ADAPTIVE_F_MODE_POWER
      && mode != DEConfig.DE_ADAPTIVE_F_MODE_PIECEWISE
      && mode != DEConfig.DE_ADAPTIVE_F_MODE_COSINE
      && mode != DEConfig.DE_ADAPTIVE_F_MODE_SIGMOID
      && mode != DEConfig.DE_ADAPTIVE_F_MODE_STOCHASTIC) {
      throw new IllegalArgumentException(
        "Invalid DE adaptive F mode: " + mode + ". Valid modes are: " +
        "POWER(" + DEConfig.DE_ADAPTIVE_F_MODE_POWER + "), " +
        "PIECEWISE(" + DEConfig.DE_ADAPTIVE_F_MODE_PIECEWISE + "), " +
        "COSINE(" + DEConfig.DE_ADAPTIVE_F_MODE_COSINE + "), " +
        "SIGMOID(" + DEConfig.DE_ADAPTIVE_F_MODE_SIGMOID + "), " +
        "STOCHASTIC(" + DEConfig.DE_ADAPTIVE_F_MODE_STOCHASTIC + ")"
      );
    }
    int safeMode = mode;

    double safeFMax = Double.isFinite(fMax) ? fMax : DEConfig.DE_ADAPTIVE_F_FMAX_DEFAULT;
    double safeFMin = Double.isFinite(fMin) ? fMin : DEConfig.DE_ADAPTIVE_F_FMIN_DEFAULT;
    if (safeFMin > safeFMax) {
      throw new IllegalArgumentException(
        "Invalid DE adaptive F range: fMin (" + safeFMin + ") > fMax (" + safeFMax + "). " +
        "fMin must be less than or equal to fMax."
      );
    }

    double safeAlpha = (Double.isFinite(alpha) && alpha > 0) ? alpha : DEConfig.DE_ADAPTIVE_F_ALPHA_DEFAULT;

    double safePiecewiseP0 = (Double.isFinite(piecewiseP0)
      ? piecewiseP0
      : DEConfig.DE_ADAPTIVE_F_PIECEWISE_P0_DEFAULT);
    safePiecewiseP0 = Math.max(0.0, Math.min(1.0, safePiecewiseP0));

    if (!Double.isFinite(piecewiseFHigh)) {
      throw new IllegalArgumentException(
        "Invalid piecewiseFHigh: " + piecewiseFHigh + ". Must be a finite number."
      );
    }
    if (!Double.isFinite(piecewiseFLow)) {
      throw new IllegalArgumentException(
        "Invalid piecewiseFLow: " + piecewiseFLow + ". Must be a finite number."
      );
    }
    double safePiecewiseFHigh = piecewiseFHigh;
    double safePiecewiseFLow = piecewiseFLow;

    double safeSigmoidP0 = (Double.isFinite(sigmoidP0)
      ? sigmoidP0
      : DEConfig.DE_ADAPTIVE_F_SIGMOID_P0_DEFAULT);
    safeSigmoidP0 = Math.max(0.0, Math.min(1.0, safeSigmoidP0));

    double safeSigmoidK = (Double.isFinite(sigmoidK) && sigmoidK > 0)
    ? sigmoidK
    : DEConfig.DE_ADAPTIVE_F_SIGMOID_K_DEFAULT;

    double safeNoiseSigma = (Double.isFinite(noiseSigma) && noiseSigma >= 0)
    ? noiseSigma
    : DEConfig.DE_ADAPTIVE_F_NOISE_SIGMA_DEFAULT;

    // Avoid non-determinism when caller omits rand.
    Random safeRand = (rand != null) ? rand : RandomProvider.get();

    // Cache frequently used values to avoid redundant computation
    double fRange = safeFMax - safeFMin;
    double halfFRange = 0.5 * fRange;

    // Precompute t values to avoid redundant calculations in the loop
    double[] tValues = new double[safeBuckets];
    for (int i = 0; i < safeBuckets; i++) {
      tValues[i] = (safeBuckets == 1) ? 0.0 : (i / (double) (safeBuckets - 1));
    }

    double[] schedule = new double[safeBuckets];
    for (int i = 0; i < safeBuckets; i++) {
      double t = tValues[i];

      double f;
      switch (safeMode) {
        case DEConfig.DE_ADAPTIVE_F_MODE_PIECEWISE:
          f = (t < safePiecewiseP0) ? safePiecewiseFHigh : safePiecewiseFLow;
          break;
        case DEConfig.DE_ADAPTIVE_F_MODE_COSINE:
          f = safeFMin + halfFRange * (1.0 + Math.cos(Math.PI * t));
          break;
        case DEConfig.DE_ADAPTIVE_F_MODE_SIGMOID:
          f = safeFMin + fRange / (1.0 + Math.exp(safeSigmoidK * (t - safeSigmoidP0)));
          break;
        case DEConfig.DE_ADAPTIVE_F_MODE_STOCHASTIC:
          double base = safeFMin + halfFRange * (1.0 + Math.cos(Math.PI * t));
          double z = safeRand.nextGaussian();

          // Limit extreme values to ±3σ (covers ~99.7% of Gaussian distribution)
          z = Math.max(-3.0, Math.min(3.0, z));
          f = base + z * safeNoiseSigma;
          break;
        case DEConfig.DE_ADAPTIVE_F_MODE_POWER:
        default:
          f = safeFMin + fRange * (1.0 - Math.pow(t, safeAlpha));
          break;
      }

      // Clip to bounds (STOCHASTIC mode may produce values outside range, which is expected)
      f = Math.max(safeFMin, Math.min(safeFMax, f));

      schedule[i] = f;
    }

    return schedule;
  }
}
