package allyfleet.util;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

/**
 * AI decision logger — writes to starsector.log with [AILog] tags
 * so you can grep for them. Can't write to desktop files because
 * Starsector sandboxes script code file access.
 *
 * Usage: AILog.trade("trade tick starting")
 * Find with: grep "\[AILog\]" starsector.log
 */
public class AILog {

    private static final Logger log = Global.getLogger(AILog.class);

    public static void logTrade(String msg)     { log.info("[AILog][TRADE] " + msg); }
    public static void logSupply(String msg)    { log.info("[AILog][SUPPLY] " + msg); }
    public static void logAI(String msg)        { log.info("[AILog][AI] " + msg); }
    public static void logShip(String msg)      { log.info("[AILog][SHIP] " + msg); }
}
