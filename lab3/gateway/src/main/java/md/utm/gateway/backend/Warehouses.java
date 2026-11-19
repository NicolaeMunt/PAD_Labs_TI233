package md.utm.gateway.backend;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Warehouse names. A warehouse is one database (gamevault_{name}) with 1..N API instances
 * in front of it. The name ends up in a header, a container name and a database name,
 * so it is limited to lower-case letters, digits and dashes.
 */
public final class Warehouses {

    /** Request header that picks the warehouse; requests without it go to the default one. */
    public static final String HEADER = "X-Warehouse";

    /** Used when gateway.default-warehouse is not set and for backends listed without a warehouse. */
    public static final String DEFAULT = "warehouse-1";

    private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?");

    private Warehouses() { }

    /** Trims and lower-cases; returns null if what is left is not a valid name. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String name = raw.trim().toLowerCase(Locale.ROOT);
        return NAME.matcher(name).matches() ? name : null;
    }

    public static String requireValid(String raw) {
        String name = normalize(raw);
        if (name == null) {
            throw new IllegalArgumentException("Invalid warehouse name '" + raw
                    + "': use 1-32 lower-case letters, digits or dashes, not starting or ending with a dash");
        }
        return name;
    }
}
