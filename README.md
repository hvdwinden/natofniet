# Nat of niet? ☔

Android-app (en webapp) die in één oogopslag laat zien of je droog blijft.

- **Grafiek**: neerslag per 5 minuten voor de komende 2 uur, of 24 uur vooruit. Sleep over de grafiek voor details.
- **Radar**: buien over Nederland en België, 2 uur vooruit.
- **Regenalarm**: melding vóór de bui (met hoe hard en tot wanneer) of wanneer het droog wordt.
- **Weer**: per uur, 14-daagse weekgrafiek, pollen.
- **Ochtendmeldingen**: dagelijks weerbericht, UV-waarschuwing, pollenalarm.

Geen account, geen advertenties, geen tracking. [Privacybeleid](https://hvdwinden.github.io/natofniet/privacy.html)

## Opbouw

| Map | Inhoud |
|---|---|
| `web/` | De hele interface: één `index.html` (Leaflet, canvas). Werkt ook los in de browser. |
| `android/` | Java-app: WebView met de webapp als asset, plus regenalarm (JobScheduler), ochtendmeldingen (AlarmManager) en pollen. |
| `store/` | Teksten en afbeeldingen voor Google Play. |
| `docs/` | GitHub Pages (privacybeleid). |

## Bouwen

```bash
cd android
./gradlew assembleDebug        # test-APK
./gradlew testDebugUnitTest    # unit tests
./gradlew bundleRelease        # Play-bundel (vereist android/keystore/keystore.properties)
```

Webapp lokaal: `python3 -m http.server 8123 --directory web`

## Gegevensbronnen

[Buienradar](https://www.buienradar.nl) (neerslag en radar), [Open-Meteo](https://open-meteo.com) (weer, UV, pollen-reserve),
[OpenStreetMap](https://www.openstreetmap.org)/Nominatim (kaart en zoeken), Kleenex pollenradar (pollen, onofficieel),
[RainViewer](https://www.rainviewer.com) (reserve-radar). Deze app is niet verbonden aan deze partijen.
