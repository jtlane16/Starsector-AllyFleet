package allyfleet;

/**
 * Actions an ally fleet can perform, with default priority weights.
 */
public enum AllyAction {
    FOLLOW("Follow / Escort Player"),
    DEFEND("Defend Colonies"),
    TRADE("Trade & Supply"),
    PATROL("Patrol Systems"),
    ATTACK("Attack Targets");

    private final String displayName;

    AllyAction(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
