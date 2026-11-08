package pl.rynekanaliza;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Historia cen przedmiotów (config/rynek-analiza-history.json). Źródła punktów:
 *  - SRC_ACTIVE: pełna analiza (/ah search, sortowanie po najniższej cenie),
 *  - SRC_PASSIVE_TRUSTED: okno rynku otwarte przez BK, na którym widać prawdziwe minimum (jedna strona albo strona 1 posortowana rosnąco),
 *  - SRC_PASSIVE_SAMPLE: oferty widziane w oknach BK przy innym sortowaniu (np. "Najnowsze") - tylko próbka, nie jest minimum rynku.
 * Statystyki i sugestie liczone są tylko z punktów ACTIVE i TRUSTED.
 */
public final class PriceHistory {
    public static final byte SRC_ACTIVE = 0;
    public static final byte SRC_PASSIVE_TRUSTED = 1;
    public static final byte SRC_PASSIVE_SAMPLE = 2;

    public static boolean ENABLED = true;
    public static int PASSIVE_INTERVAL_SEC = 60;
    public static int MAX_POINTS = 1000;
    private static final long SAME_VALUE_MIN_GAP_MS = 10 * 60 * 1000L;

    private static final Gson GSON = new Gson();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rynek-analiza-history-io");
        t.setDaemon(true);
        return t;
    });

    /** Jeden pomiar: czas, najtańsza oferta za sztukę, średnia 3 najtańszych, liczba ofert, źródło. */
    public static final class Point {
        public long t;
        public double p1;
        public double a3;
        public int n;
        public byte s;

        public Point() {}

        public Point(long t, double p1, double a3, int n, byte s) {
            this.t = t;
            this.p1 = p1;
            this.a3 = a3;
            this.n = n;
            this.s = s;
        }

        public boolean trusted() { return s != SRC_PASSIVE_SAMPLE; }
    }

    public static final class Stats {
        public int count;
        public double min, max, mean, std, first, last;
        public long firstT, lastT;
    }

    private final Map<String, List<Point>> data = new LinkedHashMap<>();
    private final Map<String, Long> lastRecorded = new HashMap<>();
    private boolean dirty;

    public PriceHistory() {}

    // ---------- zapis pomiarów ----------
    public void record(String key, long t, double p1, double a3, int n, byte src) {
        if (!ENABLED || key == null || key.isEmpty() || !(p1 > 0) || !(a3 > 0)) return;
        List<Point> l = data.computeIfAbsent(key, k -> new ArrayList<>());
        String lk = key + "#" + src;
        Long last = lastRecorded.get(lk);
        if (src != SRC_ACTIVE && last != null && t - last < PASSIVE_INTERVAL_SEC * 1000L) return;
        // ta sama wartość co ostatni punkt tego samego źródła w krótkim czasie - nie dublujemy
        for (int i = l.size() - 1; i >= 0; i--) {
            Point q = l.get(i);
            if (q.s != src) continue;
            if (Math.abs(q.p1 - p1) < 0.005 && Math.abs(q.a3 - a3) < 0.005 && t - q.t < SAME_VALUE_MIN_GAP_MS) return;
            break;
        }
        lastRecorded.put(lk, t);
        l.add(new Point(t, p1, a3, n, src));
        dirty = true;
        thin(l);
    }

    /** Gdy punktów jest za dużo, starsza połowa jest przerzedzana (co drugi punkt), nowsza zostaje w pełnej rozdzielczości. */
    private static void thin(List<Point> l) {
        while (l.size() > MAX_POINTS) {
            int half = l.size() / 2;
            List<Point> out = new ArrayList<>();
            for (int i = 0; i < half; i++) if (i % 2 == 0) out.add(l.get(i));
            out.addAll(l.subList(half, l.size()));
            l.clear();
            l.addAll(out);
        }
    }

    // ---------- odczyt ----------
    public int count(String key) {
        List<Point> l = data.get(key);
        return l == null ? 0 : l.size();
    }

    /** Punkty z ostatnich rangeMs milisekund (rangeMs <= 0 = wszystkie). */
    public List<Point> get(String key, long rangeMs, long now) {
        List<Point> l = data.get(key);
        if (l == null) return List.of();
        long from = rangeMs <= 0 ? Long.MIN_VALUE : now - rangeMs;
        List<Point> out = new ArrayList<>();
        for (Point p : l) if (p.t >= from) out.add(p);
        return out;
    }

    /** Statystyki średniej 3 najtańszych (a3) z punktów zaufanych. */
    public static Stats stats(List<Point> pts) {
        Stats s = new Stats();
        double sum = 0;
        boolean first = true;
        for (Point p : pts) {
            if (!p.trusted()) continue;
            s.count++;
            sum += p.a3;
            if (first) { s.min = s.max = s.first = p.a3; s.firstT = p.t; first = false; }
            s.min = Math.min(s.min, p.a3);
            s.max = Math.max(s.max, p.a3);
            s.last = p.a3;
            s.lastT = p.t;
        }
        if (s.count == 0) return s;
        s.mean = sum / s.count;
        double var = 0;
        for (Point p : pts) if (p.trusted()) var += (p.a3 - s.mean) * (p.a3 - s.mean);
        s.std = Math.sqrt(var / s.count);
        return s;
    }

    /**
     * Sugestia marży z historii = rozrzut cen (odchylenie standardowe średniej 3 najtańszych) w wybranym okresie.
     * NaN, gdy zaufanych pomiarów jest mniej niż 6. Zaokrąglenie: do 10 od 100 $ wzwyż, w pozostałych przypadkach do pełnych $.
     */
    public static double suggestMargin(Stats st) {
        if (st.count < 6) return Double.NaN;
        double v = st.std;
        return v >= 100 ? Math.round(v / 10.0) * 10.0 : Math.round(v);
    }

    // ---------- plik ----------
    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("rynek-analiza-history.json");
    }

    public static PriceHistory load() {
        PriceHistory h = new PriceHistory();
        try {
            Path f = file();
            if (Files.exists(f)) {
                Type type = new TypeToken<LinkedHashMap<String, ArrayList<Point>>>() {}.getType();
                Map<String, ArrayList<Point>> m = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), type);
                if (m != null) for (Map.Entry<String, ArrayList<Point>> e : m.entrySet()) h.data.put(e.getKey(), e.getValue());
            }
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się wczytać historii cen: " + e);
        }
        return h;
    }

    /** Zapis synchroniczny (przy zamykaniu gry, gdy wątek w tle mógłby nie zdążyć). */
    public void saveBlocking() {
        if (!dirty) return;
        dirty = false;
        try {
            Path f = file();
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się zapisać historii cen: " + e);
        }
    }

    /** Zapis w tle (JSON budowany od razu, plik zapisywany w osobnym wątku). Nic nie robi, gdy nie ma zmian. */
    public void save() {
        if (!dirty) return;
        dirty = false;
        final String json = GSON.toJson(data);
        IO.execute(() -> {
            try {
                Path f = file();
                Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                System.err.println("[rynek-analiza] nie udało się zapisać historii cen: " + e);
            }
        });
    }
}
