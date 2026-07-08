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
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Campaign map overlay widget — shows ally fleet status + clickable priorities.
 *
 * Renders a small semi-transparent panel in the top-left of the screen.
 * Click a priority number to cycle: 0 -> 25 -> 50 -> 75 -> 100 -> 0.
 * Click the fleet name to cycle through multiple fleets.
 */
public class AllyFleetOverlayWidget implements CampaignUIRenderingListener, CampaignInputListener, EveryFrameScript {

    // ── Layout ────────────────────────────────────────────────────
    private static final float PANEL_X = 10f,  PANEL_Y = 80f;
    private static final float PANEL_W = 230f, PANEL_H = 200f;
    private static final float LINE_H = 18f;
    private static final float PAD   = 5f;
    private static final int[] LEVELS = {0, 25, 50, 75, 100};

    private int selectedIdx = 0;
    private boolean done = false;

    // Cached LabelAPI objects rebuilt every frame
    private final List<LabelEntry> entries = new ArrayList<>();

    private static class LabelEntry {
        LabelAPI label;
        float x, y, w, h;
        Runnable click;
    }

    // ── EveryFrameScript ─────────────────────────────────────────

    @Override public boolean isDone() { return done; }
    @Override public boolean runWhilePaused() { return false; }

    @Override
    public void advance(float amount) {
        // Rebuild labels every frame using the fast-mutating API
        entries.clear();
        int count = AllyFleetController.getFleetCount();
        if (count == 0) return;
        if (selectedIdx >= count) selectedIdx = 0;

        AllyFleet fleet = new ArrayList<>(AllyFleetController.getFleets()).get(selectedIdx);
        if (fleet == null) return;

        float y = PANEL_Y + PAD;
        String font = Fonts.ORBITRON_12;

        // ── Header: fleet name (clickable) ──
        addEntry(fleet.getFleetName(), font, PANEL_X + PAD, y += LINE_H, PANEL_W - 2*PAD, LINE_H,
                Misc.getBasePlayerColor(), this::cycleFleet);

        // ── Status ──
        String st = fleet.isAlive() ? "Active" : "Respawning";
        addEntry("Status: " + st, font, PANEL_X + PAD, y += LINE_H, PANEL_W - 2*PAD, LINE_H,
                fleet.isAlive() ? Color.GREEN : Color.RED, null);

        // ── Credits ──
        addEntry("$" + Misc.getFormat().format(fleet.getCredits()), font,
                PANEL_X + PAD, y += LINE_H, PANEL_W - 2*PAD, LINE_H,
                Color.ORANGE, null);

        y += 4f; // spacer

        // ── Priority rows ──
        for (AllyAction a : AllyAction.values()) {
            int p = fleet.getPriority(a);
            Color c = p >= 75 ? Color.CYAN : p >= 25 ? Color.WHITE : Color.GRAY;
            AllyAction cap = a;
            addEntry(a.getDisplayName() + ": " + p, font,
                    PANEL_X + PAD, y += LINE_H, PANEL_W - 2*PAD, LINE_H, c,
                    () -> { cyclePrio(fleet, cap); });
        }
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

    private void cyclePrio(AllyFleet fleet, AllyAction action) {
        if (fleet == null) return;
        int cur = fleet.getPriority(action);
        int nxt = 0;
        for (int i = 0; i < LEVELS.length - 1; i++)
            if (cur == LEVELS[i]) { nxt = LEVELS[i + 1]; break; }
        fleet.setPriority(action, nxt);
        Global.getSector().getCampaignUI().addMessage(
            fleet.getFleetName() + ": " + action.getDisplayName() + " > " + nxt,
            Misc.getHighlightColor());
    }

    private void cycleFleet() {
        int n = AllyFleetController.getFleetCount();
        if (n > 0) selectedIdx = (selectedIdx + 1) % n;
    }

    // ── CampaignUIRenderingListener ─────────────────────────────

    @Override public void renderInUICoordsBelowUI(ViewportAPI vp) {}

    @Override
    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI vp) {
        if (entries.isEmpty()) return;

        float alpha = vp.getAlphaMult();

        // ── Background ──
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glColor4f(0.08f, 0.08f, 0.12f, 0.70f * alpha);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(PANEL_X, PANEL_Y);
        GL11.glVertex2f(PANEL_X + PANEL_W, PANEL_Y);
        GL11.glVertex2f(PANEL_X + PANEL_W, PANEL_Y + PANEL_H);
        GL11.glVertex2f(PANEL_X, PANEL_Y + PANEL_H);
        GL11.glEnd();

        // ── Border ──
        GL11.glColor4f(0.3f, 0.5f, 1.0f, 0.5f * alpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(PANEL_X, PANEL_Y);
        GL11.glVertex2f(PANEL_X + PANEL_W, PANEL_Y);
        GL11.glVertex2f(PANEL_X + PANEL_W, PANEL_Y + PANEL_H);
        GL11.glVertex2f(PANEL_X, PANEL_Y + PANEL_H);
        GL11.glEnd();

        // ── Button rects ──
        for (LabelEntry e : entries) {
            if (e.click != null) {
                GL11.glColor4f(0.18f, 0.25f, 0.40f, 0.40f * alpha);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(e.x, e.y - e.h);
                GL11.glVertex2f(e.x + e.w, e.y - e.h);
                GL11.glVertex2f(e.x + e.w, e.y);
                GL11.glVertex2f(e.x, e.y);
                GL11.glEnd();
            }
        }

        GL11.glPopAttrib();

        // ── Labels ──
        for (LabelEntry e : entries) {
            e.label.render(alpha);
        }
    }

    @Override public void renderInUICoordsAboveUIAndTooltips(ViewportAPI vp) {}

    // ── CampaignInputListener ────────────────────────────────────

    @Override public int getListenerInputPriority() { return 5; }
    @Override public void processCampaignInputPreCore(List<InputEventAPI> events) {}
    @Override public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}

    @Override
    public void processCampaignInputPostCore(List<InputEventAPI> events) {
        if (entries.isEmpty()) return;
        float screenH = Global.getSector().getViewport().getVisibleHeight();

        for (InputEventAPI ev : events) {
            if (ev.isConsumed()) continue;
            if (ev.isMouseDownEvent()) {
                float mx = ev.getX();
                float my = screenH - ev.getY();
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
