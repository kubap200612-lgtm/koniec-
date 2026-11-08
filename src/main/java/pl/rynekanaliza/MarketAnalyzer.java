package pl.rynekanaliza;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Silnik analizy rynku (Fabric, Minecraft 1.21.4, mapowania Yarn). Nie zna BK-Rynku - ceny i listę
 * przedmiotów dostaje przez interfejs Target (patrz BkBridge).
 *
 * Dla każdego przedmiotu:
 *   1. "/ah search <x>"
 *   2. PPM w repeater (ostatni slot okna) DWA razy: "Brak motywu" -> "Najwyższej ceny" -> "Najniższej ceny"
 *   3. skan wszystkich stron "Rynek (n/m)" (zielony lime dye = następna strona)
 *   4. każda pasująca oferta -> cena ZA SZTUKĘ (cena z lore / liczba sztuk w stacku)
 *   5. 3 najtańsze -> średnia * 0.97 (podatek 3%) - marża  => cena skupu
 */
public final class MarketAnalyzer {

    /** Adapter na przedmiot z HUD. */
    public interface Target {
        /** Tekst "x" do komendy /ah search x. */
        String searchQuery();

        /** TA SAMA logika dopasowania co w auto-kupnie (id + nazwa + enchanty + komponenty). */
        boolean matches(ItemStack stack);

        /** Marża w $ przypisana do przedmiotu (np. 100, 200, 500). */
        double margin();

        /** Zapisz cenę skupu ZA SZTUKĘ do HUD. */
        void applyBuyPrice(double pricePerPiece);

        default void onFailed(String reason) {}

        /**
         * Cena całej oferty (nie za sztukę) odczytana z lore tak, jak robi to źródło listy (BK). NaN = nie umiem, użyj własnego odczytu.
         */
        default double extractPrice(ItemStack stack) { return Double.NaN; }

        /** Pomiar z analizy: najtańsza oferta, średnia 3 najtańszych (za sztukę) i liczba znalezionych ofert. */
        default void onMeasured(double cheapest, double avg3, int offers) {}

        /** Krótki opis, dlaczego oferty nie pasowały (do logu, gdy nic nie znaleziono). */
        default String diagnose() { return ""; }

        /** Sugerowana marża wyliczona z najtańszych ofert (wywoływana, gdy znaleziono co najmniej 3 oferty). */
        default void applySuggestion(double suggestedMargin, int offersUsed) {}

        /** Nazwa pokazywana nad rynkiem podczas analizy. */
        default String displayName() { return searchQuery(); }

        /** Ikona pokazywana nad rynkiem, zanim znajdzie się pierwsza oferta (potem pokazujemy prawdziwą ofertę). */
        default ItemStack icon() { return ItemStack.EMPTY; }
    }

    // ---------- ustawienia (możesz wyciągnąć do configu) ----------
    public static String PRICE_KEYWORD = "Koszt";                 // linia lore z ceną (gdy brak PRICE_REGEX)
    public static Pattern PRICE_REGEX = null;                     // lore_regex z profilu BK; grupa 1 = liczba
    public static Pattern MARKET_TITLE = Pattern.compile("rynek", Pattern.CASE_INSENSITIVE);
    public static double TAX_FACTOR = 0.97;                       // podatek 3%
    public static int SORT_CLICKS = 2;                            // Brak motywu -> Najwyższej -> Najniższej
    public static int LISTINGS_USED = 3;                          // ile najtańszych ofert uśredniamy do ceny skupu
    public static int SUGGEST_LISTINGS = 5;                       // z ilu najtańszych ofert liczymy sugerowaną marżę
    public static double SUGGEST_FACTOR = 1.0;                    // mnożnik sugerowanej marży
    public static int MIN_LISTINGS = 3;                           // poniżej tego: nie ustawiamy ceny
    public static int MAX_PAGES = 20;
    public static int OPEN_TIMEOUT_TICKS = 120;
    public static int PAGE_TIMEOUT_TICKS = 80;
    public static int WAIT_TIMEOUT_TICKS = 40;                    // max czekanie na odpowiedź serwera po kliknięciu
    public static int MIN_CLICK_GAP_TICKS = 2;                    // minimalna przerwa między kliknięciami
    public static int STABLE_TICKS = 2;                           // tyle ticków bez zmian = okno "uspokojone"
    public static int BETWEEN_TARGETS_TICKS = 6;
    public static int MAX_RETRIES = 2;
    public static int HIGHLIGHT_PAUSE_TICKS = 10;                 // pauza na ostatniej stronie z podświetleniem (0 = bez pauzy)

    public static Runnable onFinished = () -> {};
    public static Consumer<String> log = msg -> {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("[Analiza] " + msg), false);
    };

    // ---------- stan ----------
    private enum Step { IDLE, OPEN, WAIT_MARKET, SORT, SORT_WAIT, SCAN, ADVANCE, WAIT_PAGE }

    private static Step step = Step.IDLE;
    private static List<? extends Target> queue = List.of();
    private static int queueIdx;
    private static Target current;
    private static int wait;
    private static int ticksInStep;
    private static int sortClicksDone;
    private static int pagesScanned;
    private static int lastPage;
    private static int retries;
    private static int baseSig, lastSig, stableCount;     // czekanie na odpowiedź serwera (zmiana i uspokojenie okna)
    private static boolean sigChanged, watching, pauseDone, warnedSort;
    private static int priceFailed;                       // oferty pasujące, ale bez odczytanej ceny
    private static final List<Listing> listings = new ArrayList<>();
    private static ItemStack sampleStack;                 // pierwsza znaleziona oferta (do ikony nad rynkiem)
    private static boolean stackable;                     // czy przedmiot da się składać w stacki (np. złote jabłka)
    private static int highlightPage = -1;
    private static final java.util.Set<Integer> highlightSlots = new java.util.HashSet<>();   // żółte: idą do ceny skupu
    private static final java.util.Set<Integer> highlightSlots2 = new java.util.HashSet<>();  // pomarańczowe: tylko do sugestii
    private static int scanPage = 1, scanTotal = 1;

    /** Jedna pasująca oferta: strona, slot w oknie i cena za sztukę. */
    private static final class Listing {
        final int page, slot;
        final double perPiece;
        final String label;
        Listing(int page, int slot, double perPiece, String label) { this.page = page; this.slot = slot; this.perPiece = perPiece; this.label = label; }
    }
    private static boolean registered;

    private MarketAnalyzer() {}

    // ---------- API ----------
    public static void init() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(MarketAnalyzer::tick);
    }

    public static boolean isRunning() { return step != Step.IDLE; }

    public static boolean start(List<? extends Target> targets) {
        if (isRunning() || targets == null || targets.isEmpty()) return false;
        queue = new ArrayList<>(targets);
        queueIdx = 0;
        log.accept("Start analizy rynku: " + queue.size() + " przedm.");
        nextTarget();
        return true;
    }

    public static void stop() {
        if (!isRunning()) return;
        finishAll("Analiza przerwana.");
    }

    // ---------- główna pętla ----------
    private static void tick(MinecraftClient mc) {
        if (step == Step.IDLE) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            finishAll("Rozłączono - analiza przerwana.");
            return;
        }
        ticksInStep++;
        if (wait > 0) { wait--; return; }
        switch (step) {
            case OPEN -> openMarket(mc);
            case WAIT_MARKET -> waitMarket(mc);
            case SORT -> sort(mc);
            case SORT_WAIT -> sortWait(mc);
            case SCAN -> scan(mc);
            case ADVANCE -> advance(mc);
            case WAIT_PAGE -> waitPage(mc);
            default -> {}
        }
    }

    private static void setStep(Step s, int delay) {
        step = s;
        ticksInStep = 0;
        wait = delay;
        watching = false;
    }

    private static void nextTarget() {
        if (queueIdx >= queue.size()) { finishAll("Analiza zakończona."); return; }
        current = queue.get(queueIdx++);
        resetView();
        sortClicksDone = 0;
        pagesScanned = 0;
        lastPage = -1;
        retries = 0;
        setStep(Step.OPEN, queueIdx == 1 ? 0 : BETWEEN_TARGETS_TICKS);
    }

    private static void finishAll(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
        step = Step.IDLE;
        current = null;
        resetView();
        warnedSort = false;
        log.accept(msg);
        try { onFinished.run(); } catch (Throwable t) { t.printStackTrace(); }
    }

    // ---------- czekanie na serwer zamiast stałych opóźnień ----------
    /** Odcisk zawartości okna (tytuł + tożsamość stacków): zmienia się, gdy serwer przysłał nowe dane. */
    static int signature(HandledScreen<?> hs) {
        ScreenHandler h = hs.getScreenHandler();
        int n = containerSize(h);
        int sig = hs.getTitle().getString().hashCode();
        for (int i = 0; i < n; i++) sig = 31 * sig + System.identityHashCode(h.slots.get(i).getStack());
        return sig;
    }

    /** Zaczyna czekać na odpowiedź serwera po akcji (np. kliknięciu). */
    private static void beginWait(HandledScreen<?> hs) {
        baseSig = lastSig = signature(hs);
        stableCount = 0;
        sigChanged = false;
        watching = true;
    }

    /**
     * true, gdy okno przestało się zmieniać od STABLE_TICKS ticków.
     * requireChange = true: najpierw musi nastąpić jakaś zmiana względem beginWait (odpowiedź serwera na kliknięcie).
     */
    private static boolean settled(HandledScreen<?> hs, boolean requireChange) {
        int s = signature(hs);
        if (!sigChanged && s != baseSig) { sigChanged = true; lastSig = s; stableCount = 0; return false; }
        if (requireChange && !sigChanged) return false;
        if (s == lastSig) stableCount++; else { lastSig = s; stableCount = 0; }
        return stableCount >= Math.max(1, STABLE_TICKS);
    }

    // ---------- kroki ----------
    private static void openMarket(MinecraftClient mc) {
        if (mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
        String q = Formatting.strip(current.searchQuery());
        mc.player.networkHandler.sendChatCommand("ah search " + (q == null ? "" : q.trim()));
        setStep(Step.WAIT_MARKET, 0);
    }

    private static void waitMarket(MinecraftClient mc) {
        HandledScreen<?> hs = marketScreen(mc);
        if (hs != null && ready(hs)) {
            if (!watching) { beginWait(hs); return; }
            if (settled(hs, false)) { sortClicksDone = 0; setStep(Step.SORT, 0); return; }
        } else {
            watching = false;
        }
        if (ticksInStep > OPEN_TIMEOUT_TICKS) retryOrFail("rynek się nie otworzył");
    }

    private static void sort(MinecraftClient mc) {
        HandledScreen<?> hs = marketScreen(mc);
        if (hs == null || !ready(hs)) { retryOrFail("zniknęło okno rynku przy sortowaniu"); return; }
        ScreenHandler h = hs.getScreenHandler();
        int sortSlot = containerSize(h) - 1; // prawy dolny róg = repeater

        // jeśli już jest "Najniższej ceny" (potwierdzone odczytem lore) - nie klikamy
        if (sortClicksDone == 0 && sortLooksLowest(h.slots.get(sortSlot).getStack())) {
            pagesScanned = 0;
            lastPage = -1;
            setStep(Step.SCAN, 0);
            return;
        }
        click(mc, hs, sortSlot, 1); // PPM = przełącz
        sortClicksDone++;
        if (sortClicksDone < Math.max(1, SORT_CLICKS)) {
            setStep(Step.SORT, MIN_CLICK_GAP_TICKS);
        } else {
            beginWait(hs); // po ostatnim kliknięciu czekamy na odpowiedź serwera
            setStep(Step.SORT_WAIT, 0);
            watching = true;
        }
    }

    private static void sortWait(MinecraftClient mc) {
        HandledScreen<?> hs = marketScreen(mc);
        boolean ok = hs != null && ready(hs) && settled(hs, true);
        if (!ok && ticksInStep <= WAIT_TIMEOUT_TICKS) return;
        if (hs == null || !ready(hs)) { retryOrFail("zniknęło okno rynku po sortowaniu"); return; }
        ScreenHandler h = hs.getScreenHandler();
        if (!warnedSort && !sortLooksLowest(h.slots.get(containerSize(h) - 1).getStack())) {
            warnedSort = true;
            log.accept("Uwaga: nie potwierdzam odczytem lore, że sortowanie = 'Najniższej ceny' (kontynuuję po " + SORT_CLICKS + " kliknięciach).");
        }
        pagesScanned = 0;
        lastPage = -1;
        setStep(Step.SCAN, 0);
    }

    private static void scan(MinecraftClient mc) {
        HandledScreen<?> hs = marketScreen(mc);
        if (hs == null) { retryOrFail("zniknęło okno rynku przy skanowaniu"); return; }
        ScreenHandler h = hs.getScreenHandler();
        int container = containerSize(h);
        int listingsEnd = container - 9; // ostatni rząd = przyciski
        int[] pg = pageInfo(hs);
        scanPage = pg[0];
        scanTotal = pg[1];

        for (int i = 0; i < listingsEnd; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (st.isEmpty() || !current.matches(st)) continue;
            double total = current.extractPrice(st);              // tak jak źródło listy (BK)
            if (Double.isNaN(total) || total <= 0) total = parsePrice(st); // własny odczyt jako zapas
            if (Double.isNaN(total) || total <= 0) { priceFailed++; continue; }
            if (sampleStack == null) sampleStack = st.copyWithCount(1);
            if (st.getMaxCount() > 1) stackable = true;
            String id = Registries.ITEM.getId(st.getItem()).getPath();
            String nm = Formatting.strip(st.getName().getString());
            listings.add(new Listing(pg[0], i, total / Math.max(1, st.getCount()), id + " \"" + nm + "\"" + (st.getCount() > 1 ? " x" + st.getCount() : ""))); // cena za sztukę
        }
        pagesScanned++;

        // podświetlenie: oferty tej strony, które są teraz w gronie najtańszych (żółte = do ceny, pomarańczowe = do sugestii)
        highlightSlots.clear();
        highlightSlots2.clear();
        highlightPage = pg[0];
        int rank = 0;
        for (Listing l : cheapest(neededListings())) {
            if (l.page == pg[0]) (rank < Math.max(1, LISTINGS_USED) ? highlightSlots : highlightSlots2).add(l.slot);
            rank++;
        }

        setStep(Step.ADVANCE, 0);
    }

    private static void advance(MinecraftClient mc) {
        HandledScreen<?> hs = marketScreen(mc);
        if (hs == null) { retryOrFail("zniknęło okno rynku przy przechodzeniu na następną stronę"); return; }
        ScreenHandler h = hs.getScreenHandler();
        int container = containerSize(h);
        int[] pg = pageInfo(hs);

        // Przedmioty niestackowalne (po 1 szt., np. hełmy): rynek jest posortowany od najniższej ceny, więc gdy mamy
        // już tyle ofert, ile potrzeba do ceny skupu i sugestii marży (5), dalsze strony mają tylko droższe - kończymy.
        // Przedmioty stackowalne (np. złote jabłka): większy stack na dalszej stronie może mieć niższą cenę za sztukę,
        // więc przeglądamy wszystkie strony. Ta zasada jest na stałe, bez ustawienia w configu.
        boolean finishing = !stackable && listings.size() >= neededListings();
        int next = -1;
        if (!finishing) {
            boolean more = pg[0] < pg[1] && pagesScanned < MAX_PAGES;
            next = more ? findNextButton(h, container) : -1;
            if (next < 0) finishing = true;
        }

        if (finishing) {
            // krótka pauza na ostatniej stronie, żeby dało się obejrzeć podświetlone oferty
            boolean anyHighlight = !highlightSlots.isEmpty() || !highlightSlots2.isEmpty();
            if (HIGHLIGHT_PAUSE_TICKS > 0 && anyHighlight && !pauseDone) {
                pauseDone = true;
                setStep(Step.ADVANCE, HIGHLIGHT_PAUSE_TICKS);
                return;
            }
            finishTarget();
            return;
        }

        lastPage = pg[0];
        highlightSlots.clear();
        highlightSlots2.clear();
        click(mc, hs, next, 0);
        beginWait(hs);
        setStep(Step.WAIT_PAGE, MIN_CLICK_GAP_TICKS);
        watching = true;
    }

    private static void waitPage(MinecraftClient mc) {
        HandledScreen<?> hs = marketScreen(mc);
        boolean settled = hs != null && ready(hs) && settled(hs, true);
        if (settled && pageInfo(hs)[0] != lastPage) {
            setStep(Step.SCAN, 0);
            return;
        }
        if (ticksInStep > PAGE_TIMEOUT_TICKS) {
            log.accept("Timeout przy zmianie strony - liczę z tego, co zebrałem.");
            finishTarget();
        }
    }

    // ---------- wynik ----------
    private static void finishTarget() {
        List<Double> prices = new ArrayList<>();
        for (Listing l : listings) prices.add(l.perPiece);

        // sugerowana marża z (do) 5 najtańszych ofert - liczymy zawsze, także gdy cena skupu wyjdzie <= 0
        double suggested = computeSuggestedMargin(prices);
        String sugText = "brak sugestii (min. 3 oferty)";
        if (!Double.isNaN(suggested)) {
            int n = Math.min(Math.max(1, SUGGEST_LISTINGS), prices.size());
            current.applySuggestion(suggested, n);
            sugText = String.format("sugerowana marża %.0f (z %d najtańszych ofert)", suggested, n);
        }

        // pomiar do historii cen (min. 3 oferty = jest z czego liczyć średnią)
        if (prices.size() >= 3) {
            List<Listing> three = cheapest(3);
            double a3 = 0;
            for (Listing l : three) a3 += l.perPiece;
            current.onMeasured(three.get(0).perPiece, a3 / three.size(), prices.size());
        }

        double buy = computeBuyPrice(prices, current.margin());
        if (Double.isNaN(buy)) {
            log.accept(Formatting.strip(current.searchQuery()) + ": " + sugText);
            String diag = current.diagnose();
            log.accept("Diagnostyka: " + (diag.isEmpty() ? "" : diag + "; ") + "oferty bez odczytanej ceny: " + priceFailed);
            failTarget("za mało ofert (" + prices.size() + "/" + MIN_LISTINGS + ") albo cena skupu <= 0");
            return;
        }
        List<Listing> used = cheapest(LISTINGS_USED);
        double avg = 0;
        StringBuilder where = new StringBuilder();
        for (Listing l : used) {
            avg += l.perPiece;
            if (where.length() > 0) where.append("; ");
            where.append(String.format("str.%d r%d k%d = %.0f/szt (%s)", l.page, l.slot / 9 + 1, l.slot % 9 + 1, l.perPiece, l.label));
        }
        avg /= used.size();
        current.applyBuyPrice(buy);
        log.accept(String.format("%s: średnia %d najtańszych = %.2f, po podatku %.2f, marża %.0f -> skup %.0f; %s",
                Formatting.strip(current.searchQuery()), used.size(), avg, avg * TAX_FACTOR, current.margin(), buy, sugText));
        log.accept("Użyte oferty: " + where);
        nextTarget();
    }

    /** Ile ofert trzeba zebrać (dla niestackowalnych): tyle, ile potrzeba do ceny skupu i do sugestii marży. */
    private static int neededListings() {
        return Math.max(Math.max(1, LISTINGS_USED), Math.max(1, SUGGEST_LISTINGS));
    }

    /**
     * Sugerowana marża = typowy "krok cenowy" między kolejnymi ofertami wśród najtańszych:
     * (cena najdroższej z N najtańszych - cena najtańszej) / (N - 1), N = min(5, liczba ofert), razy mnożnik.
     * Zaokrąglenie: do 10 dla wartości >= 100, w pozostałych przypadkach do pełnych $. NaN, gdy ofert < 3.
     */
    public static double computeSuggestedMargin(List<Double> perPiece) {
        List<Double> sorted = new ArrayList<>(perPiece);
        Collections.sort(sorted);
        int n = Math.min(Math.max(1, SUGGEST_LISTINGS), sorted.size());
        if (n < 3) return Double.NaN;
        double step = (sorted.get(n - 1) - sorted.get(0)) / (n - 1);
        double v = step * SUGGEST_FACTOR;
        v = v >= 100 ? Math.round(v / 10.0) * 10.0 : Math.round(v);
        return Math.max(0, v);
    }

    /** Najtańsze oferty za sztukę (stabilnie, wg kolejności na rynku). */
    private static List<Listing> cheapest(int limit) {
        List<Listing> sorted = new ArrayList<>(listings);
        sorted.sort((x, y) -> Double.compare(x.perPiece, y.perPiece));
        return sorted.subList(0, Math.min(Math.max(1, limit), sorted.size()));
    }

    private static void resetView() {
        listings.clear();
        sampleStack = null;
        stackable = false;
        pauseDone = false;
        priceFailed = 0;
        highlightSlots.clear();
        highlightSlots2.clear();
        highlightPage = -1;
        scanPage = 1;
        scanTotal = 1;
    }

    // ---------- stan do rysowania (AnalysisOverlay) ----------
    public static Target currentTarget() { return current; }
    public static int targetIndex() { return queueIdx; }
    public static int targetCount() { return queue.size(); }
    public static int scanPage() { return scanPage; }
    public static int scanTotal() { return scanTotal; }
    public static int foundCount() { return listings.size(); }
    public static int highlightPage() { return highlightPage; }
    /** true = przedmiot stackowalny, skan wszystkich stron; false = skan do znalezienia LISTINGS_USED ofert. */
    public static boolean fullScan() { return stackable; }
    public static int listingsUsed() { return neededListings(); }
    public static java.util.Set<Integer> highlightSlots() { return highlightSlots; }
    public static java.util.Set<Integer> highlightSlots2() { return highlightSlots2; }

    /** Ikona nad rynkiem: prawdziwa pierwsza oferta, a dopóki jej nie ma - ikona z przedmiotu na liście. */
    public static ItemStack iconStack() {
        if (sampleStack != null) return sampleStack;
        return current == null ? ItemStack.EMPTY : current.icon();
    }

    public static boolean isMarket(HandledScreen<?> hs) {
        String title = Formatting.strip(hs.getTitle().getString());
        return title != null && MARKET_TITLE.matcher(title).find();
    }

    public static int pageOf(HandledScreen<?> hs) { return pageInfo(hs)[0]; }

    private static void failTarget(String reason) {
        log.accept(Formatting.strip(current.searchQuery()) + ": " + reason + " - cena NIE ustawiona.");
        try { current.onFailed(reason); } catch (Throwable t) { t.printStackTrace(); }
        nextTarget();
    }

    private static void retryOrFail(String reason) {
        if (retries < MAX_RETRIES) {
            retries++;
            resetView();
            sortClicksDone = 0;
            pagesScanned = 0;
            lastPage = -1;
            log.accept(reason + " - ponawiam (" + retries + "/" + MAX_RETRIES + ")");
            setStep(Step.OPEN, 40);
        } else {
            failTarget(reason);
        }
    }

    /**
     * Czysta logika cen. perPiece = ceny za sztukę wszystkich pasujących ofert.
     * Zwraca NaN, gdy ofert jest za mało lub wynik <= 0.
     * skup = floor( średnia(3 najtańsze) * 0.97 - marża )
     */
    public static double computeBuyPrice(List<Double> perPiece, double margin) {
        if (perPiece.size() < Math.max(1, MIN_LISTINGS)) return Double.NaN;
        List<Double> sorted = new ArrayList<>(perPiece);
        Collections.sort(sorted);
        int n = Math.min(LISTINGS_USED, sorted.size());
        double sum = 0;
        for (int i = 0; i < n; i++) sum += sorted.get(i);
        double buy = Math.floor(sum / n * TAX_FACTOR - margin);
        return buy > 0 ? buy : Double.NaN;
    }

    // ---------- pomocnicze: okno ----------
    private static HandledScreen<?> marketScreen(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof HandledScreen<?> hs)) return null;
        String title = Formatting.strip(hs.getTitle().getString());
        return title != null && MARKET_TITLE.matcher(title).find() ? hs : null;
    }

    /** Liczba slotów samego okna (bez 36 slotów ekwipunku gracza). */
    static int containerSize(ScreenHandler h) { return h.slots.size() - 36; }

    static boolean ready(HandledScreen<?> hs) {
        ScreenHandler h = hs.getScreenHandler();
        int size = containerSize(h);
        return size >= 18 && h.slots.get(size - 1).getStack().isOf(Items.REPEATER);
    }

    private static void click(MinecraftClient mc, HandledScreen<?> hs, int slot, int button) {
        mc.interactionManager.clickSlot(hs.getScreenHandler().syncId, slot, button, SlotActionType.PICKUP, mc.player);
    }

    private static final Pattern PAGE = Pattern.compile("\\((\\d+)\\s*/\\s*(\\d+)\\)");

    static int[] pageInfo(HandledScreen<?> hs) {
        Matcher m = PAGE.matcher(hs.getTitle().getString());
        if (m.find()) {
            try { return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))}; }
            catch (NumberFormatException ignored) {}
        }
        return new int[]{1, 1};
    }

    private static int findNextButton(ScreenHandler h, int container) {
        int fallback = -1;
        for (int i = container - 9; i < container; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (!st.isOf(Items.LIME_DYE)) continue;
            String n = Formatting.strip(st.getName().getString());
            if (n != null && n.toLowerCase().contains("nast")) return i; // "Następna strona"
            if (fallback < 0) fallback = i;
        }
        return fallback;
    }

    /** Best-effort: zaznaczona opcja w lore repeatera jest podkreślona (widać na zrzucie). Tylko do logu. */
    private static boolean sortLooksLowest(ItemStack repeater) {
        LoreComponent lore = repeater.get(DataComponentTypes.LORE);
        if (lore == null) return false;
        for (Text line : lore.lines()) {
            String s = Formatting.strip(line.getString());
            if (s == null || !s.toLowerCase().contains("najniższej ceny")) continue;
            boolean[] underlined = {false};
            line.visit((style, str) -> {
                if (!str.isBlank() && style.isUnderlined()) underlined[0] = true;
                return Optional.empty();
            }, Style.EMPTY);
            return underlined[0];
        }
        return false;
    }

    // ---------- pomocnicze: cena z lore ----------
    private static final Pattern PRICE = Pattern.compile(
            "(\\d{1,3}(?:[ \\u00A0.,]\\d{3})+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?)\\s*(mld|mln|k|m)?(?![a-z])",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TOKEN = Pattern.compile("^([\\d.,\\s\u00A0]+?)\\s*(mld|mln|k|m)?$", Pattern.CASE_INSENSITIVE);

    /** Cena z lore oferty; NaN gdy nie znaleziono. Najpierw regex z profilu BK, potem słowo "Koszt". */
    static double parsePrice(ItemStack st) {
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return Double.NaN;
        List<String> lines = new ArrayList<>();
        for (Text t : lore.lines()) {
            String line = Formatting.strip(t.getString());
            if (line != null) lines.add(line);
        }
        if (PRICE_REGEX != null) {
            for (String line : lines) {
                Matcher m = PRICE_REGEX.matcher(line);
                if (m.matches() && m.groupCount() >= 1 && m.group(1) != null) {
                    Matcher tk = TOKEN.matcher(m.group(1).trim());
                    if (tk.matches()) {
                        double v = parseNumber(tk.group(1).trim(), tk.group(2));
                        if (!Double.isNaN(v)) return v;
                    }
                }
            }
        }
        for (String line : lines) {
            int k = line.toLowerCase().indexOf(PRICE_KEYWORD.toLowerCase());
            if (k < 0) continue;
            Matcher m = PRICE.matcher(line);
            if (m.find(k + PRICE_KEYWORD.length())) {
                double v = parseNumber(m.group(1), m.group(2));
                if (!Double.isNaN(v)) return v;
            }
        }
        return Double.NaN;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    /** "1 500", "1.500", "1,500.50", "1.5k", "2m", "1mld" -> liczba. */
    static double parseNumber(String tok, String suffix) {
        try {
            tok = tok.replace(" ", "").replace("\u00A0", "");
            if (suffix != null) {
                double mult = switch (suffix.toLowerCase()) {
                    case "k" -> 1e3;
                    case "m", "mln" -> 1e6;
                    case "mld" -> 1e9;
                    default -> 1;
                };
                if (count(tok, '.') + count(tok, ',') > 1) return Double.NaN;
                return Double.parseDouble(tok.replace(',', '.')) * mult;
            }
            boolean dot = tok.indexOf('.') >= 0, comma = tok.indexOf(',') >= 0;
            if (dot && comma) {
                int dec = Math.max(tok.lastIndexOf('.'), tok.lastIndexOf(','));
                tok = tok.substring(0, dec).replace(".", "").replace(",", "") + "." + tok.substring(dec + 1);
            } else if (dot || comma) {
                char sep = comma ? ',' : '.';
                if (count(tok, sep) > 1 || tok.matches("\\d{1,3}[.,]\\d{3}")) tok = tok.replace(".", "").replace(",", "");
                else tok = tok.replace(',', '.');
            }
            return Double.parseDouble(tok);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
