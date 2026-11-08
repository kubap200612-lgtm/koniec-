# Rynek Analiza - dodatek do BK-Rynek 3.0 (Minecraft 1.21.4, Fabric)

Osobny mod, który liczy ceny skupu i wpisuje je prosto w listę przedmiotów BK-Rynek.
Ostatnie zmiany były kompilowane tylko na atrapach API (prawdziwy build zrobi dopiero GitHub Actions), a mod nie był uruchomiony w grze.

## Jak zbudować
1. Repo z plikami budowania (`build.gradle`, `gradle.properties`, `settings.gradle`, `.github/workflows/build.yml`)
   i folderem `src/` z tej paczki. Build leci w zakładce Actions, jar jest w artefakcie "rynek-analiza".
2. Jar wrzuć do `mods/` razem z BK-Rynek.

## Panel (GUI)
- Klawisz **prawy Shift** (domyślnie) otwiera/zamyka panel. Klawisz **F8** (domyślnie) = start/stop analizy.
  Oba klawisze zmienisz w panelu, w zakładce **Ustawienia** (kliknij przycisk i naciśnij klawisz; Delete/Backspace = brak, Esc = anuluj),
  albo w Sterowaniu Minecrafta (kategoria "Rynek Analiza").
- Zakładka **Przedmioty**: lista przedmiotów z aktywnego profilu BK (wyszukiwarka, strony), dla każdego:
  aktualna cena skupu, pole **Marża ($)** (puste = marża domyślna), **Sugestia** z ostatniej analizy i przycisk **Użyj**
  (wpisuje sugestię do pola marży). Przyciski **Start analizy** (z wpisaną frazą wyszukiwania analizuje tylko pasujące) i **Stop**.
- Zakładka **Wykresy**: wykres historii cen wybranego przedmiotu (strzałki < > zmieniają przedmiot, przyciski 1 h / 6 h / 24 h / 7 dni / Wszystko zmieniają zakres,
  najechanie myszą pokazuje wartości punktu), statystyki, linia **Profil** i linia **Podgląd (nic nie kupuje)** z oceną BUY / WATCH / REJECT
  (opis niżej) oraz przycisk "Użyj sugestii z historii jako marży".
- Zakładka **Ustawienia**: klawisze, szybkość analizy (Bezpieczna / Szybka / Maksymalna), domyślna marża, podatek (0.97), mnożnik sugestii,
  pauza na ostatniej stronie, podgląd na rynku, wznawianie BK, ścisłe sprawdzanie komponentów,
  historia cen (zapis, obserwacja rynku podczas pracy BK, odstęp zapisów). Ustawienia są na kilku stronach (przyciski < >).
- Podczas analizy panel nie otwiera się (komunikat w czacie) - zatrzymaj analizę klawiszem start/stop.
- Marże i sugestie są zapisywane w `config/rynek-analiza.json`.

## Wzory
- **Cena skupu** (wpisywana do BK jako `maxPrice`) = `floor( średnia 3 najtańszych ofert za sztukę * 0.97 - marża )`.
- **Sugerowana marża** (z 5 najtańszych ofert) = `(cena najdroższej z 5 - cena najtańszej) / 4` = typowy krok cenowy między
  kolejnymi ofertami, razy mnożnik z ustawień; zaokrąglenie do 10 (od 100 $ wzwyż) lub do pełnych $. Gdy ofert jest 3-4, rozstęp dzielony jest przez (liczba ofert - 1);
  poniżej 3 ofert brak sugestii.
  To moje założenie, nie wzór z BK - jeśli wolisz inny, wystarczy zmienić `computeSuggestedMargin` w `MarketAnalyzer.java`.

## Co dzieje się podczas analizy
BK-Rynek jest zatrzymywany (i wznawiany po analizie, jeśli działał). Dla każdego przedmiotu:
`/ah search <nazwa>` -> PPM w repeater dwa razy ("Najniższej ceny") -> skan -> cena za sztukę z każdej pasującej oferty ->
cena skupu i sugestia marży -> `maxPrice` wpisu w BK -> na końcu zapis configu BK i przeładowanie cen.
Mniej niż 3 oferty = cena skupu nie zostaje ustawiona.

Dopasowanie ofert jak w BK: najpierw zgodne id przedmiotu (BK wybiera wpisy po id, np. `enchanted_golden_apple`),
potem `AutoMarketScanner.matchesEntry` (nazwa, enchanty, lore, custom model data...) + zgodna liczba komponentów.
Zwykłe złote jabłko o nazwie "Zaklęte złote jabłko" nie przejdzie.

Zasięg skanu zależy od przedmiotu (na stałe, bez ustawienia): przedmioty niestackowalne (po 1 szt., np. hełmy) - skan kończy się,
gdy znaleziono 5 ofert (3 idą do ceny, 5 do sugestii), bo rynek jest posortowany od najniższej ceny; przedmioty stackowalne (np. złote jabłka) -
przeglądane są wszystkie strony, bo większy stack na dalszej stronie może mieć niższą cenę za sztukę.

## Podgląd na rynku
- Nad rynkiem pasek z ikoną i nazwą analizowanego przedmiotu, numerem w kolejce, stroną, liczbą ofert i trybem skanu.
- Oferty z 3 najtańszych (idą do ceny skupu) mają **żółtą** ramkę, 4. i 5. (tylko do sugestii) **pomarańczową**.
  Ostatnia strona skanu z podświetleniem jest chwilę trzymana (`highlightPauseTicks`, domyślnie 10 ticków = 0,5 s; 0 = bez pauzy).
- Po policzeniu w czacie: "Użyte oferty: str.1 r1 k1 = 950/szt (id "nazwa" xN); ..." oraz sugerowana marża. To ostateczny wybór;
  w trybie pełnego skanu podświetlenie na kolejnych stronach pokazuje stan chwilowy.
- Wyłączenie podglądu: zakładka Ustawienia albo `"showOverlay": false`.

## Komendy (opcjonalnie)
`/analiza start [fragment]`, `/analiza stop`, `/analiza lista`, `/analiza marza <kwota> <fragment>`, `/analiza marza_domyslna <kwota>`.

## Do sprawdzenia przy pierwszym teście
1. Prawy Shift otwiera panel i widać w nim przedmioty z BK (jeśli nie - komunikat błędu w panelu).
2. Wpisanie marży w polu zapisuje się po zamknięciu panelu; po analizie w kolumnie "Sugestia" pojawia się wartość.
3. Fraza po `/ah search` to domyślnie nazwa przedmiotu bez kolorów. Jeśli serwer szuka inaczej, wpisz własną w `search` w configu.
4. Cena z lore: używany jest `lore_regex` z profilu BK, a gdy go brak - pierwsza liczba po słowie "Koszt".
5. BK porównuje cenę za sztukę tylko przy włączonym trybie "Unit" w profilu.
6. Zakładka Wykresy: do czasu, aż będzie min. 8 zaufanych pomiarów z min. 2 godzin, linia Podgląd pokazuje "WATCH - za mało danych". To normalne.
7. Czy BK otwiera rynek z sortowaniem "Najnowsze" - wtedy większość pomiarów z obserwacji to zielone próbki (nie liczą się do profilu),
   a profil opiera się głównie na pomiarach z analizy (Start / F8). Uruchamiaj ją co jakiś czas.

## Szybkość analizy
Zamiast stałych opóźnień bot czeka na faktyczną odpowiedź serwera: po każdym kliknięciu obserwuje okno rynku i idzie dalej,
gdy dane przestały się zmieniać (kilka ticków). Dodatkowo: nie klika sortowania, jeśli "Najniższej ceny" jest już ustawione,
pauza z podświetleniem jest tylko raz na przedmiot, a przerwa między przedmiotami jest krótka.
Poziomy (zakładka Ustawienia): Bezpieczna (4 ticki między kliknięciami, 3 ticki spokoju), Szybka - domyślna (2 / 2), Maksymalna (1 / 1).
Jeśli serwer zacznie odrzucać komendy lub klikanie, wybierz niższy poziom. Rzeczywisty czas zależy od pingu i serwera.

## Dopasowanie i cena - jak w BK
- Cena oferty jest czytana funkcją BK (`AutoMarketScanner.extractPrice`, ten sam skaner co w silniku BK): pomija m.in. linie
  "koszt użycia" w lore przedmiotów customowych. Własny odczyt ("Koszt" / `lore_regex`) działa tylko jako zapas.
- Liczba komponentów NIE jest już sprawdzana domyślnie (BK sam jej nie sprawdza w swojej ścieżce dopasowania, więc nasze dodatkowe
  sprawdzenie mogło odrzucać prawidłowe oferty). Włączysz je w Ustawieniach ("Ścisłe sprawdzanie komponentów").
- Gdy dla przedmiotu nie znaleziono min. 3 ofert, w czacie pojawia się linia "Diagnostyka": ile przedmiotów o tym id było w oknie,
  ile odrzuciło dopasowanie BK (z przykładową nazwą), ile liczba komponentów, ile nie miało odczytanej ceny.

## Historia cen i wykresy (etap 1: tylko obserwacja)
Ten etap niczego nie zmienia w cenach skupu ani marżach - tylko zbiera dane i je pokazuje. Plik: `config/rynek-analiza-history.json`
(do ok. 1000 punktów na przedmiot; starsze są przerzedzane). Zapis w tle co kilka minut, przy końcu analizy i przy zamykaniu gry.

Źródła punktów na wykresie:
- **Analiza** (Start / F8): po każdej analizie przedmiotu zapisywany jest pomiar (najtańsza oferta i średnia 3 najtańszych, za sztukę). To najpewniejsze dane.
- **Obserwacja podczas pracy BK** (Ustawienia -> "Obserwuj rynek, gdy działa BK"): gdy BK otworzy okno rynku, mod tylko je czyta (nie klika, nie zatrzymuje BK)
  i zapisuje ceny przedmiotów z listy. Zapis nie częściej niż raz na minutę na przedmiot. Osobny skaner, żeby nie dzielić stanu z BK.
  - **Zaufany pomiar** (niebieska/żółta linia): rynek ma jedną stronę albo to strona 1 posortowana rosnąco po cenie - widać prawdziwe minimum.
  - **Próbka** (zielone kropki): okno przy innym sortowaniu (BK często używa "Najnowsze") - to oferty, które BK akurat widział, nie minimum rynku.
    Próbki nie wchodzą do statystyk ani sugestii.
- Wykres: niebieska linia = najtańsza oferta, żółta = średnia 3 najtańszych, zielone kropki = próbki z okien BK.
- **Sugestia z historii** = rozrzut cen (odchylenie standardowe średniej 3 najtańszych) w wybranym zakresie, min. 6 zaufanych pomiarów.
  To prosta miara zmienności, nie gwarancja zysku. Zastosuj ją ręcznie (przycisk w zakładce Wykresy), nic nie zmienia się samo.
- Żeby mieć wiarygodny wykres, uruchamiaj analizę co jakiś czas (np. co kilka godzin) - pomiary z analizy to główna seria.

## Profil rynku i podgląd decyzji (nadal tylko podgląd)
Pod wykresem są dwie linie liczone z zaufanych pomiarów z wybranego zakresu (próbki z okien BK nie wchodzą):
- **Profil**: *norma* = mediana średniej 3 najtańszych (z niej liczona jest cena skupu), *dno* = 20. percentyl najtańszej oferty,
  *zmienność* = odchylenie standardowe / średnia, *trend* = regresja liniowa średniej 3 najtańszych (spadkowy / stabilny / wzrostowy
  od zmiany o ±3 % w badanym okresie), *ofert* = typowa liczba widzianych ofert.
- **Podgląd (nic nie kupuje)**: ocena najtańszej oferty z ostatniego pomiaru, z uzasadnieniem. Nie jest podłączona ani do kupowania,
  ani do cen skupu w BK - nic się nie zmienia samo.

Reguły, w tej kolejności:
1. **WATCH** - za mało danych: potrzeba min. 8 zaufanych pomiarów rozłożonych na min. 2 godziny; albo ostatni pomiar jest starszy niż 30 min.
2. **REJECT** - zysk po podatku (norma * 0.97 - najtańsza oferta) jest mniejszy niż marża przedmiotu.
3. **WATCH** - trend spadkowy (nie łapiemy spadającego noża).
4. **WATCH** - oferta się nie wyróżnia: jest mniej niż 1 odchylenie standardowe poniżej typowej najtańszej oferty (mediany), czyli mieści się
   w zwykłych wahaniach tego przedmiotu. Dzięki temu przy dużej zmienności albo dużym rozstępie do 3. oferty "tania" oferta nie dostaje BUY.
5. **BUY** - cała reszta.

Ograniczenia, o których trzeba pamiętać:
- Progi (8 pomiarów, 2 h, 30 min, ±3 %, 1 odchylenie) to moje heurystyki. Sprawdziłem tylko, że reguły działają zgodnie z opisem na danych
  syntetycznych; nie wiem, jak sprawdzą się na prawdziwym rynku. Najpierw obserwuj, czy BUY pokrywa się z tym, co sam uznałbyś za okazję.
- Najnowszy pomiar wchodzi też do statystyk, na tle których jest oceniany - to lekko zaniża wykrywanie okazji, więc błąd idzie w stronę ostrożności.
- Płynności (jak szybko przedmiot się sprzedaje) nie da się policzyć z samych cen; potrzebne są własne transakcje, a BK ich nie udostępnia.

Plan dalszych etapów (jeszcze niezrobione):
- Etap 2: półautomatyczne zmiany - propozycja nowej marży / ceny skupu w granicach np. ±20 %, wprowadzana tylko po zatwierdzeniu w panelu
  i dopiero po zebraniu odpowiedniej ilości danych; na początku marża tylko w górę.
- Etap 3: własne transakcje (kupno, wystawienie, sprzedaż) z czatu serwera -> realny zysk i czas sprzedaży per przedmiot,
  limity wydatków i wyłącznik awaryjny. Wymaga przykładowych wiadomości z czatu Anarchii.
