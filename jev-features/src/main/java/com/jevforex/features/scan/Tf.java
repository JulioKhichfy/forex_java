package com.jevforex.features.scan;

/** Períodos do radar. D1 = dia UTC (o domingo à noite do forex entra na segunda). */
public enum Tf {
    M5(5), M15(15), H1(60), H4(240), D1(1440);

    public final int minutes;

    Tf(int minutes) {
        this.minutes = minutes;
    }
}
