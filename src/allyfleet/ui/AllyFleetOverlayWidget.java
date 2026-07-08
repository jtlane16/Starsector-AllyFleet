package allyfleet.ui;

import allyfleet.AllyAction;
import allyfleet.AllyFleet;
import allyfleet.controllers.AllyFleetController;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AllyFleetOverlayWidget implements CampaignUIRenderingListener, EveryFrameScript {

    private static final float PAD = 6f, LINE_H = 18f, PANEL_W = 200f, PANEL_H = 160f;
    private static final float TOP_OFFSET = 40f, RIGHT_OFFSET = 10f;
    private float panelX, panelY;
    private final List<LabelEntry> entries = new ArrayList<>();
    private boolean done = false, wasDown = false;

    private static class LabelEntry {
        LabelAPI label; float x, y, w, h; Runnable click;
    }

    @Override public boolean isDone() { return done; }
    @Override public boolean runWhilePaused() { return false; }

    @Override
    public void advance(float amount) {
        entries.clear();
        AllyFleet fleet = getFleet();
        if (fleet == null) return;

        float sw = Global.getSettings().getScreenWidth();
        float sh = Global.getSettings().getScreenHeight();
        panelX = sw - PANEL_W - RIGHT_OFFSET;
        panelY = sh - TOP_OFFSET;
        CampaignFleetAPI cf = fleet.getFleet();
        String font = Fonts.ORBITRON_12;
        float y = panelY;

        // Row 1: Fleet name
        addEntry(fleet.getFleetName(), font, panelX + PAD, y - LINE_H, PANEL_W - 2*PAD, LINE_H,
                Misc.getBasePlayerColor(), null);

        // Row 2: Objective
        AllyAction current = currentObjective(fleet);
        addEntry("Objective: " + current.getDisplayName(), font,
                panelX + PAD, y - LINE_H * 2, PANEL_W - 2*PAD, LINE_H, Color.CYAN, null);

        // Row 3: Change objective button
        addEntry("> Change Objective <", font,
                panelX + PAD, y - LINE_H * 3, PANEL_W - 2*PAD, LINE_H,
                Color.ORANGE, () -> cycleObjective(fleet));

        // Row 4: Credits
        addEntry("$" + Misc.getFormat().format(fleet.getCredits()), font,
                panelX + PAD, y - LINE_H * 4, PANEL_W - 2*PAD, LINE_H, Color.YELLOW, null);

        // Row 5: Supplies (current / daily use)
        String supStr = "Supplies: " + (int)(cf != null ? cf.getCargo().getSupplies() : 0);
        if (cf != null) supStr += " (-" + (int)cf.getTotalSupplyCostPerDay() + "/d)";
        addEntry(supStr, font, panelX + PAD, y - LINE_H * 5, PANEL_W - 2*PAD, LINE_H,
                Color.WHITE, null);

        // Row 6: Fuel
        String fuelStr = "Fuel: " + (int)(cf != null ? cf.getCargo().getFuel() : 0);
        addEntry(fuelStr, font, panelX + PAD, y - LINE_H * 6, PANEL_W - 2*PAD, LINE_H,
                Color.WHITE, null);

        // Row 7: Status
        String st = fleet.isAlive() ? "Active" : "Respawning";
        addEntry("Status: " + st, font, panelX + PAD, y - LINE_H * 7, PANEL_W - 2*PAD, LINE_H,
                fleet.isAlive() ? Color.GREEN : Color.RED, null);

        // Mouse polling
        boolean down = Mouse.isButtonDown(0);
        if (down && !wasDown) {
            float mx = Mouse.getX(), my = Mouse.getY();
            for (LabelEntry e : entries) {
                if (e.click != null && mx >= e.x && mx <= e.x + e.w
                    && my >= e.y - e.h && my <= e.y) {
                    e.click.run(); break;
                }
            }
        }
        wasDown = down;
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

    private AllyAction currentObjective(AllyFleet fleet) {
        for (AllyAction a : AllyAction.values())
            if (fleet.getPriority(a) > 0) return a;
        return AllyAction.FOLLOW;
    }

    private void cycleObjective(AllyFleet fleet) {
        if (fleet == null) return;
        AllyAction[] all = AllyAction.values();
        AllyAction current = currentObjective(fleet);
        for (AllyAction a : all) fleet.setPriority(a, 0);
        int idx = Arrays.asList(all).indexOf(current);
        AllyAction next = all[(idx + 1) % all.length];
        fleet.setPriority(next, 75);
        Global.getSector().getCampaignUI().addMessage(
                fleet.getFleetName() + " -> " + next.getDisplayName(),
                Misc.getHighlightColor());
    }

    private AllyFleet getFleet() {
        List<AllyFleet> list = new ArrayList<>(AllyFleetController.getFleets());
        return list.isEmpty() ? null : list.get(0);
    }

    @Override public void renderInUICoordsBelowUI(ViewportAPI vp) {}

    @Override
    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI vp) {
        if (entries.isEmpty()) return;
        float alpha = vp.getAlphaMult();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glColor4f(0.08f, 0.08f, 0.12f, 0.70f * alpha);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(panelX, panelY - PANEL_H);
        GL11.glVertex2f(panelX + PANEL_W, panelY - PANEL_H);
        GL11.glVertex2f(panelX + PANEL_W, panelY);
        GL11.glVertex2f(panelX, panelY);
        GL11.glEnd();
        GL11.glColor4f(0.3f, 0.5f, 1.0f, 0.5f * alpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(panelX, panelY - PANEL_H);
        GL11.glVertex2f(panelX + PANEL_W, panelY - PANEL_H);
        GL11.glVertex2f(panelX + PANEL_W, panelY);
        GL11.glVertex2f(panelX, panelY);
        GL11.glEnd();
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
