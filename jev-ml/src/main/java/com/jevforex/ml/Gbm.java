package com.jevforex.ml;

import smile.classification.GradientTreeBoost;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.DoubleVector;
import smile.data.vector.IntVector;
import smile.data.vector.ValueVector;
import smile.math.MathEx;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gradient boosting de árvores do Smile, 3 classes, com probabilidades. */
final class Gbm {

    private static final String LABEL = "y";

    private final GradientTreeBoost model;
    private final List<String> names;

    private Gbm(GradientTreeBoost model, List<String> names) {
        this.model = model;
        this.names = names;
    }

    static Gbm fit(double[][] x, int[] y, List<String> names, ExperimentConfig.Gbm p, long seed) {
        MathEx.setSeed(seed);   // subsample reprodutível (a semente vale para a thread atual)
        var opts = new GradientTreeBoost.Options(p.ntrees(), p.maxDepth(), p.maxNodes(), p.nodeSize(),
                p.shrinkage(), p.subsample(), null, null);
        return new Gbm(GradientTreeBoost.fit(Formula.lhs(LABEL), frame(x, y, names), opts), names);
    }

    /** P(QUEDA), P(LATERAL), P(ALTA) para cada linha. */
    double[][] predict(double[][] x) {
        DataFrame df = frame(x, new int[x.length], names);
        double[][] out = new double[x.length][Dataset.CLASSES.size()];
        for (int i = 0; i < x.length; i++) model.predict(df.get(i), out[i]);
        return out;
    }

    /** Importância de cada feature (redução de perda somada nas árvores), normalizada para somar 1. */
    Map<String, Double> importance() {
        double[] imp = model.importance();
        double total = 0;
        for (double v : imp) total += v;
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < imp.length && i < names.size(); i++) out.put(names.get(i), total > 0 ? imp[i] / total : 0);
        return out;
    }

    private static DataFrame frame(double[][] x, int[] y, List<String> names) {
        ValueVector[] cols = new ValueVector[names.size() + 1];
        for (int j = 0; j < names.size(); j++) {
            double[] c = new double[x.length];
            for (int i = 0; i < x.length; i++) c[i] = x[i][j];
            cols[j] = new DoubleVector(names.get(j), c);
        }
        cols[names.size()] = new IntVector(LABEL, y);
        return new DataFrame(cols);
    }
}
