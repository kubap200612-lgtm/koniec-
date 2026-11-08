package pl.rynekanaliza;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Ustawienia w config/rynek-analiza.json (marże i ewentualne własne frazy wyszukiwania). */
public final class AnalizaConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Marża w $ używana, gdy przedmiot nie ma własnej. */
    public double defaultMargin = 100;
    /** Klucz = "nazwa bez kolorów|material" (małe litery), wartość = marża w $ (np. 100, 200, 500). */
    public Map<String, Double> margins = new LinkedHashMap<>();
    /** Klucz jak wyżej, wartość = fraza wpisywana po "/ah search " (domyślnie nazwa bez kolorów). */
    public Map<String, String> search = new LinkedHashMap<>();
    /** Ostatnia sugerowana marża z analizy (z 5 najtańszych ofert), klucz jak w margins. */
    public Map<String, Double> suggested = new LinkedHashMap<>();
    /** Mnożnik sugerowanej marży (1.0 = jeden "krok cenowy" między ofertami). */
    public double suggestFactor = 1.0;
    public double taxFactor = 0.97;
    public int listingsUsed = 3;
    public int minListings = 3;
    public int maxPages = 20;
    /** Szybkość analizy: 0 = bezpieczna, 1 = szybka (domyślnie), 2 = maksymalna. */
    public int speed = 1;
    /** Wymagaj tej samej liczby komponentów co we wzorcu z BK (BK sam tego nie sprawdza; włącz tylko, gdy coś się myli). */
    public boolean strictComponents = false;
    /** Zapisuj historię cen (z analiz i z obserwacji rynku) do wykresów. */
    public boolean historyEnabled = true;
    /** Obserwuj okna rynku otwierane przez BK (bez klikania) i zapisuj z nich ceny. */
    public boolean passiveObserve = true;
    /** Minimalny odstęp między zapisami z obserwacji dla jednego przedmiotu (sekundy). */
    public int historyIntervalSec = 60;
    /** Czy po analizie włączyć z powrotem BK-Rynek, jeśli działał przed startem. */
    public boolean resumeBk = true;
    /** Żółte podświetlenie najtańszych ofert + pasek z nazwą i ikoną nad rynkiem. */
    public boolean showOverlay = true;
    /** Ile ticków (20 = 1 s) trzymać stronę z podświetlonymi ofertami, żeby dało się je obejrzeć. 0 = bez pauzy. */
    public int highlightPauseTicks = 10;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("rynek-analiza.json");
    }

    public static AnalizaConfig load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                AnalizaConfig c = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), AnalizaConfig.class);
                if (c != null) {
                    if (c.margins == null) c.margins = new LinkedHashMap<>();
                    if (c.search == null) c.search = new LinkedHashMap<>();
                    if (c.suggested == null) c.suggested = new LinkedHashMap<>();
                    return c;
                }
            }
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się wczytać configu: " + e);
        }
        AnalizaConfig c = new AnalizaConfig();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.writeString(file(), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się zapisać configu: " + e);
        }
    }

    /** Przenosi ustawienia do silnika analizy. */
    public void applyToAnalyzer() {
        MarketAnalyzer.TAX_FACTOR = taxFactor;
        MarketAnalyzer.LISTINGS_USED = Math.max(1, listingsUsed);
        MarketAnalyzer.MIN_LISTINGS = Math.max(1, minListings);
        MarketAnalyzer.MAX_PAGES = Math.max(1, maxPages);
        switch (speed) {
            case 0 -> { MarketAnalyzer.MIN_CLICK_GAP_TICKS = 4; MarketAnalyzer.STABLE_TICKS = 3; MarketAnalyzer.BETWEEN_TARGETS_TICKS = 20; }
            case 2 -> { MarketAnalyzer.MIN_CLICK_GAP_TICKS = 1; MarketAnalyzer.STABLE_TICKS = 1; MarketAnalyzer.BETWEEN_TARGETS_TICKS = 2; }
            default -> { MarketAnalyzer.MIN_CLICK_GAP_TICKS = 2; MarketAnalyzer.STABLE_TICKS = 2; MarketAnalyzer.BETWEEN_TARGETS_TICKS = 6; }
        }
        MarketAnalyzer.HIGHLIGHT_PAUSE_TICKS = Math.max(0, highlightPauseTicks);
        MarketAnalyzer.SUGGEST_FACTOR = Math.max(0, suggestFactor);
        PriceHistory.ENABLED = historyEnabled;
        PriceHistory.PASSIVE_INTERVAL_SEC = Math.max(5, historyIntervalSec);
        AnalysisOverlay.enabled = showOverlay;
    }
}
