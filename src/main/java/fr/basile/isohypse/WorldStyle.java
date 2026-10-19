package fr.basile.isohypse;

import java.util.Locale;
import java.util.Optional;

/** The extra level-type values recognized in server.properties. */
public enum WorldStyle {
    VANILLA_PLUS("Vanilla+"),
    REALISTIC("Realistic"),
    FAIRY("Fairy");

    private static final String NAMESPACE = "isohypse:";

    private final String levelType;

    WorldStyle(String levelType) {
        this.levelType = levelType;
    }

    public String levelType() {
        return levelType;
    }

    /** Case-insensitive, optional "isohypse:" prefix. */
    public static Optional<WorldStyle> fromLevelType(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith(NAMESPACE)) {
            v = v.substring(NAMESPACE.length());
        }
        for (WorldStyle style : values()) {
            if (style.levelType.toLowerCase(Locale.ROOT).equals(v)) {
                return Optional.of(style);
            }
        }
        return Optional.empty();
    }
}
