package allyfleet.ui;

import allyfleet.AllyAction;
import allyfleet.AllyFleet;
import allyfleet.controllers.AllyFleetController;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact campaign-map widget — shows your ally fleet and lets you set its current objective.
 * Only one ally fleet exists at a time.
 *
 * Layout (top-right corner):
 *   [Fleet Name]
 *   Objective: FOLLOW  <- click to cycle
 *   Status: Active
 */
public class AllyFleetOverlayWidget implements CampaignUIRenderingListener, CampaignInputListener, EveryFrameScript {

    // ── Layout constants ───────────────────────────────────────────
    private static final float PAD = 6f;
    private static final float LINE_H = 18f;
    private static final float PANEL_W = 200f;
    private static final float TOP_MARGIN = 40f;   // below top bar
    private static final float RIGHT_MARGIN = 10f;

    // Computed each frame from screen size
    private float panelX, panelY, panelH;

    private final List<LabelEntry> entries = new ArrayList<>();
    private boolean done = false;

    private static class LabelEntry {
        LabelAPI label;
        float x, y, w, h;
        Runnable click;
    }

    // ── EveryFrameScript ──────────────────────────────────────────

    @Override public boolean isDone() { return done; }
    @Override public boolean runWhilePaused() { return false; }

    @Override
    public void advance(float amount) {
        entries.clear();
        AllyFleet fleet = getFleet();
        if (fleet == null) return;

        // Position: top-right, computed from viewport
        float screenW = Global.getSector().getViewport().getVisibleWidth();
        panelX = screenW - PANEL_W - RIGHT_MARGIN;
        panelY = Global.getSector().getViewport().getVisibleHeight() - TOP_MARGIN;
        panelH = LINE_H * 3 + PAD * 4;

        String font = Fonts.ORBITRON_12;
        float y = panelY; // top of panel

        // ── Header ──
        addEntry(fleet.getFleetName(), font, panelX + PAD, y - LINE_H, PANEL_W - 2*PAD, LINE_H,
                Misc.getBasePlayerColor(), null);

        // ── Objective (clickable cycle) ──
        AllyAction current = fleet.getHighestPriorityAction();
        if (current == null) { current = AllyAction.FOLLOW; fleet.setPriority(AllyAction.FOLLOW, 75); }
        String objText = "Objective: " + current.getDisplayName();
        addEntry(objText, font, panelX + PAD, y - LINE_H * 2, PANEL_W - 2*PAD, LINE_H,
                Color.CYAN, () -> cycleObjective(fleet));

        // ── Status ──
        String st = fleet.isAlive() ? "Active" : "Respawning";
        addEntry("Status: " + st, font, panelX + PAD, y - LINE_H * 3, PANEL_W - 2*PAD, LINE_H,
                fleet.isAlive() ? Color.GREEN : Color.RED, null);
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

        // Find current active objective
        AllyAction current = null;
        for (AllyAction a : all) {
            if (fleet.getPriority(a) > 0) { current = a; break; }
        }

        // Set all to 0, then set next one to 75
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

    // ── CampaignUIRenderingListener ───────────────────────────────

    @Override public void renderInUICoordsBelowUI(ViewportAPI vp) {}

    @Override
    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI vp) {
        AllyFleet fleet = getFleet();
        if (fleet == null || entries.isEmpty()) return;

        float alpha = vp.getAlphaMult();

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        // Background
        GL11.glColor4f(0.08f, 0.08f, 0.12f, 0.70f * alpha);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(panelX, panelY - panelH);
        GL11.glVertex2f(panelX + PANEL_W, panelY - panelH);
        GL11.glVertex2f(panelX + PANEL_W, panelY);
        GL11.glVertex2f(panelX, panelY);
        GL11.glEnd();

        // Border
        GL11.glColor4f(0.3f, 0.5f, 1.0f, 0.5f * alpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(panelX, panelY - panelH);
        GL11.glVertex2f(panelX + PANEL_W, panelY - panelH);
        GL11.glVertex2f(panelX + PANEL_W, panelY);
        GL11.glVertex2f(panelX, panelY);
        GL11.glEnd();

        // Clickable row highlight
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

        // Labels
        for (LabelEntry e : entries) {
            e.label.render(alpha);
        }
    }

    @Override public void renderInUICoordsAboveUIAndTooltips(ViewportAPI vp) {}

    // ── CampaignInputListener ─────────────────────────────────────

    @Override public int getListenerInputPriority() { return 5; }
    @Override public void processCampaignInputPreCore(List<InputEventAPI> events) {}
    @Override public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}

    @Override
    public void processCampaignInputPostCore(List<InputEventAPI> events) {
        if (entries.isEmpty()) return;
        for (InputEventAPI ev : events) {
            if (ev.isConsumed()) continue;
            if (ev.isMouseDownEvent()) {
                float mx = ev.getX();
                float my = ev.getY(); // OpenGL Y: 0 at bottom
                for (LabelEntry e : entries) {
                    if (e.click != null && mx >= e.x && mx <= e.x + e.w
                        && my >= e.y - e.h && my <= e.y) {
                        e.click.run();
                        ev.consume();
                        return;
                    }
                }
            }
        }
    }
}
