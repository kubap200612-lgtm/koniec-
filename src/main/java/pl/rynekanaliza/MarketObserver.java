package pl.rynekanaliza;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Obserwacja rynku bez klikania: gdy BK otworzy okno rynku, czytamy je (tylko czytamy) i zapisujemy ceny przedmiotów z listy do historii.
 * Działa tylko, gdy nasza własna analiza nie trwa. Do wykresów trafiają dwa rodzaje punktów:
 * zaufane (widać prawdziwe minimum) i próbki (oferty widziane przy innym sortowaniu, np. "Najnowsze").
 */
public final class MarketObserver {
    private static int lastSig, stable, lastObservedSig, saveTicker;
    private static long lastPass;
    private static List<BkBridge.BkTarget> targets = List.of();
    private static long targetsVersion = Long.MIN_VALUE;
    private static long targetsBuiltAt;

    private MarketObserver() {}

    public static void tick(MinecraftClient mc) {
        PriceHistory hist = RynekAnalizaMod.history();
        AnalizaConfig cfg = RynekAnalizaMod.config();
        if (hist == null || cfg == null) return;
        if (++saveTicker >= 6000) { // co ok. 5 minut (zapis tylko, gdy są zmiany)
            saveTicker = 0;
            hist.save();
        }
        if (!cfg.historyEnabled || !cfg.passiveObserve || MarketAnalyzer.isRunning()) { stable = 0; return; }
        if (!(mc.currentScreen instanceof HandledScreen<?> hs) || !MarketAnalyzer.isMarket(hs) || !MarketAnalyzer.ready(hs)) {
            stable = 0;
            lastSig = 0;
            return;
        }
        if (!BkBridge.available()) return;

        int sig = MarketAnalyzer.signature(hs);
        if (sig != lastSig) { lastSig = sig; stable = 0; return; }
        if (stable < 1000) stable++;
        if (stable < 3 || sig == lastObservedSig) return;
        long now = System.currentTimeMillis();
        if (now - lastPass < 3000) return;
        lastObservedSig = sig;
        lastPass = now;
        try {
            observe(hs, now, cfg, hist);
        } catch (Throwable ignored) {
            // obserwacja nigdy nie może przeszkadzać botowi BK
        }
    }

    private static void observe(HandledScreen<?> hs, long now, AnalizaConfig cfg, PriceHistory hist) throws Exception {
        ScreenHandler h = hs.getScreenHandler();
        int end = Math.max(0, MarketAnalyzer.containerSize(h) - 9); // ostatni rząd = przyciski
        int[] pg = MarketAnalyzer.pageInfo(hs);

        double[] total = new double[end];
        for (int i = 0; i < end; i++) {
            ItemStack st = h.slots.get(i).getStack();
            total[i] = st.isEmpty() ? Double.NaN : priceOf(st);
        }
        boolean trusted = isTrustworthy(total, pg[0], pg[1]);

        ensureTargets(cfg);
        for (BkBridge.BkTarget t : targets) {
            List<Double> perPiece = new ArrayList<>();
            for (int i = 0; i < end; i++) {
                if (Double.isNaN(total[i])) continue;
                ItemStack st = h.slots.get(i).getStack();
                if (!t.matches(st)) continue;
                perPiece.add(total[i] / Math.max(1, st.getCount()));
            }
            if (perPiece.isEmpty()) continue;
            Collections.sort(perPiece);
            int k = Math.min(3, perPiece.size());
            double a3 = 0;
            for (int i = 0; i < k; i++) a3 += perPiece.get(i);
            a3 /= k;
            hist.record(t.key(), now, perPiece.get(0), a3, perPiece.size(),
                    trusted ? PriceHistory.SRC_PASSIVE_TRUSTED : PriceHistory.SRC_PASSIVE_SAMPLE);
        }
    }

    /**
     * Czy z tego okna da się odczytać prawdziwe minimum cen? Tak, gdy rynek ma jedną stronę (widać wszystkie oferty)
     * albo to strona 1 i ceny rosną w kolejności slotów (posortowane od najniższej ceny).
     */
    static boolean isTrustworthy(double[] totals, int page, int pages) {
        int priced = 0;
        double prev = -1;
        boolean nonDecreasing = true;
        for (double t : totals) {
            if (Double.isNaN(t)) continue;
            priced++;
            if (prev >= 0 && t < prev - 1e-9) nonDecreasing = false;
            prev = t;
        }
        if (priced == 0) return false;
        if (pages <= 1) return true;
        return page == 1 && priced >= 5 && nonDecreasing;
    }

    private static double priceOf(ItemStack st) {
        double v = BkBridge.extractPriceObserver(st);
        if (Double.isNaN(v) || v <= 0) v = MarketAnalyzer.parsePrice(st);
        return v > 0 ? v : Double.NaN;
    }

    private static void ensureTargets(AnalizaConfig cfg) throws Exception {
        long v = BkBridge.priceVersion();
        long now = System.currentTimeMillis();
        boolean stale = v != targetsVersion
                || (v < 0 && now - targetsBuiltAt > 30_000)
                || (targets.isEmpty() && now - targetsBuiltAt > 5_000);
        if (stale) {
            targets = BkBridge.observerTargets(cfg);
            targetsVersion = v;
            targetsBuiltAt = now;
        }
    }
}
