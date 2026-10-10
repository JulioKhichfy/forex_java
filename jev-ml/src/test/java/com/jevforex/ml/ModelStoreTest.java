package com.jevforex.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelStoreTest {

    @Test
    void gravaLeEPromove_mesmasProbabilidades(@TempDir Path lake) throws Exception {
        Random rnd = new Random(3);
        double[][] x = new double[500][2];
        int[] y = new int[500];
        for (int i = 0; i < x.length; i++) {
            x[i][0] = rnd.nextGaussian();
            x[i][1] = rnd.nextGaussian();
            y[i] = x[i][0] > 0.5 ? Dataset.UP : x[i][0] < -0.5 ? Dataset.DOWN : Dataset.FLAT;
        }
        ExperimentConfig.Gbm p = new ExperimentConfig.Gbm(30, 3, 8, 10, 0.1, 0.8);
        Gbm g = Gbm.fit(x, y, List.of("f1", "pair_EURUSD"), p, 1);
        Path v = lake.resolve("models/market=fx/20261010-000000");
        Files.createDirectories(v);
        g.save(v.resolve("A-60.ser"));
        var mf = new ModelStore.Manifest("20261010-000000", "v3", Instant.parse("2026-10-10T00:00:00Z"), "a", "b", p,
                Dataset.CLASSES, List.of(new ModelStore.Entry("A", 60, "A-60.ser", g.names(), 500, 0.5)), null, null);
        new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writeValue(v.resolve("manifest.json").toFile(), mf);

        ModelStore store = new ModelStore(lake);
        assertEquals(List.of("20261010-000000"), store.versions());
        assertTrue(store.champion().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.promote("não-existe", ""));
        store.promote("20261010-000000", "primeira versão");
        assertEquals("20261010-000000", store.champion().orElseThrow().version());

        Gbm back = store.load("20261010-000000").get("A", 60);
        assertEquals(List.of("f1", "pair_EURUSD"), back.names());
        double[][] probe = {{0.9, 1}, {-0.9, 1}, {0.0, 1}};
        double[][] a = g.predict(probe), b = back.predict(probe);
        for (int i = 0; i < probe.length; i++) assertArrayEquals(a[i], b[i], 1e-12);
    }
}
