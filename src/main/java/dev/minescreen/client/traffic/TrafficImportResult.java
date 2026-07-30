package dev.minescreen.client.traffic;

/** Result of importing a manifest, Rail Map Painter project, or generator script. */
public record TrafficImportResult(String templateId, Defaults defaults, String sourceKind,
        int stationCount, int lineCount) {
    public TrafficImportResult {
        defaults = defaults == null ? Defaults.EMPTY : defaults;
        sourceKind = sourceKind == null ? "manifest" : sourceKind;
    }

    public record Defaults(String line, String destination, String currentStop, String nextStop,
            String eta, String status) {
        public static final Defaults EMPTY = new Defaults("", "", "", "", "", "");

        public Defaults {
            line = safe(line);
            destination = safe(destination);
            currentStop = safe(currentStop);
            nextStop = safe(nextStop);
            eta = safe(eta);
            status = safe(status);
        }

        public boolean present() {
            return !line.isBlank() || !destination.isBlank() || !currentStop.isBlank()
                    || !nextStop.isBlank() || !eta.isBlank() || !status.isBlank();
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }
}
