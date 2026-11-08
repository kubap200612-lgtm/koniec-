package pl.rynekanaliza;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Most do BK-Rynek 3.0 (1.21.4). Wszystko przez refleksję, więc ten mod nie ma zależności kompilacji od BK.
 *
 * Z jaru BK (sprawdzone na nazwach klas/metod, nie na działającej grze):
 *  - MarketService.shared().items()  -> żywa lista wpisów aktywnego profilu (core.PriceEntry, publiczne pola:
 *                                       name, material, maxPrice = cena skupu, componentCount, ...)
 *  - MarketService.save()            -> zapis pamięci do configu na dysk
 *  - ClientPriceListManager.markDirty() (prywatna) podbija wersję list cen, żeby auto-kupno i podświetlanie
 *                                       przeładowały ceny
 *  - AutoMarketScanner.matchesEntry(ItemStack, clean.PriceEntry) -> to samo dopasowanie, którego używa auto-kupno
 */
public final class BkBridge {
    private static final String P = "pl.bkubiak.bkrynek.";

    private static boolean inited;
    private static boolean ok;
    private static boolean matcherOk;
    private static String error = "";

    private static Method mShared, mItems, mProfile, mRunning, mStart, mStop, mSave;
    private static Method mMarkDirty, mFullReload, mAllEntries, mMatches, mExtractPrice, mGetVersion;
    private static Field fOriginals, fServersConfig, fIgnoreComponents;
    private static Object scanner;          // skaner silnika BK (do własnej analizy, gdy BK jest zatrzymany)
    private static Object observerScanner;  // osobna instancja do obserwacji w trakcie pracy BK (nie dzielimy stanu z jego wątkami)
    private static boolean priceOk;

    private BkBridge() {}

    // ---------- inicjalizacja ----------
    private static synchronized void init() {
        if (inited) return;
        inited = true;
        try {
            Class<?> cService = Class.forName(P + "client.market.MarketService");
            mShared = cService.getMethod("shared");
            mItems = cService.getMethod("items");
            mProfile = cService.getMethod("profile");
            mRunning = cService.getMethod("running");
            mStart = cService.getMethod("start");
            mStop = cService.getMethod("stop");
            mSave = cService.getMethod("save");

            Class<?> cClient = Class.forName(P + "client.BkRynekClient");
            fServersConfig = cClient.getField("serversConfig");

            Class<?> cPrices = Class.forName(P + "core.manager.ClientPriceListManager");
            mFullReload = cPrices.getMethod("fullReloadMemory", fServersConfig.getType());
            try { mGetVersion = cPrices.getMethod("getVersion"); } catch (NoSuchMethodException ignored) {}
            try {
                mMarkDirty = cPrices.getDeclaredMethod("markDirty");
                mMarkDirty.setAccessible(true);
            } catch (NoSuchMethodException ignored) {}
            try {
                fIgnoreComponents = cPrices.getDeclaredField("ignoreComponents");
                fIgnoreComponents.setAccessible(true);
            } catch (NoSuchFieldException ignored) {}
            ok = true;
        } catch (Throwable t) {
            ok = false;
            error = t.toString();
            return;
        }
        try {
            Class<?> cEngine = Class.forName(P + "client.market.CleanMarketEngine");
            mAllEntries = cEngine.getMethod("allEntries");
            fOriginals = cEngine.getDeclaredField("originals");
            fOriginals.setAccessible(true);
            Class<?> cScanner = Class.forName(P + "client.market.clean.AutoMarketScanner");
            // najlepiej użyć dokładnie tego skanera, którego używa silnik BK (ma ustawione regexy profilu)
            for (Field f : cEngine.getDeclaredFields()) {
                if (f.getType() == cScanner && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    scanner = f.get(null);
                    break;
                }
            }
            boolean ownScanner = false;
            if (scanner == null) {
                scanner = cScanner.getConstructor().newInstance();
                ownScanner = true;
            }
            for (Method m : cScanner.getMethods()) {
                if (m.getName().equals("matchesEntry") && m.getParameterCount() == 2) mMatches = m;
                if (m.getName().equals("extractPrice") && m.getParameterCount() == 1) mExtractPrice = m;
            }
            if (ownScanner) configureScanner(cScanner, scanner);
            try {
                observerScanner = cScanner.getConstructor().newInstance();
                configureScanner(cScanner, observerScanner);
            } catch (Throwable t) {
                observerScanner = null;
            }
            matcherOk = mMatches != null;
            priceOk = mExtractPrice != null;
        } catch (Throwable t) {
            matcherOk = false;
            error = "dopasowanie BK niedostępne (używam własnego): " + t;
        }
    }

    public static boolean available() {
        if (!FabricLoader.getInstance().isModLoaded("bkrynek")) return false;
        init();
        return ok;
    }

    public static String error() {
        init();
        return error;
    }

    public static boolean usesBkMatcher() {
        init();
        return matcherOk;
    }

    /** Gdy nie znaleziono skanera silnika BK: nasz własny dostaje regexy z aktywnego profilu. */
    private static void configureScanner(Class<?> cScanner, Object target) {
        try {
            Object profile = mProfile.invoke(service());
            String lore = (String) get(profile, "loreRegex");
            String seller = (String) get(profile, "loreSellerRegex");
            if (lore != null && !lore.isBlank()) cScanner.getMethod("setLoreRegex", String.class).invoke(target, lore);
            if (seller != null && !seller.isBlank()) cScanner.getMethod("setLoreSellerRegex", String.class).invoke(target, seller);
        } catch (Throwable ignored) {}
    }

    /** Cena całej oferty wg AutoMarketScanner.extractPrice z BK (pomija m.in. linie "koszt użycia"); NaN gdy brak. */
    static double extractPrice(ItemStack st) {
        return extractPrice(st, scanner);
    }

    /** To samo, ale na osobnym skanerze - do obserwacji, gdy BK pracuje. */
    static double extractPriceObserver(ItemStack st) {
        return extractPrice(st, observerScanner != null ? observerScanner : scanner);
    }

    private static double extractPrice(ItemStack st, Object sc) {
        if (!priceOk || sc == null) return Double.NaN;
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return Double.NaN;
        List<String> lines = new ArrayList<>();
        for (Text t : lore.lines()) lines.add(t.getString());
        try {
            Object r = mExtractPrice.invoke(sc, lines);
            double v = r instanceof Number n ? n.doubleValue() : Double.NaN;
            return v > 0 ? v : Double.NaN;
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    // ---------- sterowanie BK ----------
    private static Object service() throws Exception {
        return mShared.invoke(null);
    }

    public static boolean isRunning() {
        try { return (Boolean) mRunning.invoke(service()); } catch (Throwable t) { return false; }
    }

    public static void stopBk() {
        try { mStop.invoke(service()); } catch (Throwable t) { MarketAnalyzer.log.accept("Nie udało się zatrzymać BK: " + t); }
    }

    public static void startBk() {
        try { mStart.invoke(service()); } catch (Throwable t) { MarketAnalyzer.log.accept("Nie udało się wznowić BK: " + t); }
    }

    /** Zapis cen na dysk + powiadomienie BK, że ceny się zmieniły. */
    public static void commit() {
        try {
            mSave.invoke(service());
            if (mMarkDirty != null) mMarkDirty.invoke(null);
            else mFullReload.invoke(null, fServersConfig.get(null));
        } catch (Throwable t) {
            MarketAnalyzer.log.accept("Zapis cen do BK nie powiódł się: " + t);
        }
    }

    /** Wersja listy cen BK (zmienia się po każdej edycji listy); -1, gdy nieznana. */
    public static long priceVersion() {
        init();
        try { return mGetVersion == null ? -1 : ((Number) mGetVersion.invoke(null)).longValue(); } catch (Throwable t) { return -1; }
    }

    /** lore_regex z aktywnego profilu BK (grupa 1 = liczba) albo null. */
    public static Pattern priceRegex() {
        try {
            Object profile = mProfile.invoke(service());
            String rx = (String) get(profile, "loreRegex");
            if (rx == null || rx.isBlank()) return null;
            return Pattern.compile(rx);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- cele analizy ----------
    public static List<BkTarget> targets(String filter, AnalizaConfig cfg) throws Exception {
        init();
        List<?> items = new ArrayList<>((List<?>) mItems.invoke(service()));
        IdentityHashMap<Object, Object> coreToClean = new IdentityHashMap<>();
        if (matcherOk) {
            try {
                mAllEntries.invoke(null); // wymusza odświeżenie wewnętrznej listy BK
                Map<?, ?> originals = (Map<?, ?>) fOriginals.get(null);
                for (Map.Entry<?, ?> e : originals.entrySet()) coreToClean.put(e.getValue(), e.getKey());
            } catch (Throwable t) {
                error = "nie odczytałem wpisów BK (używam własnego dopasowania): " + t;
            }
        }
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        List<BkTarget> out = new ArrayList<>();
        for (Object core : items) {
            BkTarget t = new BkTarget(core, coreToClean.get(core), cfg);
            if (f.isEmpty() || t.displayName().toLowerCase(Locale.ROOT).contains(f)) out.add(t);
        }
        return out;
    }

    /** Cele do obserwacji rynku w trakcie pracy BK: dopasowanie i ceny liczone na osobnym skanerze. */
    public static List<BkTarget> observerTargets(AnalizaConfig cfg) throws Exception {
        List<BkTarget> l = targets("", cfg);
        for (BkTarget t : l) t.passive = true;
        return l;
    }

    public static List<String> describe(AnalizaConfig cfg) throws Exception {
        List<String> lines = new ArrayList<>();
        for (BkTarget t : targets("", cfg)) {
            lines.add(String.format("%s | skup %.0f | marża %.0f", t.displayName(), t.currentBuyPrice(), t.margin()));
        }
        return lines;
    }

    /** Ustawia marżę wszystkim przedmiotom, których nazwa zawiera fragment. Zwraca ich liczbę. */
    public static int setMargin(String fragment, double margin, AnalizaConfig cfg) throws Exception {
        int n = 0;
        for (BkTarget t : targets(fragment, cfg)) {
            cfg.margins.put(t.key(), margin);
            n++;
        }
        if (n > 0) cfg.save();
        return n;
    }

    // ---------- odczyt pól wpisu BK ----------
    static Object get(Object o, String field) {
        if (o == null) return null;
        try { return o.getClass().getField(field).get(o); } catch (Throwable t) { return null; }
    }

    static String str(Object o, String field) {
        Object v = get(o, field);
        return v instanceof String s ? s : "";
    }

    static boolean ignoreComponents() {
        try { return fIgnoreComponents != null && fIgnoreComponents.getBoolean(null); } catch (Throwable t) { return false; }
    }

    private static String strip(String s) {
        String r = Formatting.strip(s == null ? "" : s);
        return r == null ? "" : r.trim();
    }

    /** Przedmiot z listy BK jako cel analizy. */
    public static final class BkTarget implements MarketAnalyzer.Target {
        private final Object core;
        private final Object clean;
        private final AnalizaConfig cfg;
        private boolean warnedMatcher;
        boolean passive;

        BkTarget(Object core, Object clean, AnalizaConfig cfg) {
            this.core = core;
            this.clean = clean;
            this.cfg = cfg;
        }

        @Override
        public String displayName() {
            String n = strip(str(core, "name"));
            return n.isEmpty() ? str(core, "material") : n;
        }

        @Override
        public ItemStack icon() {
            String id = str(core, "material").trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) return ItemStack.EMPTY;
            Identifier ident = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (ident == null) return ItemStack.EMPTY;
            return new ItemStack(Registries.ITEM.get(ident));
        }

        public String key() {
            return (strip(str(core, "name")) + "|" + str(core, "material")).toLowerCase(Locale.ROOT);
        }

        public double currentBuyPrice() {
            Object v = get(core, "maxPrice");
            return v instanceof Number n ? n.doubleValue() : 0;
        }

        @Override
        public String searchQuery() {
            String custom = cfg.search.get(key());
            if (custom != null && !custom.isBlank()) return custom;
            String n = strip(str(core, "name"));
            if (!n.isEmpty()) return n;
            return str(core, "material").replace("minecraft:", "").replace('_', ' ');
        }

        @Override
        public void applySuggestion(double suggestedMargin, int offersUsed) {
            cfg.suggested.put(key(), suggestedMargin);
        }

        @Override
        public double margin() {
            return cfg.margins.getOrDefault(key(), cfg.defaultMargin);
        }

        /** Id przedmiotu z wpisu BK: małe litery, bez "minecraft:" (tak BK szuka wpisów w evaluateSlot). */
        private String wantedMaterial() {
            String m = str(core, "material").trim().toLowerCase(Locale.ROOT);
            return m.startsWith("minecraft:") ? m.substring("minecraft:".length()) : m;
        }

        // liczniki do diagnostyki (dlaczego nic nie pasuje)
        private int seenSameId, rejectedByBk, rejectedByComponents;
        private String exampleRejected = "";
        private final java.util.TreeSet<Integer> componentCountsSeen = new java.util.TreeSet<>();

        @Override
        public boolean matches(ItemStack stack) {
            if (stack.isEmpty()) return false;
            // BK najpierw wybiera wpisy po id przedmiotu, a dopiero potem porównuje nazwę/enchanty/lore.
            // Samo matchesEntry() id NIE sprawdza (zwykłe złote jabłko o nazwie "Zaklęte złote jabłko" by przeszło).
            String wantId = wantedMaterial();
            if (!wantId.isEmpty()) {
                String haveId = Registries.ITEM.getId(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
                if (!wantId.equals(haveId)) return false;
            }
            seenSameId++;
            if (matcherOk && clean != null) {
                try {
                    Object sc = passive && observerScanner != null ? observerScanner : scanner;
                    if (!(Boolean) mMatches.invoke(sc, stack, clean)) {
                        rejectedByBk++;
                        if (exampleRejected.isEmpty()) exampleRejected = strip(stack.getName().getString());
                        return false;
                    }
                } catch (Throwable t) {
                    if (!warnedMatcher) {
                        warnedMatcher = true;
                        MarketAnalyzer.log.accept("Dopasowanie BK rzuciło błąd: " + t);
                    }
                    return false;
                }
            } else if (!fallbackMatches(stack)) {
                rejectedByBk++;
                if (exampleRejected.isEmpty()) exampleRejected = strip(stack.getName().getString());
                return false;
            }
            // BK (ścieżka "clean") nie sprawdza liczby komponentów, więc domyślnie my też nie.
            // Włączane w ustawieniach tylko, gdy ktoś chce odróżniać przedmioty o tej samej nazwie.
            if (cfg.strictComponents && !ignoreComponents()) {
                Object cc = get(core, "componentCount");
                int want = cc instanceof Number n ? n.intValue() : 0;
                int have = stack.getComponents().getTypes().size();
                componentCountsSeen.add(have);
                if (want > 0 && have != want) {
                    rejectedByComponents++;
                    return false;
                }
            }
            return true;
        }

        @Override
        public void onMeasured(double cheapest, double avg3, int offers) {
            PriceHistory h = RynekAnalizaMod.history();
            if (h != null) h.record(key(), System.currentTimeMillis(), cheapest, avg3, offers, PriceHistory.SRC_ACTIVE);
        }

        @Override
        public double extractPrice(ItemStack stack) {
            return BkBridge.extractPrice(stack);
        }

        @Override
        public String diagnose() {
            return String.format("w oknie było %d przedm. o id '%s'; odrzucone przez dopasowanie BK: %d%s; odrzucone przez liczbę komponentów: %d%s; oczekiwana nazwa wpisu: '%s'",
                    seenSameId, wantedMaterial(), rejectedByBk,
                    exampleRejected.isEmpty() ? "" : " (np. \"" + exampleRejected + "\")",
                    rejectedByComponents, componentCountsSeen.isEmpty() ? "" : " (widziane liczby komponentów: " + componentCountsSeen + ")",
                    strip(str(core, "name")));
        }

        /** Proste dopasowanie awaryjne: id przedmiotu + nazwa bez kolorów. */
        private boolean fallbackMatches(ItemStack stack) {
            String material = str(core, "material").replace("minecraft:", "").toLowerCase(Locale.ROOT);
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            if (!material.isEmpty() && !material.equals(id)) return false;
            String name = strip(str(core, "name"));
            return name.isEmpty() || name.equalsIgnoreCase(strip(stack.getName().getString()));
        }

        @Override
        public void applyBuyPrice(double pricePerPiece) {
            try {
                core.getClass().getField("maxPrice").setDouble(core, pricePerPiece);
            } catch (Throwable t) {
                MarketAnalyzer.log.accept("Nie mogę wpisać ceny do BK: " + t);
            }
        }
    }
}
