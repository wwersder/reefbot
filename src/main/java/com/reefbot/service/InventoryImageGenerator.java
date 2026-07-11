package com.reefbot.service;

import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.ConsumableItem;
import com.reefbot.service.game.FishingService;
import com.reefbot.service.game.TideService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates an inventory card as a PNG byte array for the /inv command.
 *
 * Emoji rendering: Twemoji 14.0.2 PNG images loaded from jsDelivr CDN at runtime
 * and cached in-process. Requires outbound HTTPS access from the VPS.
 *
 * Custom font: place a TTF in src/main/resources/fonts/Nunito-Regular.ttf and
 * src/main/resources/fonts/Nunito-Bold.ttf (download from fonts.google.com).
 * Falls back to system SansSerif if the files are absent.
 *
 * On VPS install Ubuntu font as a decent fallback:
 *   sudo apt-get install -y fonts-ubuntu
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryImageGenerator {

    private final TideService    tideService;
    private final FishingService fishingService;

    // ── Canvas ────────────────────────────────────────────────────────────────
    private static final int W = 700;
    private static final int H = 460;

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final int HDR_H  = 72;
    private static final int XP_H   = 26;
    private static final int BODY_Y = HDR_H + XP_H;
    private static final int FOOT_H = 42;
    private static final int BODY_H = H - BODY_Y - FOOT_H;
    private static final int DIV_X  = 478;

    // ── Fishing XP thresholds (mirrors FishingService.XP_THRESHOLDS) ─────────
    private static final int[] FISHING_XP =
            {0, 50, 150, 350, 700, 1200, 2000, 3500, 6000, 10000, Integer.MAX_VALUE};

    // ── Emoji → Twemoji filename mapping ─────────────────────────────────────
    // Twemoji 14.0.2 CDN: https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/
    private static final String TWEMOJI_CDN =
            "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/";

    private static final Map<String, String> EMOJI_HEX = Map.ofEntries(
        Map.entry("🏝",  "1f3dd"),       // island (header)
        Map.entry("🐚",  "1f41a"),       // shells
        Map.entry("🐟",  "1f41f"),       // fish
        Map.entry("🪵",  "1fab5"),       // wood
        Map.entry("🪨",  "1faa8"),       // stone
        Map.entry("🪸",  "1fab8"),       // coral
        Map.entry("⭐",  "2b50"),        // dev points
        Map.entry("📜",  "1f4dc"),       // speed scroll
        Map.entry("🪱",  "1fab1"),       // bait
        Map.entry("🪝",  "1fa9d"),       // hook
        Map.entry("🫙",  "1fad9")        // tide vial
    );

    // In-process emoji image cache: key = emoji char, value = Optional (empty if load failed)
    private static final ConcurrentHashMap<String, Optional<BufferedImage>> EMOJI_CACHE
            = new ConcurrentHashMap<>();

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

    // Resource accent colors (used as fallback stripe when emoji fails to load)
    private static final Color C_SHELLS = c(0xf0c84a);
    private static final Color C_FISH   = c(0x4a9eff);
    private static final Color C_WOOD   = c(0x8b5e3c);
    private static final Color C_STONE  = c(0x7a8a9a);
    private static final Color C_CORAL  = c(0xff6b8a);
    private static final Color C_DEV    = c(0x9b59b6);
    private static final Color C_SCROLL = c(0x2a9a8a);
    private static final Color C_BAIT   = c(0x5a9a3a);
    private static final Color C_HOOK   = c(0xe08030);
    private static final Color C_VIAL   = c(0x3a8acf);

    private static Color c(int rgb) { return new Color(rgb); }

    // ── Fonts ─────────────────────────────────────────────────────────────────

    private static Font fontRegular(float size) {
        return loadFont("/fonts/Nunito-Regular.ttf", Font.PLAIN, size);
    }

    private static Font fontBold(float size) {
        return loadFont("/fonts/Nunito-Bold.ttf", Font.BOLD, size);
    }

    private static Font loadFont(String classpathPath, int style, float size) {
        InputStream is = InventoryImageGenerator.class.getResourceAsStream(classpathPath);
        if (is != null) {
            try {
                return Font.createFont(Font.TRUETYPE_FONT, is).deriveFont(style, size);
            } catch (Exception e) {
                log.debug("Could not load font from {}: {}", classpathPath, e.getMessage());
            }
        }
        // Try common system fonts with Cyrillic support
        for (String name : new String[]{"Nunito", "Ubuntu", "Noto Sans", "DejaVu Sans"}) {
            Font f = new Font(name, style, (int) size);
            if (!f.getFamily().equalsIgnoreCase("Dialog")) {
                return f.deriveFont(style, size);
            }
        }
        return new Font(Font.SANS_SERIF, style, (int) size);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Generate without avatar (falls back to placeholder icon). */
    public byte[] generate(Player player, Island island) {
        return generate(player, island, null);
    }

    /** Generate with optional avatar bytes (JPEG/PNG from Telegram). */
    public byte[] generate(Player player, Island island, @Nullable byte[] avatarBytes) {
        System.setProperty("java.awt.headless", "true");

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,          RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,      RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        g.setColor(BG);
        g.fillRect(0, 0, W, H);

        drawHeader(g, player, avatarBytes);
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

    private void drawHeader(Graphics2D g, Player player, @Nullable byte[] avatarBytes) {
        g.setColor(HDR_BG);
        g.fillRect(0, 0, W, HDR_H);
        g.setColor(c(0x1a4a7a));
        g.fillRect(0, HDR_H - 2, W, 2);

        // Avatar circle (56×56) at left
        int avX = 10, avY = 8, avD = 56;
        drawAvatar(g, avatarBytes, avX, avY, avD);

        // Title row
        g.setColor(ACCENT);
        g.setFont(fontBold(9f));
        g.drawString("REEFBOT  ·  ИНВЕНТАРЬ", avX + avD + 12, 27);

        // Player name
        String name = player.getUsername() != null
                ? "@" + player.getUsername()
                : "#" + player.getTelegramId();
        g.setColor(TXT_PRI);
        g.setFont(fontBold(21f));
        g.drawString(name, avX + avD + 12, 57);

        // Level box (top-right)
        int lvl = player.getFishing() != null ? player.getFishing().getFishingLevel() : 1;
        int lx = W - 66, ly = 12, lw = 52, lh = 48;
        g.setColor(c(0x0a3055));
        g.fill(new RoundRectangle2D.Float(lx, ly, lw, lh, 6, 6));
        g.setColor(c(0x1a6aaa));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(lx, ly, lw, lh, 6, 6));

        g.setColor(ACCENT);
        g.setFont(fontBold(8f));
        drawCentered(g, "LVL", lx, ly, lw, 22);

        g.setColor(Color.WHITE);
        g.setFont(fontBold(23f));
        drawCentered(g, String.valueOf(lvl), lx, ly + 20, lw, 28);
    }

    // ── Avatar ────────────────────────────────────────────────────────────────

    private void drawAvatar(Graphics2D g, @Nullable byte[] avatarBytes, int x, int y, int d) {
        if (avatarBytes != null) {
            try {
                BufferedImage src = ImageIO.read(new ByteArrayInputStream(avatarBytes));
                // Scale to square
                BufferedImage circle = new BufferedImage(d, d, BufferedImage.TYPE_INT_ARGB);
                Graphics2D gc = circle.createGraphics();
                gc.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                gc.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                // Clip to circle
                gc.setClip(new Ellipse2D.Float(0, 0, d, d));
                gc.drawImage(src, 0, 0, d, d, null);
                gc.dispose();
                g.drawImage(circle, x, y, null);
                // Border
                g.setColor(c(0x2a6acc));
                g.setStroke(new BasicStroke(2f));
                g.drawOval(x, y, d, d);
                return;
            } catch (Exception e) {
                log.debug("Could not render avatar image: {}", e.getMessage());
            }
        }
        // Placeholder: colored circle with island emoji
        g.setColor(c(0x1a4a8a));
        g.fillOval(x, y, d, d);
        g.setColor(c(0x2a6acc));
        g.setStroke(new BasicStroke(2f));
        g.drawOval(x, y, d, d);
        BufferedImage islandEmoji = getEmoji("🏝");
        if (islandEmoji != null) {
            int es = d - 14;
            g.drawImage(islandEmoji, x + 7, y + 7, es, es, null);
        } else {
            g.setColor(c(0x5aacff));
            g.setFont(fontBold(24f));
            drawCentered(g, "~", x, y, d, d);
        }
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
        int xpPrev = (lvl - 1 < FISHING_XP.length) ? FISHING_XP[lvl - 1] : 0;
        int xpNext = (lvl     < FISHING_XP.length) ? FISHING_XP[lvl]     : xpPrev + 1;

        g.setColor(ACCENT);
        g.setFont(fontBold(8f));
        g.drawString("XP", 16, y + 17);

        // Track
        int tx = 44, ty = y + 8, tw = W - 158, th = 10;
        g.setColor(XP_TRACK);
        g.fill(new RoundRectangle2D.Float(tx, ty, tw, th, 4, 4));
        g.setColor(BORDER);
        g.draw(new RoundRectangle2D.Float(tx, ty, tw, th, 4, 4));

        // Fill
        if (xpNext > xpPrev) {
            float pct   = Math.min(1f, (float)(xp - xpPrev) / (xpNext - xpPrev));
            int   fillW = Math.max(6, (int)(tw * pct));
            g.setColor(XP_FILL);
            g.fill(new RoundRectangle2D.Float(tx, ty, fillW, th, 4, 4));
        }

        // Text
        g.setColor(c(0x6ab0d0));
        g.setFont(fontRegular(9f));
        String xpStr = lvl >= 10 ? "MAX" : fmt(xp) + " / " + fmt(xpNext);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(xpStr, W - 14 - fm.stringWidth(xpStr), y + 17);
    }

    // ── Body ──────────────────────────────────────────────────────────────────

    private void drawBody(Graphics2D g, Player player, Island island) {
        g.setColor(BORDER);
        g.fillRect(DIV_X, BODY_Y, 2, BODY_H);
        drawResources(g, island);
        drawItems(g, player);
    }

    // ── Resources grid ────────────────────────────────────────────────────────

    private record Res(String name, int value, String emoji, Color fallbackColor, boolean hiColor) {}

    private void drawResources(Graphics2D g, Island island) {
        int padX = 12, padY = 8;

        // Section label — brighter and larger
        g.setColor(c(0x4a7aaa));
        g.setFont(fontBold(10f));
        g.drawString("РЕСУРСЫ ОСТРОВА", padX, BODY_Y + padY + 10);
        g.setColor(c(0x1a3050));
        g.fillRect(padX, BODY_Y + padY + 14, DIV_X - padX * 2, 1);

        Res[] res = {
            new Res("РАКУШКИ", safe(island.getShells()),   "🐚", C_SHELLS, true),
            new Res("РЫБА",    safe(island.getFish()),      "🐟", C_FISH,   false),
            new Res("ДЕРЕВО",  safe(island.getWood()),      "🪵", C_WOOD,   false),
            new Res("КАМЕНЬ",  safe(island.getStone()),     "🪨", C_STONE,  false),
            new Res("КОРАЛЛ",  safe(island.getCoral()),     "🪸", C_CORAL,  false),
            new Res("ОЧКИ",    safe(island.getDevPoints()), "⭐", C_DEV,    true),
        };

        int cols  = 3, rows = 2;
        int gridX = padX;
        int gridY = BODY_Y + padY + 22;
        int gridW = DIV_X - padX * 2;
        int gridH = BODY_H - padY - 22 - 6;
        int colW  = (gridW - (cols - 1) * 7) / cols;
        int rowH  = (gridH - (rows - 1) * 7) / rows;

        for (int i = 0; i < res.length; i++) {
            drawResSlot(g,
                    gridX + (i % cols) * (colW + 7),
                    gridY + (i / cols) * (rowH + 7),
                    colW, rowH, res[i]);
        }
    }

    private void drawResSlot(Graphics2D g, int x, int y, int w, int h, Res res) {
        boolean isEmpty = (res.value() == 0);

        // Dim entire cell when empty (matches opacity: 0.38 effect)
        java.awt.Composite savedComp = g.getComposite();
        if (isEmpty) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.38f));
        }

        g.setColor(PANEL);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, 4, 4));
        g.setColor(isEmpty ? c(0x101820) : BORDER);
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, 4, 4));

        // Emoji icon — large, centered horizontally at top
        int iconSize = 44;
        int iconX    = x + (w - iconSize) / 2;
        int iconY    = y + 9;
        BufferedImage emoji = getEmoji(res.emoji());
        if (emoji != null) {
            g.drawImage(emoji, iconX, iconY, iconSize, iconSize, null);
        } else {
            // Fallback: colored circle
            g.setColor(res.fallbackColor());
            int dot = 14;
            g.fillOval(x + (w - dot) / 2, iconY + (iconSize - dot) / 2, dot, dot);
        }

        // Name label — centered, below emoji
        g.setColor(TXT_SEC);
        g.setFont(fontBold(9.5f));
        drawCentered(g, res.name(), x, y + 9 + iconSize + 4, w, 15);

        // Count — large, centered, below name
        Color valColor = res.hiColor()
                ? (res.fallbackColor() == C_DEV ? RARE_COL : HIGHLIGHT)
                : TXT_PRI;
        g.setColor(valColor);
        g.setFont(fontBold(24f));
        int countY = y + 9 + iconSize + 4 + 15 + 2;
        drawCentered(g, fmt(res.value()), x, countY, w, h - countY + y - 6);

        if (isEmpty) {
            g.setComposite(savedComp);
        }
    }

    // ── Items list ────────────────────────────────────────────────────────────

    private record Item(String name, int qty, String emoji, Color fallbackColor) {}

    private void drawItems(Graphics2D g, Player player) {
        int x0 = DIV_X + 2, w0 = W - DIV_X - 2;
        int padX = 12, padY = 8;

        g.setColor(c(0x4a7aaa));
        g.setFont(fontBold(10f));
        g.drawString("ПРЕДМЕТЫ", x0 + padX, BODY_Y + padY + 10);
        g.setColor(c(0x1a3050));
        g.fillRect(x0 + padX, BODY_Y + padY + 14, w0 - padX * 2, 1);

        Item[] items = {
            new Item("Свиток",  tideService.getItemCount(player, ConsumableItem.SPEED_SCROLL), "📜", C_SCROLL),
            new Item("Наживка", tideService.getItemCount(player, ConsumableItem.BAIT),         "🪱", C_BAIT),
            new Item("Крюк",    tideService.getItemCount(player, ConsumableItem.FISHING_HOOK), "🪝", C_HOOK),
            new Item("Склянка", tideService.getItemCount(player, ConsumableItem.TIDE_VIAL),    "🫙", C_VIAL),
        };

        int ix   = x0 + padX;
        int iw   = w0 - padX * 2;
        int topY = BODY_Y + padY + 22;
        int avail = BODY_H - padY - 22 - 6;
        int ih   = (avail - 3 * 7) / 4;  // 4 items, 3 gaps of 7px

        for (int i = 0; i < items.length; i++) {
            drawItemSlot(g, ix, topY + i * (ih + 7), iw, ih, items[i]);
        }
    }

    private void drawItemSlot(Graphics2D g, int x, int y, int w, int h, Item item) {
        boolean has = item.qty() > 0;

        java.awt.Composite savedComp = g.getComposite();
        if (!has) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.38f));
        }

        g.setColor(PANEL);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, 4, 4));
        g.setColor(has ? BORDER : c(0x101820));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, 4, 4));

        // Emoji icon — centered vertically in left 44px zone
        int iconSize = 30;
        int iconX    = x + (44 - iconSize) / 2;
        int iconY    = y + (h - iconSize) / 2;
        BufferedImage emoji = getEmoji(item.emoji());
        if (emoji != null) {
            g.drawImage(emoji, iconX, iconY, iconSize, iconSize, null);
        } else {
            g.setColor(has ? item.fallbackColor() : c(0x1a2a3a));
            g.fill(new RoundRectangle2D.Float(x + 1, y + 6, 4, h - 12, 2, 2));
        }

        // Separator
        g.setColor(c(0x1a3050));
        g.fillRect(x + 44, y + 8, 1, h - 16);

        // Name — slightly brighter than before
        g.setColor(TXT_SEC);
        g.setFont(fontBold(10f));
        g.drawString(item.name(), x + 53, y + h / 2 - 2);

        // Qty or dash
        if (has) {
            g.setColor(TXT_PRI);
            g.setFont(fontBold(17f));
            g.drawString("×" + item.qty(), x + 53, y + h / 2 + 16);
        } else {
            g.setColor(c(0x2a4a6a));
            g.setFont(fontRegular(14f));
            g.drawString("—", x + 53, y + h / 2 + 14);
        }

        if (!has) {
            g.setComposite(savedComp);
        }
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private void drawFooter(Graphics2D g, Player player, Island island) {
        int y = H - FOOT_H;
        g.setColor(FOOT_BG);
        g.fillRect(0, y, W, FOOT_H);
        g.setColor(BORDER);
        g.fillRect(0, y, W, 2);

        int    lvl       = player.getFishing() != null ? player.getFishing().getFishingLevel() : 1;
        String fishTitle = fishingService.levelName(lvl);
        String islandName = island != null && island.getName() != null ? island.getName() : "—";

        drawFooterCol(g, 24,           y, "РЫБАК",   fishTitle + " (ур. " + lvl + ")");
        drawFooterCol(g, W / 3 + 14,  y, "ОСТРОВ",  islandName);

        g.setColor(TXT_DIM);
        g.setFont(fontBold(10f));
        g.drawString("REEFBOT", 2 * W / 3 + 14, y + 28);

        // Dividers
        g.setColor(BORDER);
        g.fillRect(W / 3,     y + 8, 1, FOOT_H - 16);
        g.fillRect(2 * W / 3, y + 8, 1, FOOT_H - 16);
    }

    private void drawFooterCol(Graphics2D g, int x, int panelY, String label, String value) {
        g.setColor(TXT_DIM);
        g.setFont(fontBold(8f));
        g.drawString(label, x, panelY + 17);
        g.setColor(c(0x7aaad0));
        g.setFont(fontBold(12f));
        g.drawString(value, x, panelY + 33);
    }

    // ── Corner accents ────────────────────────────────────────────────────────

    private void drawCorners(Graphics2D g) {
        g.setColor(c(0x2a6aaa));
        g.setStroke(new BasicStroke(2f));
        int s = 16;
        g.drawLine(3, 3, 3 + s, 3);           g.drawLine(3, 3, 3, 3 + s);
        g.drawLine(W - 4 - s, 3, W - 4, 3);   g.drawLine(W - 4, 3, W - 4, 3 + s);
        g.drawLine(3, H - 4, 3 + s, H - 4);   g.drawLine(3, H - 4 - s, 3, H - 4);
        g.drawLine(W - 4 - s, H - 4, W - 4, H - 4); g.drawLine(W - 4, H - 4 - s, W - 4, H - 4);
    }

    // ── Emoji loading (Twemoji CDN) ───────────────────────────────────────────

    @Nullable
    private static BufferedImage getEmoji(String emoji) {
        String hex = EMOJI_HEX.get(emoji);
        if (hex == null) return null;

        return EMOJI_CACHE.computeIfAbsent(hex, key -> {
            // Priority 1: bundled Apple emoji PNGs from classpath.
            // Place files in src/main/resources/emoji/{hex}.png
            // Download from: https://emojipedia.org/apple (right-click image → Save as {hex}.png)
            InputStream bundled = InventoryImageGenerator.class.getResourceAsStream("/emoji/" + key + ".png");
            if (bundled != null) {
                try (InputStream is = bundled) {
                    BufferedImage img = ImageIO.read(is);
                    if (img != null) {
                        log.debug("Loaded bundled emoji (Apple): {}", key);
                        return Optional.of(img);
                    }
                } catch (IOException e) {
                    log.debug("Bundled emoji {} unreadable: {}", key, e.getMessage());
                }
            }
            // Priority 2: Twemoji CDN fallback (MIT, looks great on non-Apple clients)
            try {
                URLConnection conn = new URL(TWEMOJI_CDN + key + ".png").openConnection();
                conn.setConnectTimeout(3_000);
                conn.setReadTimeout(3_000);
                conn.setRequestProperty("User-Agent", "ReefBot/1.0");
                BufferedImage img = ImageIO.read(conn.getInputStream());
                if (img != null) log.debug("Loaded Twemoji CDN: {}", key);
                return Optional.ofNullable(img);
            } catch (Exception e) {
                log.warn("Failed to load emoji '{}' ({}): {}", emoji, key, e.getMessage());
                return Optional.empty();
            }
        }).orElse(null);
    }

    // ── Drawing helpers ───────────────────────────────────────────────────────

    private static void drawCentered(Graphics2D g, String text, int x, int y, int w, int h) {
        FontMetrics fm = g.getFontMetrics();
        int tx = x + (w - fm.stringWidth(text)) / 2;
        int ty = y + (h + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(text, tx, ty);
    }

    private static String fmt(int n) {
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
        sym.setGroupingSeparator(' ');
        return new DecimalFormat("#,###", sym).format(n);
    }

    private static int safe(Integer v) { return v != null ? v : 0; }
}
