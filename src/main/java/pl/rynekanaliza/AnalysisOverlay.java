package pl.rynekanaliza;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

/**
 * Rysowanie nad oknem rynku podczas analizy:
 *  - żółte podświetlenie ofert, które są teraz w gronie najtańszych (te idą do średniej),
 *  - pasek nad rynkiem z ikoną i nazwą analizowanego przedmiotu.
 *
 * Pozycję okna liczymy sami (standardowy kontener: szerokość 176, wysokość 114 + rzędy * 18),
 * dzięki czemu nie trzeba mixinów.
 */
public final class AnalysisOverlay {
    public static boolean enabled = true;

    private static final int YELLOW = 0xFFFFFF00;
    private static final int YELLOW_FILL = 0x66FFFF00;
    private static final int ORANGE = 0xFFFF9900;
    private static final int ORANGE_FILL = 0x55FF9900;

    private AnalysisOverlay() {}

    public static void render(Screen screen, DrawContext ctx) {
        if (!enabled || !MarketAnalyzer.isRunning()) return;
        if (!(screen instanceof HandledScreen<?> hs) || !MarketAnalyzer.isMarket(hs)) return;

        ScreenHandler h = hs.getScreenHandler();
        int rows = Math.max(1, (h.slots.size() - 36) / 9);
        int bgHeight = 114 + rows * 18;
        int left = (screen.width - 176) / 2;
        int top = (screen.height - bgHeight) / 2;

        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(0f, 0f, 300f); // nad przedmiotami, pod tooltipem
        try {
            drawHighlights(ctx, hs, h, left, top);
            drawHeader(ctx, left, top);
        } finally {
            m.pop();
        }
    }

    private static void drawHighlights(DrawContext ctx, HandledScreen<?> hs, ScreenHandler h, int left, int top) {
        if (MarketAnalyzer.highlightPage() != MarketAnalyzer.pageOf(hs)) return;
        for (int slotIndex : MarketAnalyzer.highlightSlots2()) box(ctx, h, left, top, slotIndex, ORANGE, ORANGE_FILL);
        for (int slotIndex : MarketAnalyzer.highlightSlots()) box(ctx, h, left, top, slotIndex, YELLOW, YELLOW_FILL);
    }

    private static void box(DrawContext ctx, ScreenHandler h, int left, int top, int slotIndex, int border, int fill) {
        if (slotIndex < 0 || slotIndex >= h.slots.size()) return;
        Slot s = h.slots.get(slotIndex);
        int x = left + s.x;
        int y = top + s.y;
        ctx.fill(x, y, x + 16, y + 16, fill);
        ctx.fill(x - 1, y - 1, x + 17, y, border);        // góra
        ctx.fill(x - 1, y + 16, x + 17, y + 17, border);  // dół
        ctx.fill(x - 1, y, x, y + 16, border);            // lewa
        ctx.fill(x + 16, y, x + 17, y + 16, border);      // prawa
    }

    private static void drawHeader(DrawContext ctx, int left, int top) {
        MarketAnalyzer.Target t = MarketAnalyzer.currentTarget();
        if (t == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;

        String mode = MarketAnalyzer.fullScan() ? "wszystkie strony" : "do " + MarketAnalyzer.listingsUsed() + " ofert";
        String line = String.format("Analiza %d/%d: %s | str. %d/%d | ofert: %d | skan: %s",
                MarketAnalyzer.targetIndex(), MarketAnalyzer.targetCount(), t.displayName(),
                MarketAnalyzer.scanPage(), MarketAnalyzer.scanTotal(), MarketAnalyzer.foundCount(), mode);
        ItemStack icon = MarketAnalyzer.iconStack();

        int boxW = 22 + tr.getWidth(line) + 6;
        int boxH = 18;
        int x = left + (176 - boxW) / 2;
        int y = Math.max(1, top - boxH - 2);

        ctx.fill(x, y, x + boxW, y + boxH, 0xCC000000);
        ctx.fill(x, y, x + boxW, y + 1, YELLOW);
        if (!icon.isEmpty()) ctx.drawItem(icon, x + 2, y + 1);
        ctx.drawTextWithShadow(tr, line, x + 22, y + 5, 0xFFFFFFFF);
    }
}
