package ru.soscreate.newcart;

/**
 * Pure TPS-to-work-budget policy, deliberately independent of Bukkit so it can
 * be unit-tested without loading server API classes.
 */
final class TpsWorkBudget {
    private static final int MAX_ALLOWED_LAYERS = 2;

    private TpsWorkBudget() {
    }

    static int layersForTps(double tps, double pauseBelow, double fullSpeedAt, int maximumLayers) {
        if (!Double.isFinite(tps) || !Double.isFinite(pauseBelow) || !Double.isFinite(fullSpeedAt)
                || tps < pauseBelow) {
            return 0;
        }
        if (tps < fullSpeedAt) {
            return 1;
        }
        return Math.max(1, Math.min(MAX_ALLOWED_LAYERS, maximumLayers));
    }
}
