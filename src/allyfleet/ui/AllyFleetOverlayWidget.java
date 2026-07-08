package allyfleet.ui;

import allyfleet.AllyAction;
import allyfleet.AllyFleet;
import allyfleet.controllers.AllyFleetController;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Campaign-map widget — top-right corner, click objective to cycle.
 * Positioned in screen-pixel space (not world space) so zoom doesn't move it.
 * Clicks detected via Mouse polling in advance() since CampaignInputListener
 * dispatch through ListenerManagerAPI is unreliable.
 */
public class AllyFleetOverlayWidget implements CampaignUIRenderingListener, EveryFrameScript {

    // Layout constants
    private static final float PAD = 6f;
    private static final float LINE_H = 18f;
    private static final float PANEL_W = 200f;
    private static final float PANEL_H = 95f;  // 4 rows + padding
    private static final float TOP_OFFSET = 40f;
    private static final float RIGHT_OFFSET = 10f;
    private float panelX, panelY;
    private final List<LabelEntry> entries = new ArrayList<>();
    private boolean done = false;

    // Click debounce
    private boolean wasDown = false;

    private static class LabelEntry {
        LabelAPI label;
        float x, y, w, h;
        Runnable click;
    }

    @Override public boolean isDone() { return done; }
    @Override public boolean runWhilePaused() { return false; }

    @Override
    public void advance(float amount) {
        entries.clear();
        AllyFleet fleet = getFleet();
        if (fleet == null) return;

        // Fixed screen-space position (not affected by zoom)
        float sw = Global.getSettings().getScreenWidth();
        float sh = Global.getSettings().getScreenHeight();
        panelX = sw - PANEL_W - RIGHT_OFFSET;
        panelY = sh - TOP_OFFSET;

        String font = Fonts.ORBITRON_12;
        float y = panelY;

        // Row 1: Fleet name
        addEntry(fleet.getFleetName(), font, panelX + PAD, y - LINE_H, PANEL_W - 2*PAD, LINE_H,
                Misc.getBasePlayerColor(), null);

        // Row 2: Current objective (info only)
        AllyAction current = currentObjective(fleet);
        addEntry("Objective: " + current.getDisplayName(), font,
                panelX + PAD, y - LINE_H * 2, PANEL_W - 2*PAD, LINE_H,
                Color.CYAN, null);

        // Row 3: Button — "> Change Objective"
        addEntry("> Change Objective <", font,
                panelX + PAD, y - LINE_H * 3, PANEL_W - 2*PAD, LINE_H,
                Color.ORANGE, () -> cycleObjective(fleet));

        // Row 4: Status
        String st = fleet.isAlive() ? "Active" : "Respawning";
        addEntry("Status: " + st, font, panelX + PAD, y - LINE_H * 4, PANEL_W - 2*PAD, LINE_H,
                fleet.isAlive() ? Color.GREEN : Color.RED, null);

        // ── Mouse click detection ──────────────────────────────────
        boolean down = Mouse.isButtonDown(0);
        if (down && !wasDown) { // rising edge
            float mx = Mouse.getX();  // GL coords: 0 at bottom
            float my = Mouse.getY();
            for (LabelEntry e : entries) {
                if (e.click != null && mx >= e.x && mx <= e.x + e.w
                    && my >= e.y - e.h && my <= e.y) {
                    e.click.run();
                    break;
                }
            }
        }
        wasDown = down;
    }

    private AllyAction currentObjective(AllyFleet fleet) {
        for (AllyAction a : AllyAction.values())
            if (fleet.getPriority(a) > 0) return a;
        return AllyAction.FOLLOW;
    }

    private void addEntry(String text, String font, float x, float y, float w, float h,
                          Color color, Runnable onClick) {
        LabelAPI label = Global.getSettings().createLabel(text, font);
        label.setColor(color);
        label.getPosition().setLocation(x, y);
        label.getPosition().setSize(w, h);
        LabelEntry e = new LabelEntry();
        e.label = label; e.x = x; e.y = y; e.w = w; e.h = h; e.click = onClick;
        entries.add(e);
    }

    private void cycleObjective(AllyFleet fleet) {
        if (fleet == null) return;
        AllyAction[] all = AllyAction.values();
        AllyAction current = null;
        for (AllyAction a : all) { if (fleet.getPriority(a) > 0) { current = a; break; } }
        for (AllyAction a : all) fleet.setPriority(a, 0);
        AllyAction next = all[0];
        if (current != null) {
            int idx = java.util.Arrays.asList(all).indexOf(current);
            next = all[(idx + 1) % all.length];
        }
        fleet.setPriority(next, 75);
        Global.getSector().getCampaignUI().addMessage(
                fleet.getFleetName() + " -> " + next.getDisplayName(),
                Misc.getHighlightColor());
    }

    private AllyFleet getFleet() {
        List<AllyFleet> list = new ArrayList<>(AllyFleetController.getFleets());
        return list.isEmpty() ? null : list.get(0);
    }

    // ── Rendering ─────────────────────────────────────────────────

    @Override public void renderInUICoordsBelowUI(ViewportAPI vp) {}

    @Override
    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI vp) {
        if (entries.isEmpty()) return;
        float alpha = vp.getAlphaMult();

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        // Background
        GL11.glColor4f(0.08f, 0.08f, 0.12f, 0.70f * alpha);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(panelX, panelY - PANEL_H);
        GL11.glVertex2f(panelX + PANEL_W, panelY - PANEL_H);
        GL11.glVertex2f(panelX + PANEL_W, panelY);
        GL11.glVertex2f(panelX, panelY);
        GL11.glEnd();

        // Border
        GL11.glColor4f(0.3f, 0.5f, 1.0f, 0.5f * alpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(panelX, panelY - PANEL_H);
        GL11.glVertex2f(panelX + PANEL_W, panelY - PANEL_H);
        GL11.glVertex2f(panelX + PANEL_W, panelY);
        GL11.glVertex2f(panelX, panelY);
        GL11.glEnd();

        // Highlight clickable row
        for (LabelEntry e : entries) {
            if (e.click != null) {
                GL11.glColor4f(0.18f, 0.25f, 0.40f, 0.35f * alpha);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(e.x, e.y - e.h);
                GL11.glVertex2f(e.x + e.w, e.y - e.h);
                GL11.glVertex2f(e.x + e.w, e.y);
                GL11.glVertex2f(e.x, e.y);
                GL11.glEnd();
            }
        }

        GL11.glPopAttrib();

        for (LabelEntry e : entries) e.label.render(alpha);
    }

    @Override public void renderInUICoordsAboveUIAndTooltips(ViewportAPI vp) {}
}
