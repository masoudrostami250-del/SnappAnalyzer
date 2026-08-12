package com.snapfloat;

public final class TripRuleEngine {
    private TripRuleEngine() {}

    public static final double MIN_TOMAN_PER_KM = 12000.0;

    public static Result analyze(long fareToman, long distanceMeters) {
        if (fareToman < 0 || distanceMeters <= 0) {
            return new Result(false, true, 0, 0);
        }

        // کمتر از یک کیلومتر، دقیقاً یک کیلومتر محاسباتی است.
        double km = Math.max(1.0, distanceMeters / 1000.0);
        double tomanPerKm = fareToman / km;
        boolean good = tomanPerKm >= MIN_TOMAN_PER_KM;

        return new Result(good, false, km, tomanPerKm);
    }

    public static final class Result {
        public final boolean good;
        public final boolean unknown;
        public final double calculationKm;
        public final double tomanPerKm;

        Result(boolean good, boolean unknown, double calculationKm, double tomanPerKm) {
            this.good = good;
            this.unknown = unknown;
            this.calculationKm = calculationKm;
            this.tomanPerKm = tomanPerKm;
        }
    }
}
