package allyfleet.util;

import com.fs.starfarer.api.Global;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Writes AI decision log to Desktop/AllyFleet_AI.log so the player can
 * inspect what the fleet is thinking after a few minutes of gameplay.
 */
public class AILog {

    private static PrintWriter writer;
    private static boolean enabled = true;

    private static final String LOG_PATH = System.getProperty("user.home")
            + "/Desktop/AllyFleet_AI.log";

    static {
        try {
            writer = new PrintWriter(new FileWriter(LOG_PATH, false));
            writer.println("=== Ally Fleet AI Log ===");
            writer.println("Started: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            writer.println("(game time logged on first message)");
            writer.println();
        } catch (IOException e) {
            enabled = false;
        }
    }

    public static void log(String msg) {
        if (!enabled || writer == null) return;
        float days = 0;
        float day = 0;
        try {
            days = (float)Global.getSector().getClock().getElapsedDaysSince(0);
            day = Global.getSector().getClock().getDay();
        } catch (Exception ignored) {}
        writer.println(String.format("[%.1f] (Day %.0f) %s", days, day, msg));
        writer.flush();
    }

    public static void logTrade(String msg) { log("[TRADE] " + msg); }
    public static void logSupply(String msg) { log("[SUPPLY] " + msg); }
    public static void logAI(String msg) { log("[AI] " + msg); }
    public static void logShip(String msg) { log("[SHIP] " + msg); }

    public static void close() {
        if (writer != null) {
            writer.println();
            writer.println("=== Log closed ===");
            writer.close();
            writer = null;
        }
    }
}
