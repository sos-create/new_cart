package ru.soscreate.newcart;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerformanceBudgetTest {
    @Test
    void pausesBelowConfiguredTpsThreshold() {
        assertEquals(0, NewCartPlugin.layersForTps(17.99, 18.0, 19.5, 2));
    }

    @Test
    void performsOneLayerWhenTpsIsRecovering() {
        assertEquals(1, NewCartPlugin.layersForTps(18.0, 18.0, 19.5, 2));
        assertEquals(1, NewCartPlugin.layersForTps(19.49, 18.0, 19.5, 2));
    }

    @Test
    void performsAtMostTwoLayersAtFullTps() {
        assertEquals(2, NewCartPlugin.layersForTps(19.5, 18.0, 19.5, 2));
        assertEquals(2, NewCartPlugin.layersForTps(20.0, 18.0, 19.5, 10));
    }

    @Test
    void invalidTpsFailsSafeAndNeverIncreasesWorkAboveTwoLayers() {
        assertEquals(0, NewCartPlugin.layersForTps(Double.NaN, 18.0, 19.5, 2));
        assertEquals(0, NewCartPlugin.layersForTps(Double.POSITIVE_INFINITY, 18.0, 19.5, 2));
        assertEquals(1, NewCartPlugin.layersForTps(20.0, 18.0, 19.5, 0));
    }
}
