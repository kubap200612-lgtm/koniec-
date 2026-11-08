package pl.rynekanaliza;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Profil rynkowy przedmiotu wyliczony z historii cen (tylko zaufane pomiary) oraz decyzja BUY / WATCH / REJECT w trybie podglądu.
 * Nic tu niczego nie kupuje ani nie zmienia cen - to prosta, jawna statystyka (mediana, percentyl, odchylenie, regresja liniowa),
 * a każda decyzja ma uzasadnienie. Wartości progowe poniżej to heurystyki do sprawdzenia na prawdziwych danych, nie wyniki testów na rynku.
 *
 * Najnowszy pomiar wchodzi też do statystyk, na tle których jest oceniany. To lekko zaniża wykrywanie okazji, czyli błąd
 * idzie w bezpieczną stronę (mniej BUY).
 */
public final class MarketProfile {
    public static final int MIN_POINTS = 8;            // tyle zaufanych pomiarów trzeba, by w ogóle oceniać
    public static final double MIN_SPAN_H = 2.0;       // i tyle godzin musi obejmować historia (norma z kilku minut to nie norma)
    public static final long MAX_AGE_MIN = 30;         // starszy ostatni pomiar = dane nieaktualne, nie oceniamy
    public static final double TREND_LIMIT_PCT = 3.0;  // zmiana ceny w okresie (regresja), od której mówimy o trendzie
    public static final double MIN_Z = 1.0;            // oferta musi być co najmniej tyle odchyleń standardowych poniżej typowej najtańszej

    public enum Trend { RISING, FALLING, STABLE }
    public enum Verdict { BUY, WATCH, REJECT }

    public static final class Decision {
        public Verdict verdict;
        public String reason;
        public double profit;       // normalna cena * podatek - najtańsza oferta (bez marży)
        public double advantagePct; // o ile % najtańsza oferta jest poniżej typowej najtańszej
        public double z;            // odległość od typowej najtańszej w odchyleniach standardowych jej rozkładu
    }

    public int points;           // zaufane pomiary w badanym okresie
    public boolean enough;       // czy jest ich dość (liczba i rozpiętość czasu), by oceniać
    public double spanHours;     // od pierwszego do ostatniego zaufanego pomiaru
    public double normal;        // mediana średniej 3 najtańszych (to z niej BK liczy cenę skupu)
    public double floor;         // 20. percentyl najtańszej oferty ("dno")
    public double sigma;         // odchylenie standardowe średniej 3 najtańszych
    public double volatilityPct; // sigma / średnia * 100
    public double cheapMedian;   // mediana najtańszej oferty (typowa najtańsza)
    public double cheapSigma;    // odchylenie standardowe najtańszej oferty
    public double trendPct;      // zmiana w okresie wg regresji liniowej, w % średniej ceny
    public Trend trend = Trend.STABLE;
    public int depth;            // mediana liczby widzianych ofert
    public double lastCheapest;  // najtańsza oferta z ostatniego zaufanego pomiaru
    public long lastTime;

    public static MarketProfile build(List<PriceHistory.Point> pts) {
        MarketProfile p = new MarketProfile();
        List<PriceHistory.Point> tr = new ArrayList<>();
        for (PriceHistory.Point q : pts) if (q.trusted() && q.p1 > 0 && q.a3 > 0) tr.add(q);
        tr.sort(Comparator.comparingLong(q -> q.t));
        int n = tr.size();
        p.points = n;
        if (n == 0) return p;

        double[] a3 = new double[n], p1 = new double[n], cnt = new double[n];
        for (int i = 0; i < n; i++) {
            PriceHistory.Point q = tr.get(i);
            a3[i] = q.a3;
            p1[i] = q.p1;
            cnt[i] = q.n;
        }
        double mean = mean(a3);
        p.sigma = stdDev(a3, mean);
        p.volatilityPct = mean > 0 ? p.sigma / mean * 100 : 0;
        p.normal = median(a3);
        p.floor = percentile(p1, 0.20);
        p.cheapMedian = median(p1);
        p.cheapSigma = stdDev(p1, mean(p1));
        p.depth = (int) Math.round(median(cnt));

        // trend: regresja liniowa średniej 3 najtańszych po czasie (w godzinach)
        long t0 = tr.get(0).t;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            double x = (tr.get(i).t - t0) / 3_600_000.0;
            sx += x;
            sy += a3[i];
            sxx += x * x;
            sxy += x * a3[i];
        }
        p.spanHours = (tr.get(n - 1).t - t0) / 3_600_000.0;
        double denom = n * sxx - sx * sx;
        if (n >= 2 && denom > 1e-12 && mean > 0) {
            double slope = (n * sxy - sx * sy) / denom;
            p.trendPct = slope * p.spanHours / mean * 100;
        }
        p.trend = p.trendPct <= -TREND_LIMIT_PCT ? Trend.FALLING : p.trendPct >= TREND_LIMIT_PCT ? Trend.RISING : Trend.STABLE;

        p.lastCheapest = tr.get(n - 1).p1;
        p.lastTime = tr.get(n - 1).t;
        p.enough = n >= MIN_POINTS && p.spanHours >= MIN_SPAN_H;
        return p;
    }

    /**
     * Ocena najtańszej oferty z ostatniego pomiaru (tryb podglądu, nic nie kupuje). Kolejność reguł:
     * WATCH  - za mało danych albo ostatni pomiar jest za stary,
     * REJECT - po podatku zysk z odsprzedaży po normalnej cenie nie pokrywa marży (to ta sama zasada, co w cenie skupu BK),
     * WATCH  - cena spada (nie łapiemy spadającego noża) albo oferta nie wyróżnia się na tle zwykłej zmienności najtańszych ofert,
     * BUY    - reszta: zysk po podatku >= marży, trend nie jest spadkowy, a oferta jest co najmniej MIN_Z odchyleń poniżej typowej najtańszej.
     */
    public Decision decide(double margin, double taxFactor, long nowMs) {
        Decision d = new Decision();
        if (!enough) {
            d.verdict = Verdict.WATCH;
            d.reason = String.format(Locale.ROOT, "za mało danych (%d/%d pomiarów, %.1f/%.0f h)", points, MIN_POINTS, spanHours, MIN_SPAN_H);
            return d;
        }
        long ageMin = Math.max(0L, (nowMs - lastTime) / 60_000L);
        if (ageMin > MAX_AGE_MIN) {
            d.verdict = Verdict.WATCH;
            d.reason = "ostatni pomiar sprzed " + ageMin + " min - za stary";
            return d;
        }
        d.profit = normal * taxFactor - lastCheapest;
        d.advantagePct = cheapMedian > 0 ? (cheapMedian - lastCheapest) / cheapMedian * 100 : 0;
        d.z = cheapSigma > 0 ? (cheapMedian - lastCheapest) / cheapSigma : (cheapMedian > lastCheapest ? Double.POSITIVE_INFINITY : 0);
        if (d.profit < margin) {
            d.verdict = Verdict.REJECT;
            d.reason = "zysk po podatku " + money(d.profit) + "$ < marża " + money(margin) + "$";
        } else if (trend == Trend.FALLING) {
            d.verdict = Verdict.WATCH;
            d.reason = String.format(Locale.ROOT, "cena spada (%+.1f%% w okresie) - poczekaj", trendPct);
        } else if (d.z < MIN_Z) {
            d.verdict = Verdict.WATCH;
            d.reason = String.format(Locale.ROOT, "oferta nie wyróżnia się (%.1f odch. std. poniżej typowej, próg %.1f)", d.z, MIN_Z);
        } else {
            d.verdict = Verdict.BUY;
            d.reason = String.format(Locale.ROOT, "zysk %s$ po podatku, %.1f%% taniej niż zwykle, trend %s",
                    money(d.profit), d.advantagePct, trendName());
        }
        return d;
    }

    public String trendName() {
        return trend == Trend.FALLING ? "spadkowy" : trend == Trend.RISING ? "wzrostowy" : "stabilny";
    }

    /** Kwota do tekstu: od 100 w górę bez ułamków. */
    static String money(double v) {
        return Math.abs(v) >= 100 ? fmt(Math.round(v)) : fmt(v);
    }

    static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "?";
        if (v == Math.rint(v)) return String.valueOf((long) v);
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) s += x;
        return v.length == 0 ? 0 : s / v.length;
    }

    private static double stdDev(double[] v, double mean) {
        double var = 0;
        for (double x : v) var += (x - mean) * (x - mean);
        return v.length == 0 ? 0 : Math.sqrt(var / v.length);
    }

    private static double median(double[] v) {
        double[] c = v.clone();
        Arrays.sort(c);
        int n = c.length;
        return n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2.0;
    }

    private static double percentile(double[] v, double q) {
        double[] c = v.clone();
        Arrays.sort(c);
        return c[(int) Math.floor(q * (c.length - 1))];
    }
}
