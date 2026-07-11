package com.reefbot.service;

import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.ZoneType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Generates a 700×480 isometric pixel-art map of the player's island.
 *
 * Grid: 12×12 isometric tiles, TW=44, TH=22, BH=11.
 * Zone visibility: matches ZoneType.isUnlocked(devPoints).
 * Locked zones are rendered in grey with dimmed objects.
 *
 * Called by /island command in ReefBot.
 */
@Slf4j
@Service
public class IslandMapGenerator {

    // ── Canvas ────────────────────────────────────────────────────────────────
    private static final int W      = 700;
    private static final int H      = 480;
    private static final int HDR_H  = 52;
    private static final int FOOT_H = 38;
    private static final int MAP_Y  = HDR_H;
    private static final int MAP_H  = H - HDR_H - FOOT_H;

    // ── Isometric tile params ─────────────────────────────────────────────────
    private static final int TW = 44;   // full diamond width
    private static final int TH = 22;   // diamond half-height (TW/2)
    private static final int BH = 11;   // block side height per elevation unit

    // ── Grid ──────────────────────────────────────────────────────────────────
    private static final int G = 12;

    // Zone IDs (must match ZoneType enum order used in isUnlocked())
    private static final int Z_WATER  = 0;
    private static final int Z_SHORE  = 1;
    private static final int Z_FOREST = 2;
    private static final int Z_HILLS  = 3;
    private static final int Z_SETTLE = 4;
    private static final int Z_PLAINS = 5;
    private static final int Z_PORT   = 6;

    /**
     * Zone grid — which zone each tile belongs to.
     * 0=water, 1=shore, 2=forest, 3=hills, 4=settlement, 5=plains, 6=port
     */
    private static final int[][] ZONES = {
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0},
        {0, 0, 0, 1, 1, 2, 2, 1, 3, 3, 0, 0},
        {0, 0, 1, 1, 2, 2, 2, 1, 3, 3, 1, 0},
        {0, 0, 1, 2, 2, 2, 1, 4, 4, 1, 0, 0},
        {0, 6, 1, 2, 1, 4, 4, 1, 5, 5, 1, 0},
        {0, 6, 1, 1, 4, 4, 1, 5, 5, 1, 0, 0},
        {0, 6, 1, 1, 4, 1, 5, 5, 1, 0, 0, 0},
        {0, 6, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0},
        {0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
    };

    /**
     * Elevation grid — how many blocks tall each tile is.
     * 0 = flat (water/sand), 1 = raised, 2 = cliff
     */
    private static final int[][] ELEV = {
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 1, 2, 2, 1, 1, 1, 0, 0},
        {0, 0, 0, 1, 2, 2, 2, 1, 1, 2, 1, 0},
        {0, 0, 1, 1, 2, 2, 1, 1, 1, 1, 0, 0},
        {0, 0, 1, 2, 1, 1, 1, 1, 1, 1, 1, 0},
        {0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0},
        {0, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0},
        {0, 0, 0, 1, 1, 1, 1, 1, 0, 0, 0, 0},
        {0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
    };

    /**
     * Objects: {row, col, type}
     * types: 0=tree, 1=rock, 2=palm, 3=hut, 4=dock
     */
    private static final int[][] OBJECTS = {
        // Forest trees
        {2, 5, 0}, {2, 6, 0},
        {3, 4, 0}, {3, 5, 0}, {3, 6, 0},
        {4, 3, 0}, {4, 4, 0},
        {5, 3, 0},
        // Hills rocks
        {2, 8, 1}, {2, 9, 1},
        {3, 9, 1},
        // Shore palms
        {1, 5, 2}, {1, 7, 2},
        {9, 4, 2},
        // Settlement huts
        {5, 5, 3}, {6, 4, 3}, {7, 3, 3},
        // Port docks
        {5, 1, 4}, {6, 1, 4}, {7, 1, 4}, {8, 1, 4},
    };

    /** Zone label anchor tile {row, col} + label text (no emoji for reliable rendering). */
    private static final Object[][] ZONE_LABELS = {
        {8, 4,  Z_SHORE,  "Берег"},
        {3, 5,  Z_FOREST, "Лес"},
        {3, 9,  Z_HILLS,  "Холмы"},
        {6, 4,  Z_SETTLE, "Поселение"},
        {6, 8,  Z_PLAINS, "Равнина"},
        {7, 1,  Z_PORT,   "Порт"},
    };

    // ── Colors: {topFace, leftFace, rightFace} ────────────────────────────────
    private static final int[][] ZONE_COLORS = {
        {0x1c5498, 0x0e2e60, 0x061430},   // 0: water placeholder
        {0xdec56a, 0xae9240, 0x7e6820},   // 1: shore (sand)
        {0x2c6e1c, 0x144806, 0x072600},   // 2: forest (grass)
        {0x787080, 0x504858, 0x2e2830},   // 3: hills (stone)
        {0x987050, 0x6e4a2c, 0x4a2e0e},   // 4: settlement (dirt)
        {0x5ea830, 0x3c7212, 0x1e4800},   // 5: plains (light grass)
        {0x988058, 0x6a5832, 0x3e300e},   // 6: port (old wood)
    };
    private static final int[] WATER_TOP   = {0x1e5aaa, 0x0e3060, 0x061830};
    private static final int[] LOCKED_CLR  = {0x3a3848, 0x22202c, 0x101018};

    // ── UI colors ─────────────────────────────────────────────────────────────
    private static final Color BG      = c(0x0d1b2a);
    private static final Color HDR_BG  = c(0x0a3055);
    private static final Color FOOT_BG = c(0x07111e);
    private static final Color BORDER  = c(0x1a3050);
    private static final Color ACCENT  = c(0x4a9eff);
    private static final Color TXT_PRI = c(0xd0eaff);
    private static final Color TXT_SEC = c(0x4a7aaa);
    private static final Color TXT_DIM = c(0x2a5a8a);

    private static Color c(int rgb) { return new Color(rgb); }

    // ── Public API ────────────────────────────────────────────────────────────

    public byte[] generate(Player player, Island island) {
        System.setProperty("java.awt.headless", "true");

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        applyHints(g);

        int devPts = island.getDevPoints() != null ? island.getDevPoints() : 0;

        // Background
        g.setColor(BG);
        g.fillRect(0, 0, W, H);

        // Water background in map area
        drawWaterBackground(g);

        // Isometric grid origin: tile (5,5) appears at map center
        int originX = W / 2 + 8;
        int originY = MAP_Y + MAP_H / 2 - (5 + 5) * (TH / 2) + 14;

        // Render tiles back-to-front (painter's algorithm)
        for (int sum = 0; sum < 2 * G; sum++) {
            for (int row = Math.max(0, sum - G + 1); row <= Math.min(G - 1, sum); row++) {
                int col = sum - row;
                if (col < 0 || col >= G) continue;
                int sx = originX + (col - row) * (TW / 2);
                int sy = originY + (col + row) * (TH / 2);
                renderTile(g, sx, sy, ZONES[row][col], ELEV[row][col], devPts);
            }
        }

        // Render objects back-to-front
        for (int sum = 0; sum < 2 * G; sum++) {
            for (int[] obj : OBJECTS) {
                if (obj[0] + obj[1] != sum) continue;
                int row = obj[0], col = obj[1];
                int sx = originX + (col - row) * (TW / 2);
                int sy = originY + (col + row) * (TH / 2) - ELEV[row][col] * BH;
                boolean locked = !isUnlocked(ZONES[row][col], devPts);
                renderObject(g, sx, sy, obj[2], locked);
            }
        }

        // Zone labels
        drawZoneLabels(g, originX, originY, devPts);

        // Header + footer
        drawHeader(g, player, island, devPts);
        drawFooter(g, devPts);

        // Outer border
        g.setColor(c(0x1e3a5f));
        g.setStroke(new BasicStroke(2f));
        g.drawRect(1, 1, W - 3, H - 3);
        drawCorners(g);

        g.dispose();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate island map", e);
        }
    }

    // ── Tile rendering ────────────────────────────────────────────────────────

    private void renderTile(Graphics2D g, int sx, int sy, int zone, int elev, int devPts) {
        boolean locked = !isUnlocked(zone, devPts);

        if (zone == Z_WATER) {
            // Water: flat diamond only
            fillDiamond(g, sx, sy, c(WATER_TOP[0]));
            g.setColor(c(0x2870c0));
            g.setStroke(new BasicStroke(0.5f));
            // Subtle wave hint
            g.drawLine(sx - TW / 4, sy - 1, sx + TW / 4, sy - 1);
            return;
        }

        int[] clr = locked ? LOCKED_CLR : ZONE_COLORS[zone];
        int topY  = sy - elev * BH;

        // Side faces (below top face)
        if (elev > 0) {
            int drop = elev * BH;
            // Left face
            int[] lxp = {sx - TW/2, sx, sx, sx - TW/2};
            int[] lyp = {sy, sy + TH/2, sy + TH/2 + drop, sy + drop};
            g.setColor(c(clr[1]));
            g.fillPolygon(lxp, lyp, 4);
            // Right face
            int[] rxp = {sx, sx + TW/2, sx + TW/2, sx};
            int[] ryp = {sy + TH/2, sy, sy + drop, sy + TH/2 + drop};
            g.setColor(c(clr[2]));
            g.fillPolygon(rxp, ryp, 4);
        }

        // Top face
        fillDiamond(g, sx, topY, c(clr[0]));

        // Subtle top-edge highlight (non-locked only)
        if (!locked && elev > 0) {
            g.setColor(new Color(255, 255, 255, 22));
            g.setStroke(new BasicStroke(1f));
            g.drawLine(sx - TW/2, topY, sx, topY - TH/2);
            g.drawLine(sx, topY - TH/2, sx + TW/2, topY);
        }

        // Tile grid line
        g.setColor(new Color(0, 0, 0, 30));
        g.setStroke(new BasicStroke(0.5f));
        g.drawLine(sx - TW/2, topY, sx, topY - TH/2);
        g.drawLine(sx, topY - TH/2, sx + TW/2, topY);
        g.drawLine(sx + TW/2, topY, sx, topY + TH/2);
        g.drawLine(sx, topY + TH/2, sx - TW/2, topY);

        // Lock overlay — darker tint for locked tiles
        if (locked) {
            fillDiamond(g, sx, topY, new Color(0, 0, 0, 60));
        }
    }

    private void fillDiamond(Graphics2D g, int cx, int cy, Color color) {
        int[] xp = {cx - TW/2, cx, cx + TW/2, cx};
        int[] yp = {cy, cy - TH/2, cy, cy + TH/2};
        g.setColor(color);
        g.fillPolygon(xp, yp, 4);
    }

    // ── Object rendering ──────────────────────────────────────────────────────

    private void renderObject(Graphics2D g, int sx, int sy, int type, boolean locked) {
        if (locked) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.32f));
        }
        switch (type) {
            case 0 -> drawTree(g, sx, sy);
            case 1 -> drawRock(g, sx, sy);
            case 2 -> drawPalm(g, sx, sy);
            case 3 -> drawHut(g, sx, sy);
            case 4 -> drawDock(g, sx, sy);
        }
        if (locked) {
            g.setComposite(AlphaComposite.SrcOver);
        }
    }

    private void drawTree(Graphics2D g, int sx, int sy) {
        // Trunk
        g.setColor(c(0x6a3e18));
        g.setStroke(new BasicStroke(3f));
        g.drawLine(sx, sy - 4, sx + 1, sy - 18);
        // Crown layers
        g.setColor(c(0x1a5c0c));
        g.fillOval(sx - 11, sy - 34, 22, 20);
        g.setColor(c(0x228214));
        g.fillOval(sx - 8, sy - 32, 17, 17);
        g.setColor(c(0x2ea41c));
        g.fillOval(sx - 5, sy - 30, 10, 10);
        g.setStroke(new BasicStroke(1f));
    }

    private void drawRock(Graphics2D g, int sx, int sy) {
        g.setColor(c(0x5a5868));
        g.fillOval(sx - 9, sy - 9, 16, 9);
        g.setColor(c(0x787088));
        g.fillOval(sx - 7, sy - 11, 11, 8);
        g.setColor(c(0x4a4858));
        g.fillOval(sx + 2, sy - 7, 8, 5);
        g.setColor(c(0x8a8898));
        g.fillOval(sx - 6, sy - 12, 7, 5);
    }

    private void drawPalm(Graphics2D g, int sx, int sy) {
        // Curved trunk
        g.setColor(c(0x9a7240));
        g.setStroke(new BasicStroke(3f));
        GeneralPath trunk = new GeneralPath();
        trunk.moveTo(sx, sy - 2);
        trunk.curveTo(sx + 4, sy - 10, sx - 3, sy - 20, sx + 5, sy - 30);
        g.draw(trunk);
        // Fronds
        g.setColor(c(0x1a7a0c));
        g.setStroke(new BasicStroke(2f));
        int bx = sx + 5, by = sy - 30;
        for (int a : new int[]{0, 45, 90, 135, 180, 225}) {
            double rad = Math.toRadians(a - 60);
            g.drawLine(bx, by, bx + (int)(Math.cos(rad) * 11), by + (int)(Math.sin(rad) * 7));
        }
        g.setStroke(new BasicStroke(1f));
    }

    private void drawHut(Graphics2D g, int sx, int sy) {
        // Wall
        g.setColor(c(0xb87848));
        g.fillRect(sx - 9, sy - 18, 18, 14);
        // Door
        g.setColor(c(0x5a3010));
        g.fillRect(sx - 3, sy - 13, 6, 9);
        // Roof
        int[] xp = {sx - 11, sx + 1, sx + 11};
        int[] yp = {sy - 18, sy - 30, sy - 18};
        g.setColor(c(0x7a3010));
        g.fillPolygon(xp, yp, 3);
        g.setColor(c(0x9a4018));
        g.drawPolyline(xp, yp, 3);
    }

    private void drawDock(Graphics2D g, int sx, int sy) {
        g.setColor(c(0x8a6030));
        g.setStroke(new BasicStroke(3.5f));
        // Horizontal plank
        g.drawLine(sx - 10, sy - 4, sx + 10, sy - 4);
        // Posts
        g.setStroke(new BasicStroke(2f));
        g.drawLine(sx - 6, sy - 4, sx - 6, sy + 6);
        g.drawLine(sx + 6, sy - 4, sx + 6, sy + 6);
        g.setStroke(new BasicStroke(1f));
    }

    // ── Water background ──────────────────────────────────────────────────────

    private void drawWaterBackground(Graphics2D g) {
        g.setColor(c(0x0c2a52));
        g.fillRect(0, MAP_Y, W, MAP_H);
        // Wave rows
        g.setColor(c(0x143a6a));
        g.setStroke(new BasicStroke(0.5f));
        for (int y = MAP_Y + 18; y < MAP_Y + MAP_H; y += 18) {
            for (int x = 8; x < W - 8; x += 42) {
                g.drawArc(x, y, 28, 5, 0, 180);
            }
        }
        g.setStroke(new BasicStroke(1f));
    }

    // ── Zone labels ───────────────────────────────────────────────────────────

    private void drawZoneLabels(Graphics2D g, int originX, int originY, int devPts) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));

        for (Object[] entry : ZONE_LABELS) {
            int row   = (int) entry[0];
            int col   = (int) entry[1];
            int zone  = (int) entry[2];
            String lbl = (String) entry[3];

            int elev = ELEV[row][col];
            int sx = originX + (col - row) * (TW / 2);
            int sy = originY + (col + row) * (TH / 2) - elev * BH - TH / 2 - 6;

            boolean locked = !isUnlocked(zone, devPts);

            FontMetrics fm = g.getFontMetrics();
            int lw = fm.stringWidth(lbl) + 8, lh = 13;

            // Bubble background
            g.setColor(locked ? new Color(20, 18, 28, 195) : new Color(6, 22, 50, 210));
            g.fillRoundRect(sx - lw / 2, sy - lh + 2, lw, lh, 3, 3);

            g.setColor(locked ? c(0x484660) : c(0x78b0e0));
            g.drawString(lbl, sx - lw / 2 + 4, sy - 1);
        }
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private void drawHeader(Graphics2D g, Player player, Island island, int devPts) {
        g.setColor(HDR_BG);
        g.fillRect(0, 0, W, HDR_H);
        g.setColor(c(0x1a4a7a));
        g.fillRect(0, HDR_H - 2, W, 2);

        // Island icon (small isometric mini-island)
        drawMiniIslandIcon(g, 10, 8, 36);

        // Subtitle
        g.setColor(ACCENT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        g.drawString("REEFBOT  ·  КАРТА ОСТРОВА", 58, 20);

        // Island name
        String name = island.getName() != null ? island.getName() : "—";
        g.setColor(TXT_PRI);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 21));
        g.drawString(name, 58, 44);

        // Player username (top right)
        if (player.getUsername() != null) {
            String uname = "@" + player.getUsername();
            g.setColor(TXT_SEC);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(uname, W - fm.stringWidth(uname) - 74, 20);
        }

        // Dev points badge (top right)
        int bx = W - 66, by = 8, bw = 54, bh = 36;
        g.setColor(c(0x0a3055));
        g.fillRoundRect(bx, by, bw, bh, 6, 6);
        g.setColor(c(0x1a6aaa));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(bx, by, bw, bh, 6, 6);
        g.setColor(ACCENT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
        drawCentered(g, "ОР", bx, by, bw, 18);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 19));
        drawCentered(g, String.valueOf(devPts), bx, by + 18, bw, 18);
    }

    /** Tiny isometric island drawn as header icon. */
    private void drawMiniIslandIcon(Graphics2D g, int x, int y, int size) {
        // Water ring
        g.setColor(c(0x1a4a8a));
        g.fillOval(x, y, size, size);
        // Sand
        g.setColor(c(0xc8a450));
        int[] xp = {x + size/2, x + size/2 + 8, x + size/2, x + size/2 - 8};
        int[] yp = {y + 8, y + size/2, y + size - 8, y + size/2};
        g.fillPolygon(xp, yp, 4);
        // Grass top
        g.setColor(c(0x2a7a18));
        g.fillOval(x + size/2 - 6, y + 6, 13, 10);
        // Palm
        g.setColor(c(0x9a6828));
        g.setStroke(new BasicStroke(1.5f));
        g.drawLine(x + size/2, y + 14, x + size/2 + 3, y + 4);
        g.setColor(c(0x1a6c0a));
        g.setStroke(new BasicStroke(1.2f));
        g.drawLine(x + size/2 + 3, y + 4, x + size/2 + 8, y + 2);
        g.drawLine(x + size/2 + 3, y + 4, x + size/2 - 2, y + 1);
        g.setStroke(new BasicStroke(1f));
    }

    // ── Footer (zone legend) ──────────────────────────────────────────────────

    private void drawFooter(Graphics2D g, int devPts) {
        g.setColor(FOOT_BG);
        g.fillRect(0, H - FOOT_H, W, FOOT_H);
        g.setColor(BORDER);
        g.fillRect(0, H - FOOT_H, W, 1);

        String[] names = {"Берег", "Лес", "Холмы", "Поселение", "Равнина", "Порт"};
        int[]    zones = {Z_SHORE, Z_FOREST, Z_HILLS, Z_SETTLE, Z_PLAINS, Z_PORT};

        int spacing = (W - 32) / 6;
        int baseY   = H - FOOT_H / 2 + 4;

        for (int i = 0; i < 6; i++) {
            boolean locked = !isUnlocked(zones[i], devPts);
            int[] clr = locked ? LOCKED_CLR : ZONE_COLORS[zones[i]];

            int lx = 16 + i * spacing + spacing / 2;

            // Mini isometric cube swatch
            int[] tx = {lx, lx + 7, lx + 14, lx + 7};
            int[] ty = {baseY - 7, baseY - 11, baseY - 7, baseY - 3};
            g.setColor(c(clr[0]));
            g.fillPolygon(tx, ty, 4);
            g.setColor(c(clr[1]));
            g.fillPolygon(new int[]{lx, lx + 7, lx + 7, lx},
                          new int[]{baseY - 7, baseY - 3, baseY + 3, baseY - 1}, 4);
            g.setColor(c(clr[2]));
            g.fillPolygon(new int[]{lx + 7, lx + 14, lx + 14, lx + 7},
                          new int[]{baseY - 3, baseY - 7, baseY - 1, baseY + 3}, 4);

            // Label
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
            g.setColor(locked ? c(0x404050) : c(0x88b0d0));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(names[i], lx + 7 - fm.stringWidth(names[i]) / 2, baseY + 14);
        }
    }

    // ── Zone unlock ───────────────────────────────────────────────────────────

    private static boolean isUnlocked(int zoneId, int devPts) {
        return switch (zoneId) {
            case Z_WATER  -> true;
            case Z_SHORE  -> ZoneType.SHORE.isUnlocked(devPts);
            case Z_FOREST -> ZoneType.FOREST.isUnlocked(devPts);
            case Z_HILLS  -> ZoneType.HILLS.isUnlocked(devPts);
            case Z_SETTLE -> ZoneType.SETTLEMENT.isUnlocked(devPts);
            case Z_PLAINS -> ZoneType.PLAINS.isUnlocked(devPts);
            case Z_PORT   -> ZoneType.PORT.isUnlocked(devPts);
            default       -> false;
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void drawCorners(Graphics2D g) {
        g.setColor(c(0x2a6aaa));
        g.setStroke(new BasicStroke(2f));
        int s = 16;
        g.drawLine(3, 3, 3 + s, 3);              g.drawLine(3, 3, 3, 3 + s);
        g.drawLine(W - 4 - s, 3, W - 4, 3);      g.drawLine(W - 4, 3, W - 4, 3 + s);
        g.drawLine(3, H - 4, 3 + s, H - 4);      g.drawLine(3, H - 4 - s, 3, H - 4);
        g.drawLine(W - 4 - s, H - 4, W - 4, H - 4); g.drawLine(W - 4, H - 4 - s, W - 4, H - 4);
    }

    private static void drawCentered(Graphics2D g, String text, int x, int y, int w, int h) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, x + (w - fm.stringWidth(text)) / 2,
                     y + (h + fm.getAscent() - fm.getDescent()) / 2);
    }

    private static void applyHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,          RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,      RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }
}
