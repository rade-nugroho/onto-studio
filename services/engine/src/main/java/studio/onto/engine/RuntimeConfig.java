package studio.onto.engine;

/**
 * Configuration lookup for optional sidecars. A system property wins over the
 * environment so tests can force a value off without touching the process env.
 * A blank property means unset.
 */
public final class RuntimeConfig {

    private RuntimeConfig() {
    }

    public static String get(String property, String envName) {
        String propertyValue = System.getProperty(property);
        if (propertyValue != null) {
            String trimmed = propertyValue.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return EnvFile.get(envName);
    }
}
