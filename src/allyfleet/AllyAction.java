package allyfleet;

/**
 * Objectives an ally fleet can be assigned.
 */
public enum AllyAction {
    FOLLOW("Follow / Escort"),
    TRADE("Trade & Supply");

    private final String displayName;
    AllyAction(String displayName) { this.displayName = displayName; }
    public String getDisplayName() { return displayName; }
}
