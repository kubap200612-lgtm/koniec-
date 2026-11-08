package pl.rynekanaliza;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Panel Rynek Analiza: zakładki "Przedmioty" (lista z BK-Rynek, marża, sugestia, start/stop), "Wykresy" (historia cen)
 * i "Ustawienia" (klawisze, szybkość, marża domyślna, historia itd.).
 * Listy są stronicowane (bez przewijania), a wszystkie widżety budujemy od nowa w init().
 */
public class AnalizaScreen extends Screen {
    private enum Tab { ITEMS, CHARTS, SETTINGS }

    private interface RowBuilder { void build(int x, int y, int w); }
    private record SettingRow(String label, RowBuilder builder) {}

    private static final int ROW_H = 22;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int GREEN = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int BLUE = 0xFF55AAFF;
    private static final String[] SPEED_NAMES = {"Bezpieczna", "Szybka", "Maksymalna"};
    private static final long HOUR = 3_600_000L;
    private static final long[] RANGES = {HOUR, 6 * HOUR, 24 * HOUR, 7 * 24 * HOUR, 0L};
    private static final String[] RANGE_NAMES = {"1 h", "6 h", "24 h", "7 dni", "Wszystko"};
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    private final AnalizaConfig cfg;
    private final List<BkBridge.BkTarget> all = new ArrayList<>();
    private List<BkBridge.BkTarget> filtered = new ArrayList<>();
    private String loadError = "";

    private Tab tab = Tab.ITEMS;
    private int page = 0;
    private int settingsPage = 0;
    private String filter = "";
    private KeyBinding listening;
    private boolean bgDone;

    // układ (liczony w init)
    private int panelX, panelW, rowsPerPage, tableTop;
    private int colName, colNameW, colPrice, colMargin, colMarginW, colSug, colUse, colUseW;
    private final List<BkBridge.BkTarget> pageRows = new ArrayList<>();
    private final List<SettingRow> settingRows = new ArrayList<>();
    private int settingsPerPage = 6;

    // wykresy
    private int rangeIdx = 2;
    private String chartKey = "";
    private List<BkBridge.BkTarget> chartItems = new ArrayList<>();
    private int chartX, chartY, chartW, chartH;
    private List<PriceHistory.Point> chartPoints = List.of();
    private PriceHistory.Stats chartStats = new PriceHistory.Stats();
    private double chartSuggest = Double.NaN;
    private long chartNow;
    private MarketProfile chartProfile = new MarketProfile();
    private MarketProfile.Decision chartDecision;
    private boolean chartCompact;

    public AnalizaScreen(AnalizaConfig cfg) {
        super(Text.literal("Rynek Analiza"));
        this.cfg = cfg;
        try {
            all.addAll(BkBridge.targets("", cfg));
        } catch (Throwable t) {
            loadError = t.toString();
        }
        applyFilter();
    }

    private void applyFilter() {
        String f = filter.trim().toLowerCase(Locale.ROOT);
        filtered = new ArrayList<>();
        for (BkBridge.BkTarget t : all) {
            if (f.isEmpty() || t.displayName().toLowerCase(Locale.ROOT).contains(f)) filtered.add(t);
        }
    }

    private void rebuild() {
        clearChildren();
        init();
    }

    private void switchTab(Tab t) {
        tab = t;
        listening = null;
        rebuild();
    }

    // ---------- budowanie widżetów ----------
    @Override
    protected void init() {
        panelW = Math.min(540, this.width - 8);
        panelX = (this.width - panelW) / 2;

        String[] names = {"Przedmioty", "Wykresy", "Ustawienia"};
        Tab[] tabs = {Tab.ITEMS, Tab.CHARTS, Tab.SETTINGS};
        for (int i = 0; i < 3; i++) {
            final Tab target = tabs[i];
            ButtonWidget b = ButtonWidget.builder(Text.literal(names[i]), x -> switchTab(target))
                    .dimensions(panelX + i * 104, 22, 100, 20).build();
            b.active = tab != target;
            addDrawableChild(b);
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Zamknij"), b -> close())
                .dimensions(panelX + panelW - 70, 22, 70, 20).build());

        switch (tab) {
            case ITEMS -> initItems();
            case CHARTS -> initCharts();
            case SETTINGS -> initSettings();
        }
    }

    // ----- zakładka Przedmioty -----
    private void initItems() {
        TextFieldWidget filterField = new TextFieldWidget(this.textRenderer, panelX + 52, 48, 150, 16, Text.empty());
        filterField.setMaxLength(40);
        filterField.setText(filter);
        filterField.setChangedListener(s -> {
            filter = s;
            applyFilter();
            page = 0;
            rebuild();
            // po przebudowie widżetów przywracamy fokus na polu wyszukiwania
            for (var child : this.children()) {
                if (child instanceof TextFieldWidget tf && tf.getY() == 48) { setFocused(tf); tf.setFocused(true); }
            }
        });
        addDrawableChild(filterField);

        String startLabel = filter.isBlank() ? "Start analizy" : "Start (filtr)";
        addDrawableChild(ButtonWidget.builder(Text.literal(startLabel), b -> {
            String f = filter.trim();
            close();
            RynekAnalizaMod.startAnalysis(f);
        }).dimensions(panelX + 208, 46, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Stop"), b -> MarketAnalyzer.stop())
                .dimensions(panelX + 312, 46, 50, 20).build());

        tableTop = 90;
        rowsPerPage = Math.max(1, (this.height - tableTop - 52) / ROW_H);
        int pages = Math.max(1, (filtered.size() + rowsPerPage - 1) / rowsPerPage);
        page = Math.min(Math.max(0, page), pages - 1);

        colName = panelX + 6;
        colNameW = (int) (panelW * 0.33);
        colPrice = panelX + (int) (panelW * 0.37);
        colMargin = panelX + (int) (panelW * 0.52);
        colMarginW = Math.max(52, (int) (panelW * 0.17));
        colSug = panelX + (int) (panelW * 0.72);
        colUse = panelX + (int) (panelW * 0.86);
        colUseW = panelX + panelW - colUse - 2;

        pageRows.clear();
        int from = page * rowsPerPage;
        int to = Math.min(filtered.size(), from + rowsPerPage);
        for (int i = from; i < to; i++) {
            BkBridge.BkTarget t = filtered.get(i);
            pageRows.add(t);
            addMarginRow(t, tableTop + (i - from) * ROW_H);
        }
        addPager(this.height - 40, pages, () -> page > 0, () -> page < pages - 1, () -> page--, () -> page++);
    }

    private void addPager(int y, int pages, java.util.function.BooleanSupplier hasPrev, java.util.function.BooleanSupplier hasNext,
                          Runnable prevAction, Runnable nextAction) {
        ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> { prevAction.run(); rebuild(); })
                .dimensions(panelX + panelW / 2 - 70, y, 24, 20).build();
        prev.active = hasPrev.getAsBoolean();
        addDrawableChild(prev);
        ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> { nextAction.run(); rebuild(); })
                .dimensions(panelX + panelW / 2 + 46, y, 24, 20).build();
        next.active = hasNext.getAsBoolean();
        addDrawableChild(next);
    }

    private void addMarginRow(BkBridge.BkTarget t, int y) {
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, colMargin, y, colMarginW, 16, Text.empty());
        f.setMaxLength(12);
        f.setTextPredicate(s -> s.matches("\\d*[.,]?\\d*"));
        Double own = cfg.margins.get(t.key());
        f.setText(own == null ? "" : fmt(own));
        f.setSuggestion(own == null ? fmt(cfg.defaultMargin) : null);
        f.setChangedListener(s -> {
            Double v = parse(s);
            if (s.isEmpty()) {
                cfg.margins.remove(t.key());
                f.setSuggestion(fmt(cfg.defaultMargin));
            } else if (v != null) {
                cfg.margins.put(t.key(), v);
                f.setSuggestion(null);
            }
        });
        addDrawableChild(f);

        Double sug = cfg.suggested.get(t.key());
        ButtonWidget use = ButtonWidget.builder(Text.literal("Użyj"), b -> {
            Double v = cfg.suggested.get(t.key());
            if (v != null) f.setText(fmt(v));
        }).dimensions(colUse, y - 2, colUseW, 20).build();
        use.active = sug != null;
        addDrawableChild(use);
    }

    // ----- zakładka Wykresy -----
    private void initCharts() {
        PriceHistory hist = RynekAnalizaMod.history();
        chartItems = new ArrayList<>();
        if (hist != null) for (BkBridge.BkTarget t : all) if (hist.count(t.key()) > 0) chartItems.add(t);
        int idx = chartIndex();
        if (idx < 0 && !chartItems.isEmpty()) { idx = 0; chartKey = chartItems.get(0).key(); }

        if (chartItems.size() > 1) {
            final int cur = idx;
            addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> selectChart(cur - 1))
                    .dimensions(panelX + 6, 46, 24, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> selectChart(cur + 1))
                    .dimensions(panelX + panelW - 30, 46, 24, 20).build());
        }
        for (int i = 0; i < RANGES.length; i++) {
            final int ri = i;
            ButtonWidget b = ButtonWidget.builder(Text.literal(RANGE_NAMES[i]), x -> { rangeIdx = ri; rebuild(); })
                    .dimensions(panelX + 6 + i * 64, 70, 60, 20).build();
            b.active = rangeIdx != i;
            addDrawableChild(b);
        }

        chartX = panelX + 6;
        chartY = 98;
        chartW = panelW - 12;
        // uklad od dolu: przycisk, nad nim linie tekstu co 11 px; w niskim oknie gubimy legende i "ostatni pomiar"
        chartCompact = this.height - 98 - 102 < 60;
        chartH = Math.max(40, this.height - 98 - (chartCompact ? 80 : 102));

        chartNow = System.currentTimeMillis();
        chartPoints = (hist == null || chartKey.isEmpty()) ? List.of() : hist.get(chartKey, RANGES[rangeIdx], chartNow);
        chartStats = PriceHistory.stats(chartPoints);
        chartSuggest = PriceHistory.suggestMargin(chartStats);
        chartProfile = MarketProfile.build(chartPoints);
        Double own = cfg.margins.get(chartKey);
        chartDecision = chartProfile.decide(own != null ? own : cfg.defaultMargin, cfg.taxFactor, chartNow);

        String label = Double.isNaN(chartSuggest) ? "Sugestia z historii: za mało danych (min. 6 pomiarów)"
                : "Użyj sugestii z historii jako marży: " + fmt(chartSuggest) + "$";
        ButtonWidget use = ButtonWidget.builder(Text.literal(label), b -> {
            cfg.margins.put(chartKey, chartSuggest);
            cfg.save();
            b.setMessage(Text.literal("Ustawiono marżę " + fmt(chartSuggest) + "$"));
            b.active = false;
        }).dimensions(panelX + 6, this.height - 30, Math.min(panelW - 12, 330), 20).build();
        use.active = !Double.isNaN(chartSuggest) && !chartKey.isEmpty();
        addDrawableChild(use);
    }

    private int chartIndex() {
        for (int i = 0; i < chartItems.size(); i++) if (chartItems.get(i).key().equals(chartKey)) return i;
        return -1;
    }

    private void selectChart(int idx) {
        if (chartItems.isEmpty()) return;
        chartKey = chartItems.get(Math.floorMod(idx, chartItems.size())).key();
        rebuild();
    }

    // ----- zakładka Ustawienia -----
    private void initSettings() {
        int vx = panelX + (int) (panelW * 0.56);
        int vw = (int) (panelW * 0.40);
        settingRows.clear();
        settingRows.add(new SettingRow("Klawisz: otwórz panel", (x, y, w) -> addKeyButton(x, y, w, RynekAnalizaMod.openKey)));
        settingRows.add(new SettingRow("Klawisz: start/stop analizy", (x, y, w) -> addKeyButton(x, y, w, RynekAnalizaMod.toggleKey)));
        settingRows.add(new SettingRow("Szybkość analizy", this::addSpeedButton));
        settingRows.add(new SettingRow("Domyślna marża ($)", (x, y, w) -> addNumberField(x, y, w, cfg.defaultMargin, false, v -> cfg.defaultMargin = v)));
        settingRows.add(new SettingRow("Podatek (mnożnik, np. 0.97)", (x, y, w) -> addNumberField(x, y, w, cfg.taxFactor, false, v -> cfg.taxFactor = v)));
        settingRows.add(new SettingRow("Mnożnik sugerowanej marży", (x, y, w) -> addNumberField(x, y, w, cfg.suggestFactor, false, v -> cfg.suggestFactor = v)));
        settingRows.add(new SettingRow("Pauza na ostatniej stronie (ticki)", (x, y, w) -> addNumberField(x, y, w, cfg.highlightPauseTicks, true, v -> cfg.highlightPauseTicks = (int) Math.round(v))));
        settingRows.add(new SettingRow("Podgląd na rynku (żółte oferty)", (x, y, w) -> addToggle(x, y, w, cfg.showOverlay, v -> cfg.showOverlay = v)));
        settingRows.add(new SettingRow("Wznów BK po analizie", (x, y, w) -> addToggle(x, y, w, cfg.resumeBk, v -> cfg.resumeBk = v)));
        settingRows.add(new SettingRow("Ścisłe sprawdzanie komponentów", (x, y, w) -> addToggle(x, y, w, cfg.strictComponents, v -> cfg.strictComponents = v)));
        settingRows.add(new SettingRow("Zapisuj historię cen (wykresy)", (x, y, w) -> addToggle(x, y, w, cfg.historyEnabled, v -> cfg.historyEnabled = v)));
        settingRows.add(new SettingRow("Obserwuj rynek, gdy działa BK", (x, y, w) -> addToggle(x, y, w, cfg.passiveObserve, v -> cfg.passiveObserve = v)));
        settingRows.add(new SettingRow("Odstęp zapisów z obserwacji (s)", (x, y, w) -> addNumberField(x, y, w, cfg.historyIntervalSec, true, v -> cfg.historyIntervalSec = (int) Math.round(v))));

        int top = 52, step = 22;
        settingsPerPage = Math.max(3, (this.height - top - 58) / step);
        int pages = Math.max(1, (settingRows.size() + settingsPerPage - 1) / settingsPerPage);
        settingsPage = Math.min(Math.max(0, settingsPage), pages - 1);
        int from = settingsPage * settingsPerPage;
        int to = Math.min(settingRows.size(), from + settingsPerPage);
        for (int i = from; i < to; i++) settingRows.get(i).builder().build(vx, top + (i - from) * step, vw);
        if (pages > 1) addPager(this.height - 40, pages, () -> settingsPage > 0, () -> settingsPage < pages - 1, () -> settingsPage--, () -> settingsPage++);
    }

    private void addSpeedButton(int x, int y, int w) {
        addDrawableChild(ButtonWidget.builder(Text.literal(SPEED_NAMES[Math.floorMod(cfg.speed, 3)]), b -> {
            cfg.speed = Math.floorMod(cfg.speed + 1, 3);
            cfg.applyToAnalyzer();
            b.setMessage(Text.literal(SPEED_NAMES[cfg.speed]));
        }).dimensions(x, y - 2, w, 20).build());
    }

    private void addKeyButton(int x, int y, int w, KeyBinding kb) {
        String label = listening == kb ? "> naciśnij klawisz <" : kb.getBoundKeyLocalizedText().getString();
        addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> { listening = kb; rebuild(); })
                .dimensions(x, y - 2, w, 20).build());
    }

    private void addNumberField(int x, int y, int w, double value, boolean integer, Consumer<Double> setter) {
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, x, y, w, 16, Text.empty());
        f.setMaxLength(10);
        f.setTextPredicate(s -> s.matches(integer ? "\\d*" : "\\d*[.,]?\\d*"));
        f.setText(fmt(value));
        f.setChangedListener(s -> {
            Double v = parse(s);
            if (v != null) {
                setter.accept(v);
                cfg.applyToAnalyzer();
            }
        });
        addDrawableChild(f);
    }

    private void addToggle(int x, int y, int w, boolean value, Consumer<Boolean> setter) {
        boolean[] state = {value};
        addDrawableChild(ButtonWidget.builder(Text.literal(value ? "TAK" : "NIE"), b -> {
            state[0] = !state[0];
            setter.accept(state[0]);
            cfg.applyToAnalyzer();
            b.setMessage(Text.literal(state[0] ? "TAK" : "NIE"));
        }).dimensions(x, y - 2, w, 20).build());
    }

    // ---------- rysowanie ----------
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (bgDone) return; // własne tło, rysowane raz na klatkę (bez rozmycia)
        bgDone = true;
        ctx.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        bgDone = false;
        renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fill(panelX - 4, 18, panelX + panelW + 4, this.height - 6, 0xE0202020);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Rynek Analiza"), this.width / 2, 8, WHITE);

        switch (tab) {
            case ITEMS -> drawItemsLabels(ctx);
            case CHARTS -> drawChartTab(ctx, mouseX, mouseY);
            case SETTINGS -> drawSettingsLabels(ctx);
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawItemsLabels(DrawContext ctx) {
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Szukaj:"), panelX + 6, 52, GRAY);

        int hy = tableTop - 14;
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Przedmiot"), colName, hy, YELLOW);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Skup ($)"), colPrice, hy, YELLOW);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Marża ($)"), colMargin, hy, YELLOW);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Sugestia"), colSug, hy, YELLOW);

        if (!loadError.isEmpty()) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("Nie mogę wczytać listy z BK-Rynek: " + loadError), panelX + 6, tableTop, 0xFFFF5555);
            return;
        }
        if (filtered.isEmpty()) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(all.isEmpty() ? "Lista przedmiotów w BK-Rynek jest pusta." : "Brak przedmiotów pasujących do wyszukiwania."), panelX + 6, tableTop, GRAY);
        }
        for (int i = 0; i < pageRows.size(); i++) {
            BkBridge.BkTarget t = pageRows.get(i);
            int y = tableTop + i * ROW_H;
            ctx.fill(panelX, y - 3, panelX + panelW, y + 18, i % 2 == 0 ? 0x30FFFFFF : 0x18FFFFFF);
            ctx.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(t.displayName(), colNameW), colName, y + 4, WHITE);
            double price = t.currentBuyPrice();
            ctx.drawTextWithShadow(this.textRenderer, price > 0 ? fmt(price) + "$" : "-", colPrice, y + 4, GRAY);
            Double sug = cfg.suggested.get(t.key());
            ctx.drawTextWithShadow(this.textRenderer, sug != null ? fmt(sug) + "$" : "-", colSug, y + 4, sug != null ? GREEN : GRAY);
        }

        int pages = Math.max(1, (filtered.size() + rowsPerPage - 1) / rowsPerPage);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Strona " + (page + 1) + "/" + pages + "  (" + filtered.size() + " przedm.)"),
                panelX + panelW / 2, this.height - 35, WHITE);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Sugestia = krok cenowy 5 najtańszych ofert (rozstęp / 4). Skup = średnia 3 najtańszych x podatek - marża."),
                panelX + 6, this.height - 16, GRAY);
    }

    private void drawSettingsLabels(DrawContext ctx) {
        int top = 52, step = 22;
        int from = settingsPage * settingsPerPage;
        int to = Math.min(settingRows.size(), from + settingsPerPage);
        for (int i = from; i < to; i++) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(settingRows.get(i).label()), panelX + 8, top + (i - from) * step + 3, WHITE);
        }
        int pages = Math.max(1, (settingRows.size() + settingsPerPage - 1) / settingsPerPage);
        if (pages > 1) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Ustawienia " + (settingsPage + 1) + "/" + pages),
                    panelX + panelW / 2, this.height - 35, WHITE);
        }
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Klawisz: Delete/Backspace = brak, Esc = anuluj. Komponenty: BK ich nie sprawdza - włącz tylko, gdy coś się myli."),
                panelX + 8, this.height - 16, GRAY);
    }

    // ----- wykres -----
    private void drawChartTab(DrawContext ctx, int mouseX, int mouseY) {
        if (chartItems.isEmpty()) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("Brak historii cen. Uruchom analizę (Start) albo zostaw włączoną obserwację rynku, gdy działa BK."), panelX + 8, 56, GRAY);
            return;
        }
        int idx = Math.max(0, chartIndex());
        String title = chartItems.get(idx).displayName() + "  (" + (idx + 1) + "/" + chartItems.size() + ")";
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(title, panelW - 80)),
                panelX + panelW / 2, 52, WHITE);

        ctx.fill(chartX - 1, chartY - 1, chartX + chartW + 1, chartY + chartH + 1, 0xFF505050);
        ctx.fill(chartX, chartY, chartX + chartW, chartY + chartH, 0xFF101010);
        if (chartPoints.isEmpty()) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("Brak pomiarów w tym okresie."), chartX + 6, chartY + 6, GRAY);
            return;
        }

        long tMax = chartNow;
        long tMin = RANGES[rangeIdx] > 0 ? chartNow - RANGES[rangeIdx] : chartPoints.get(0).t;
        if (tMax <= tMin) tMax = tMin + 1;
        double vMin = Double.MAX_VALUE, vMax = -Double.MAX_VALUE;
        for (PriceHistory.Point p : chartPoints) {
            vMin = Math.min(vMin, p.p1);
            vMax = Math.max(vMax, p.trusted() ? Math.max(p.a3, p.p1) : p.p1);
        }
        double pad = (vMax - vMin) * 0.08;
        if (pad < 1e-9) pad = Math.max(1, vMax * 0.02);
        vMin -= pad;
        vMax += pad;

        // próbki (zielone kropki), potem linie: najtańsza (niebieska) i średnia 3 najtańszych (żółta)
        int px = Integer.MIN_VALUE, pyB = 0, pyY = 0;
        for (PriceHistory.Point p : chartPoints) {
            int x = mapX(p.t, tMin, tMax);
            if (!p.trusted()) ctx.fill(x - 1, mapY(p.p1, vMin, vMax) - 1, x + 2, mapY(p.p1, vMin, vMax) + 2, 0xFF339933);
        }
        for (PriceHistory.Point p : chartPoints) {
            if (!p.trusted()) continue;
            int x = mapX(p.t, tMin, tMax);
            int yB = mapY(p.p1, vMin, vMax);
            int yY = mapY(p.a3, vMin, vMax);
            if (px != Integer.MIN_VALUE) {
                seg(ctx, px, pyB, x, yB, BLUE);
                seg(ctx, px, pyY, x, yY, YELLOW);
            }
            ctx.fill(x - 1, yB - 1, x + 2, yB + 2, BLUE);
            ctx.fill(x - 1, yY - 1, x + 2, yY + 2, YELLOW);
            px = x;
            pyB = yB;
            pyY = yY;
        }

        ctx.drawTextWithShadow(this.textRenderer, Text.literal(fmt(vMax) + "$"), chartX + 3, chartY + 2, GRAY);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(fmt(vMin) + "$"), chartX + 3, chartY + chartH - 10, GRAY);
        String left = TIME_FMT.format(java.time.Instant.ofEpochMilli(tMin));
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(left), chartX, chartY + chartH + 3, GRAY);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("teraz"), chartX + chartW - this.textRenderer.getWidth("teraz"), chartY + chartH + 3, GRAY);

        // najechanie myszą: najbliższy punkt
        if (mouseX >= chartX && mouseX < chartX + chartW && mouseY >= chartY && mouseY < chartY + chartH) {
            PriceHistory.Point best = null;
            int bestDx = Integer.MAX_VALUE;
            for (PriceHistory.Point p : chartPoints) {
                int dx = Math.abs(mapX(p.t, tMin, tMax) - mouseX);
                if (dx < bestDx) { bestDx = dx; best = p; }
            }
            if (best != null) {
                int bx = mapX(best.t, tMin, tMax);
                ctx.fill(bx, chartY, bx + 1, chartY + chartH, 0x66FFFFFF);
                String src = best.s == PriceHistory.SRC_ACTIVE ? "analiza" : best.s == PriceHistory.SRC_PASSIVE_TRUSTED ? "obserwacja" : "próbka z okien BK";
                String info = TIME_FMT.format(java.time.Instant.ofEpochMilli(best.t)) + "  najtańsza " + fmt(best.p1) + "$"
                        + (best.trusted() ? "  śr.3 " + fmt(best.a3) + "$" : "") + "  ofert: " + best.n + "  (" + src + ")";
                int tw = this.textRenderer.getWidth(info);
                int tx = Math.min(chartX + chartW - tw - 4, Math.max(chartX + 4, bx - tw / 2));
                ctx.fill(tx - 2, chartY + 12, tx + tw + 2, chartY + 24, 0xDD000000);
                ctx.drawTextWithShadow(this.textRenderer, Text.literal(info), tx, chartY + 14, WHITE);
            }
        }

        // statystyki i legenda pod wykresem
        int sy = chartY + chartH + 14;
        PriceHistory.Stats st = chartStats;
        if (st.count > 0) {
            double pct = st.mean > 0 ? st.std / st.mean * 100 : 0;
            double chg = st.first > 0 ? (st.last - st.first) / st.first * 100 : 0;
            String statsLine = fitParts(chartW,
                    "Pomiarów: " + st.count, "min " + fmtR(st.min), "śr. " + fmtR(st.mean), "max " + fmtR(st.max),
                    "rozrzut " + fmtR(st.std) + String.format(Locale.ROOT, " (%.1f%%)", pct),
                    String.format(Locale.ROOT, "zmiana %+.1f%%", chg));
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(statsLine), chartX, sy, WHITE);
            long ago = Math.max(0, (chartNow - st.lastT) / 60000L);
            if (!chartCompact) ctx.drawTextWithShadow(this.textRenderer, Text.literal("Ostatni pomiar: " + (ago < 60 ? ago + " min" : ago / 60 + " h") + " temu"), chartX, sy + 11, GRAY);
        } else {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("Brak zaufanych pomiarów (są tylko próbki z okien BK)."), chartX, sy, GRAY);
        }
        int ty = sy + (chartCompact ? 11 : 22);
        if (!chartCompact) {
            int lx = chartX;
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("niebieska: najtańsza oferta"), lx, ty, BLUE);
            lx += this.textRenderer.getWidth("niebieska: najtańsza oferta") + 8;
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("żółta: średnia 3 najtańszych"), lx, ty, YELLOW);
            lx += this.textRenderer.getWidth("żółta: średnia 3 najtańszych") + 8;
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("zielone: próbka z okien BK"), lx, ty, 0xFF339933);
            ty += 11;
        }

        // profil rynkowy i decyzja w trybie podglądu (nic nie kupuje)
        MarketProfile mp = chartProfile;
        if (mp.points > 0) {
            String prof = fitParts(chartW,
                    "Profil: norma " + fmtR(mp.normal) + "$", "dno " + fmtR(mp.floor) + "$",
                    String.format(Locale.ROOT, "zmienność %.1f%%", mp.volatilityPct),
                    String.format(Locale.ROOT, "trend %s (%+.1f%%)", mp.trendName(), mp.trendPct),
                    "ofert ~" + mp.depth);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(prof), chartX, ty, WHITE);
            ty += 11;
        }
        if (chartDecision != null) {
            int color = chartDecision.verdict == MarketProfile.Verdict.BUY ? GREEN
                    : chartDecision.verdict == MarketProfile.Verdict.REJECT ? 0xFFFF5555 : YELLOW;
            String head = "Podgląd (nic nie kupuje): ";
            int hw = this.textRenderer.getWidth(head);
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(head), chartX, ty, GRAY);
            String verdict = chartDecision.verdict + " - " + chartDecision.reason;
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(verdict, Math.max(40, chartW - hw))),
                    chartX + hw, ty, color);
        }
    }

    private int mapX(long t, long tMin, long tMax) {
        double f = (double) (t - tMin) / (double) (tMax - tMin);
        f = Math.max(0, Math.min(1, f));
        return chartX + (int) Math.round(f * (chartW - 1));
    }

    private int mapY(double v, double vMin, double vMax) {
        double f = (v - vMin) / (vMax - vMin);
        f = Math.max(0, Math.min(1, f));
        return chartY + chartH - 1 - (int) Math.round(f * (chartH - 1));
    }

    /** Linia między dwoma punktami: dla każdej kolumny pikseli jeden pionowy pasek (mało wywołań fill). */
    private static void seg(DrawContext c, int x0, int y0, int x1, int y1, int col) {
        if (x1 < x0) { int tx = x0; x0 = x1; x1 = tx; int ty = y0; y0 = y1; y1 = ty; }
        if (x1 == x0) {
            c.fill(x0, Math.min(y0, y1), x0 + 1, Math.max(y0, y1) + 1, col);
            return;
        }
        int prevY = y0;
        for (int x = x0; x <= x1; x++) {
            int y = y0 + Math.round((float) (y1 - y0) * (x - x0) / (x1 - x0));
            c.fill(x, Math.min(prevY, y), x + 1, Math.max(prevY, y) + 1, col);
            prevY = y;
        }
    }

    // ---------- zdarzenia ----------
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening != null) {
            KeyBinding kb = listening;
            listening = null;
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) kb.setBoundKey(InputUtil.UNKNOWN_KEY);
                else kb.setBoundKey(InputUtil.fromKeyCode(keyCode, scanCode));
                KeyBinding.updateKeysByCode();
                MinecraftClient.getInstance().options.write();
            }
            rebuild();
            return true;
        }
        boolean typing = getFocused() instanceof TextFieldWidget tf && tf.isFocused();
        if (!typing && RynekAnalizaMod.openKey != null && RynekAnalizaMod.openKey.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (listening != null) { // klik anuluje ustawianie klawisza
            listening = null;
            rebuild();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void removed() {
        cfg.save();
        cfg.applyToAnalyzer();
    }

    // ---------- liczby ----------
    private static Double parse(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Double.parseDouble(s.replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }

    static String fmt(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** Jak fmt, ale od 100 w górę bez ułamków (do ciasnych linii pod wykresem). */
    static String fmtR(double v) {
        return Math.abs(v) >= 100 ? String.valueOf(Math.round(v)) : fmt(v);
    }

    /** Składa części w jedną linię; części, które się już nie mieszczą w maxW, są pomijane (bez ucinania w pół słowa). */
    private String fitParts(int maxW, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String next = sb.length() == 0 ? p : sb + " | " + p;
            if (this.textRenderer.getWidth(next) > maxW) break;
            sb.setLength(0);
            sb.append(next);
        }
        return sb.length() == 0 && parts.length > 0 ? this.textRenderer.trimToWidth(parts[0], maxW) : sb.toString();
    }
}
