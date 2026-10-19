package fr.basile.isohypse;

import org.bukkit.util.noise.PerlinNoiseGenerator;

import java.util.Random;

/**
 * Approximation of the vanilla "noise caves" (1.18+): cheese, 3D spaghetti and noodle caves.
 *
 * <p>Same noise parameters as the vanilla noise router (first octave, amplitudes, scales, thresholds), but
 * evaluated with Bukkit's {@link PerlinNoiseGenerator} and without the terrain density, aquifers, entrances,
 * pillars or 2D spaghetti. The result is close in spirit to vanilla, not identical.
 */
final class CaveNoise {

    private static final double SPAGHETTI_LAYER_SCALE = 2.0;
    private static final double NOODLE_RIDGE_SCALE = 2.6666666666666665;
    /** Noodle caves only exist in this Y range (as in vanilla). */
    private static final int NOODLE_MIN_Y = -60;
    private static final int NOODLE_MAX_Y = 320;
    /** Caves stay this many blocks below the surface. */
    private static final int SURFACE_MARGIN = 8;

    private final Normal cheese;
    private final Normal spaghetti3dRarity;
    private final Normal spaghetti3dThickness;
    private final Normal spaghetti3d1;
    private final Normal spaghetti3d2;
    private final Normal noodleToggle;
    private final Normal noodleThickness;
    private final Normal noodleRidgeA;
    private final Normal noodleRidgeB;

    CaveNoise(long seed) {
        Random random = new Random(seed ^ 0x5DEECE66DL);
        cheese = new Normal(random, -8, 0.5, 1, 2, 1, 2, 1, 0, 2, 0);
        spaghetti3dRarity = new Normal(random, -11, 1);
        spaghetti3dThickness = new Normal(random, -8, 1);
        spaghetti3d1 = new Normal(random, -7, 1);
        spaghetti3d2 = new Normal(random, -7, 1);
        noodleToggle = new Normal(random, -8, 1);
        noodleThickness = new Normal(random, -8, 1);
        noodleRidgeA = new Normal(random, -7, 1);
        noodleRidgeB = new Normal(random, -7, 1);
    }

    /**
     * True if the block at this position is carved by a cheese, spaghetti or noodle cave.
     *
     * @param depth how many blocks the position is below the terrain surface (0 = the top block)
     */
    boolean isCave(int x, int y, int z, int depth) {
        return depth >= SURFACE_MARGIN && (isCheese(x, y, z) || isSpaghetti3d(x, y, z) || isNoodle(x, y, z));
    }

    /** Big irregular pockets: vanilla density 0.27 + noise is solid when positive, the noise sees Y squashed by 1.5. */
    private boolean isCheese(int x, int y, int z) {
        return 0.27 + cheese.sample(x, y / 1.5, z) < 0;
    }

    /** Thin winding tunnels: both of two noises are close to zero, the radius and the stretch vary slowly. */
    private boolean isSpaghetti3d(int x, int y, int z) {
        double thicknessUnit = (spaghetti3dThickness.sample(x, y, z) + 1) / 2;
        double thickness = lerp(clamp01(thicknessUnit), -0.065, -0.088);
        double rarity = spaghettiRarity(spaghetti3dRarity.sample(x * SPAGHETTI_LAYER_SCALE, y * SPAGHETTI_LAYER_SCALE,
                z * SPAGHETTI_LAYER_SCALE));
        double a = spaghetti3d1.sample(x / rarity, y / rarity, z / rarity);
        double b = spaghetti3d2.sample(x / rarity, y / rarity, z / rarity);
        return Math.max(Math.abs(a), Math.abs(b)) + thickness < 0;
    }

    /** Very thin tunnels, only where the toggle noise is negative. */
    private boolean isNoodle(int x, int y, int z) {
        if (y < NOODLE_MIN_Y || y > NOODLE_MAX_Y || noodleToggle.sample(x, y, z) >= 0) {
            return false;
        }
        double thickness = lerp(clamp01((noodleThickness.sample(x, y, z) + 1) / 2), -0.05, -0.1);
        double a = noodleRidgeA.sample(x * NOODLE_RIDGE_SCALE, y * NOODLE_RIDGE_SCALE, z * NOODLE_RIDGE_SCALE);
        double b = noodleRidgeB.sample(x * NOODLE_RIDGE_SCALE, y * NOODLE_RIDGE_SCALE, z * NOODLE_RIDGE_SCALE);
        return Math.max(Math.abs(a), Math.abs(b)) * 1.5 + thickness < 0;
    }

    private static double spaghettiRarity(double value) {
        if (value < -0.5) {
            return 0.75;
        }
        if (value < 0) {
            return 1.0;
        }
        return value < 0.5 ? 1.5 : 2.0;
    }

    private static double lerp(double t, double from, double to) {
        return from + t * (to - from);
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /**
     * Vanilla NormalNoise: two octave stacks, the second sampled at a slightly different frequency,
     * summed and scaled so that the deviation is about the same whatever the amplitudes.
     */
    private static final class Normal {
        private static final double SECOND_STACK_FACTOR = 1.0181268882175227;

        private final PerlinNoiseGenerator[] first;
        private final PerlinNoiseGenerator[] second;
        private final double[] frequency;
        private final double[] weight;
        private final double valueFactor;

        Normal(Random random, int firstOctave, double... amplitudes) {
            int n = amplitudes.length;
            first = new PerlinNoiseGenerator[n];
            second = new PerlinNoiseGenerator[n];
            frequency = new double[n];
            weight = new double[n];
            int lowest = -1;
            int highest = -1;
            double persistence = Math.pow(2, n - 1) / (Math.pow(2, n) - 1);
            for (int i = 0; i < n; i++) {
                frequency[i] = Math.pow(2, firstOctave + i);
                weight[i] = amplitudes[i] * persistence;
                persistence /= 2;
                if (amplitudes[i] != 0) {
                    first[i] = new PerlinNoiseGenerator(random.nextLong());
                    second[i] = new PerlinNoiseGenerator(random.nextLong());
                    if (lowest < 0) {
                        lowest = i;
                    }
                    highest = i;
                }
            }
            double expectedDeviation = 0.1 * (1 + 1.0 / (highest - lowest + 1));
            valueFactor = (1.0 / 6) / expectedDeviation;
        }

        double sample(double x, double y, double z) {
            return (stack(first, x, y, z) + stack(second, x * SECOND_STACK_FACTOR, y * SECOND_STACK_FACTOR,
                    z * SECOND_STACK_FACTOR)) * valueFactor;
        }

        private double stack(PerlinNoiseGenerator[] octaves, double x, double y, double z) {
            double sum = 0;
            for (int i = 0; i < octaves.length; i++) {
                if (octaves[i] != null) {
                    sum += weight[i] * octaves[i].noise(x * frequency[i], y * frequency[i], z * frequency[i]);
                }
            }
            return sum;
        }
    }
}
