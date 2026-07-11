package com.reefbot.service;

import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.ConsumableItem;
import com.reefbot.service.game.FishingService;
import com.reefbot.service.game.TideService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Generates a pixel-art style inventory card as a PNG byte array.
 * Used by the /inv Telegram command (works in both private chats and groups).
 *
 * Java2D runs fine in headless mode — no display required on VPS.
 * Cyrillic text requires a font with Cyrillic support; DejaVu Sans
 * (fonts-dejavu-core, pre-installed on Ubuntu) is mapped to Font.SANS_SERIF.
 */
@Service
@RequiredArgsConstructor
public class InventoryImageGenerator {

    private final TideService   tideService;
    private final FishingService fishingService;

    // ── Canvas ────────────────────────────────────────────────────────────────
    private static final int W = 700;
    private static final int H = 410;

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final int HDR_H  = 65;
    private static final int XP_H   = 24;
    private static final int BODY_Y = HDR_H + XP_H;          // 89
    private static final int FOOT_H = 40;
    private static final int BODY_H = H - BODY_Y - FOOT_H;   // 281
    private static final int DIV_X  = 476;                    // left/right split

    // ── Fishing XP thresholds (mirrors FishingService.XP_THRESHOLDS) ─────────
    // index = level-1, value = cumulative XP to reach that level
    private static final int[] FISHING_XP =
            {0, 50, 150, 350, 700, 1200, 2000, 3500, 6000, 10000, Integer.MAX_VALUE};

    // ── Colors ────────────────────────────────────────────────────────────────
    private static final Color BG        = c(0x0d1b2a);
    private static final Color PANEL     = c(0x060e18);
    private static final Color BORDER    = c(0x1a3050);
    private static final Color ACCENT    = c(0x4a9eff);
    private static final Color TXT_PRI   = c(0xd0eaff);
    private static final Color TXT_SEC   = c(0x4a7aaa);
    private static final Color TXT_DIM   = c(0x2a5a8a);
    private static final Color HIGHLIGHT = c(0x4acfff);
    private static final Color RARE_COL  = c(0xffcc44);
    private static final Color HDR_BG    = c(0x0a3055);
    private static final Color FOOT_BG   = c(0x07111e);
    private static final Color XP_FILL   = c(0x1a6aff);
    private static final Color XP_TRACK  = c(0x0a1e30);
    // Resource accent colors
    private static final Color C_SHELLS  = c(0xf0c84a);
    private static final Color C_FISH    = c(0x4a9eff);
    private static final Color C_WOOD    = c(0x8b5e3c);
    private static final Color C_STONE   = c(0x7a8a9a);
    private static final Color C_CORAL   = c(0xff6b8a);
    private static final Color C_DEV     = c(0x9b59b6);
    // Item accent colors
    private static final Color C_SCROLL  = c(0x2a9a8a);
    private static final Color C_BAIT    = c(0x5a9a3a);
    private static final Color C_HOOK    = c(0xe08030);
    private static final Color C_VIAL    = c(0x3a8acf);

    private static Color c(int rgb) { return new Color(rgb); }

    // ── Public API ────────────────────────────────────────────────────────────

    public byte[] generate(Player player, Island island) {
        System.setProperty("java.awt.headless", "true");

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,          RenderingHints.VALUE_RENDER_QUALITY);

        g.setColor(BG);
        g.fillRect(0, 0, W, H);

        drawHeader(g, player);
        drawXpBar(g, player);
        drawBody(g, player, island);
        drawFooter(g, player, island);

        // Outer border + corner accents
        g.setColor(c(0x1e3a5f));
        g.setStroke(new BasicStroke(2f));
        g.drawRect(1, 1, W - 3, H - 3);
        drawCorners(g);

        g.dispose();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate inventory image", e);
        }
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private void drawHeader(Graphics2D g, Player player) {
        g.setColor(HDR_BG);
        g.fillRect(0, 0, W, HDR_H);
        g.setColor(c(0x1a4a7a));
        g.fillRect(0, HDR_H - 2, W, 2);

        // Left icon circle
        g.setColor(c(0x1a4a8a));
        g.fillOval(14, 12, 42, 42);
        g.setColor(c(0x2a6acc));
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval(14, 12, 42, 42);
        g.setColor(c(0x5aacff));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        drawCentered(g, "~", 14, 12, 42, 42);

        // Title row
        g.setColor(ACCENT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        g.drawString("REEFBOT  ·  ИНВЕНТАРЬ", 68, 26);

        // Player name
        String name = player.getUsername() != null
                ? "@" + player.getUsername()
                : "#" + player.getTelegramId();
        g.setColor(TXT_PRI);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString(name, 68, 52);

        // Level box
        int lvl = player.getFishing() != null ? player.getFishing().getFishingLevel() : 1;
        int lx = W - 64, ly = 11, lw = 50, lh = 44;
        g.setColor(c(0x0a3055));
        g.fill(new RoundRectangle2D.Float(lx, ly, lw, lh, 4, 4));
        g.setColor(c(0x1a6aaa));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(lx, ly, lw, lh, 4, 4));

        g.setColor(ACCENT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
        drawCentered(g, "LVL", lx, ly, lw, 20);

        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        drawCentered(g, String.valueOf(lvl), lx, ly + 18, lw, 26);
    }

    // ── XP Bar ────────────────────────────────────────────────────────────────

    private void drawXpBar(Graphics2D g, Player player) {
        int y = HDR_H;
        g.setColor(c(0x08121f));
        g.fillRect(0, y, W, XP_H);
        g.setColor(c(0x1a3a5a));
        g.fillRect(0, y + XP_H - 1, W, 1);

        int xp  = player.getFishing() != null ? player.getFishing().getFishingXp() : 0;
        int lvl = player.getFishing() != null ? player.getFishing().getFishingLevel() : 1;

        int xpPrev = lvl - 1 < FISHING_XP.length ? FISHING_XP[lvl - 1] : 0;
        int xpNext = lvl     < FISHING_XP.length ? FISHING_XP[lvl]     : xpPrev + 1;

        // Label
        g.setColor(ACCENT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        g.drawString("XP", 16, y + 16);

        // Track
        int tx = 44, ty = y + 7, tw = W - 155, th = 10;
        g.setColor(XP_TRACK);
        g.fill(new RoundRectangle2D.Float(tx, ty, tw, th, 3, 3));
        g.setColor(BORDER);
        g.draw(new RoundRectangle2D.Float(tx, ty, tw, th, 3, 3));

        // Fill
        if (xpNext > xpPrev) {
            float pct    = Math.min(1f, (float)(xp - xpPrev) / (xpNext - xpPrev));
            int   fillW  = Math.max(4, (int)(tw * pct));
            g.setColor(XP_FILL);
            g.fill(new RoundRectangle2D.Float(tx, ty, fillW, th, 3, 3));
        }

        // XP text
        g.setColor(c(0x6ab0d0));
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
        String xpStr = lvl >= 10 ? "MAX" : fmt(xp) + " / " + fmt(xpNext);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(xpStr, W - 14 - fm.stringWidth(xpStr), y + 16);
    }

    // ── Body ──────────────────────────────────────────────────────────────────

    private void drawBody(Graphics2D g, Player player, Island island) {
        g.setColor(BORDER);
        g.fillRect(DIV_X, BODY_Y, 2, BODY_H);

        drawResources(g, island);
        drawItems(g, player);
    }

    // ── Resources grid ────────────────────────────────────────────────────────

    private record Res(String name, int value, Color color, boolean altColor) {}

    private void drawResources(Graphics2D g, Island island) {
        int padX = 16, padY = 12;

        // Section label
        g.setColor(TXT_DIM);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        // "РЕСУРСЫ ОСТРОВА"
        g.drawString("РЕСУРСЫ ОСТРОВА",
                padX, BODY_Y + padY + 9);
        g.setColor(c(0x1a3050));
        g.fillRect(padX, BODY_Y + padY + 13, DIV_X - padX * 2, 1);

        Res[] resources = {
            // РАКУШКИ, РЫБА, ДЕРЕВО, КАМЕНЬ, КОРАЛЛ, ОЧКИ
            new Res("РАКУШКИ", safe(island.getShells()),   C_SHELLS, true),
            new Res("РЫБА",                  safe(island.getFish()),      C_FISH,   false),
            new Res("ДЕРЕВО",      safe(island.getWood()),      C_WOOD,   false),
            new Res("КАМЕНЬ",      safe(island.getStone()),     C_STONE,  false),
            new Res("КОРАЛЛ",      safe(island.getCoral()),     C_CORAL,  false),
            new Res("ОЧКИ",                  safe(island.getDevPoints()), C_DEV,    true),
        };

        int cols  = 3, rows = 2;
        int gridX = padX;
        int gridY = BODY_Y + padY + 22;
        int gridW = DIV_X - padX * 2;
        int gridH = BODY_H - padY - 22 - 8;
        int colW  = (gridW - (cols - 1) * 8) / cols;
        int rowH  = (gridH - (rows - 1) * 8) / rows;

        for (int i = 0; i < resources.length; i++) {
            int col = i % cols;
            int row = i / cols;
            drawResSlot(g,
                    gridX + col * (colW + 8),
                    gridY + row * (rowH + 8),
                    colW, rowH, resources[i]);
        }
    }

    private void drawResSlot(Graphics2D g, int x, int y, int w, int h, Res res) {
        g.setColor(PANEL);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, 3, 3));
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, 3, 3));

        // Left color stripe
        g.setColor(res.color());
        g.fill(new RoundRectangle2D.Float(x + 1, y + 8, 4, h - 16, 2, 2));

        // Name
        g.setColor(TXT_SEC);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
        g.drawString(res.name(), x + 13, y + 17);

        // Count
        Color valColor = res.altColor()
                ? (res.color() == C_DEV ? RARE_COL : HIGHLIGHT)
                : TXT_PRI;
        g.setColor(valColor);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 20));
        g.drawString(fmt(res.value()), x + 13, y + h - 11);
    }

    // ── Items list ────────────────────────────────────────────────────────────

    private record Item(String name, int qty, Color color) {}

    private void drawItems(Graphics2D g, Player player) {
        int x0 = DIV_X + 2, w0 = W - DIV_X - 2;
        int padX = 12, padY = 12;

        // Section label
        g.setColor(TXT_DIM);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        // "ПРЕДМЕТЫ"
        g.drawString("ПРЕДМЕТЫ",
                x0 + padX, BODY_Y + padY + 9);
        g.setColor(c(0x1a3050));
        g.fillRect(x0 + padX, BODY_Y + padY + 13, w0 - padX * 2, 1);

        Item[] items = {
            // Свиток, Наживка, Крюк, Склянка
            new Item("Свиток",
                    tideService.getItemCount(player, ConsumableItem.SPEED_SCROLL), C_SCROLL),
            new Item("Наживка",
                    tideService.getItemCount(player, ConsumableItem.BAIT),         C_BAIT),
            new Item("Крюк",
                    tideService.getItemCount(player, ConsumableItem.FISHING_HOOK), C_HOOK),
            new Item("Склянка",
                    tideService.getItemCount(player, ConsumableItem.TIDE_VIAL),    C_VIAL),
        };

        int ix = x0 + padX;
        int iy = BODY_Y + padY + 22;
        int iw = w0 - padX * 2;

        for (Item item : items) {
            drawItemSlot(g, ix, iy, iw, 46, item);
            iy += 46 + 7;
        }
    }

    private void drawItemSlot(Graphics2D g, int x, int y, int w, int h, Item item) {
        boolean has = item.qty() > 0;

        g.setColor(PANEL);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, 3, 3));
        g.setColor(has ? BORDER : c(0x101820));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, 3, 3));

        // Color stripe
        g.setColor(has ? item.color() : c(0x1a2a3a));
        g.fill(new RoundRectangle2D.Float(x + 1, y + 6, 4, h - 12, 2, 2));

        // Name
        g.setColor(has ? TXT_SEC : c(0x2a4a6a));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
        g.drawString(item.name(), x + 12, y + 15);

        // Quantity
        if (has) {
            g.setColor(TXT_PRI);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 17));
            g.drawString("×" + item.qty(), x + 12, y + h - 9);   // ×N
        } else {
            g.setColor(c(0x2a4a6a));
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            g.drawString("—", x + 12, y + h - 11);                // —
        }
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private void drawFooter(Graphics2D g, Player player, Island island) {
        int y = H - FOOT_H;
        g.setColor(FOOT_BG);
        g.fillRect(0, y, W, FOOT_H);
        g.setColor(BORDER);
        g.fillRect(0, y, W, 2);

        int    lvl        = player.getFishing() != null ? player.getFishing().getFishingLevel() : 1;
        String fishTitle  = fishingService.levelName(lvl);
        // "ур. N"
        String fishVal    = fishTitle + " (ур. " + lvl + ")";
        String islandName = island != null && island.getName() != null ? island.getName() : "—";

        // Col 1: fishing level
        // "РЫБАК"
        drawFooterCol(g, 24, y,
                "РЫБАК", fishVal);

        // Col 2: island name
        // "ОСТРОВ"
        drawFooterCol(g, W / 3 + 18, y,
                "ОСТРОВ", islandName);

        // Col 3: brand
        g.setColor(TXT_DIM);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        g.drawString("REEFBOT", 2 * W / 3 + 18, y + 27);

        // Dividers
        g.setColor(BORDER);
        g.fillRect(W / 3, y + 7, 1, FOOT_H - 14);
        g.fillRect(2 * W / 3, y + 7, 1, FOOT_H - 14);
    }

    private void drawFooterCol(Graphics2D g, int x, int panelY, String label, String value) {
        g.setColor(TXT_DIM);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
        g.drawString(label, x, panelY + 16);
        g.setColor(c(0x7aaad0));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.drawString(value, x, panelY + 32);
    }

    // ── Corner accents ────────────────────────────────────────────────────────

    private void drawCorners(Graphics2D g) {
        g.setColor(c(0x2a6aaa));
        g.setStroke(new BasicStroke(2f));
        int s = 14;
        g.drawLine(3, 3, 3 + s, 3);           g.drawLine(3, 3, 3, 3 + s);            // TL
        g.drawLine(W - 4 - s, 3, W - 4, 3);   g.drawLine(W - 4, 3, W - 4, 3 + s);   // TR
        g.drawLine(3, H - 4, 3 + s, H - 4);   g.drawLine(3, H - 4 - s, 3, H - 4);   // BL
        g.drawLine(W - 4 - s, H - 4, W - 4, H - 4); g.drawLine(W - 4, H - 4 - s, W - 4, H - 4); // BR
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void drawCentered(Graphics2D g, String text, int x, int y, int w, int h) {
        FontMetrics fm = g.getFontMetrics();
        int tx = x + (w - fm.stringWidth(text)) / 2;
        int ty = y + (h + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(text, tx, ty);
    }

    private static String fmt(int n) {
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
        sym.setGroupingSeparator(' '); // non-breaking space
        return new DecimalFormat("#,###", sym).format(n);
    }

    private static int safe(Integer v) { return v != null ? v : 0; }
}
