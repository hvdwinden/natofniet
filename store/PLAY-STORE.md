# Nat of niet? in Google Play

Alles wat je nodig hebt voor de Play Console. De bestanden staan in deze map (`store/`).

| Wat | Bestand |
|---|---|
| App-bundel (uploaden) | `../android/app/build/outputs/bundle/release/app-release.aab` |
| App-icoon 512×512 | `icon-512.png` |
| Feature graphic 1024×500 | `feature-1024x500.png` |
| Telefoon-schermafbeeldingen | `screenshots/1.png` t/m `5.png` (1080×2080) |
| Privacybeleid | `../docs/privacy.html`, online op https://hvdwinden.github.io/natofniet/privacy.html |

## ⚠️ Uploadsleutel – maak een back-up

`android/keystore/upload.jks` en `android/keystore/keystore.properties` (met het wachtwoord) ondertekenen elke update.
Zet ze in je wachtwoordmanager of op een veilige plek buiten deze computer. Kwijt? Dan kan Google de sleutel
resetten (met Play App Signing), maar dat kost tijd.

## Stappen

1. **Ontwikkelaarsaccount** op <https://play.google.com/console/signup> – eenmalig US$ 25, met identiteitscontrole.
   Kies "Persoonlijk" (of "Organisatie" als je een KvK-nummer + D-U-N-S hebt).
2. **App maken**: naam *Nat of niet?*, standaardtaal Nederlands, App, Gratis.
3. **Play App Signing** aanlaten (standaard). Upload de `.aab`.
4. **Gesloten test (verplicht voor nieuwe persoonlijke accounts)**: minimaal **12 testers** moeten
   **14 dagen achter elkaar** ingeschreven zijn voordat je productietoegang kunt aanvragen.
   Maak een Google-groep of e-maillijst met vrienden/familie, zij installeren via de testlink.
5. Vul de onderdelen hieronder in en vraag daarna **productietoegang** aan.

## Store-vermelding

**App-naam** (max 30): `Nat of niet?`

**Korte beschrijving** (max 80):
```
Gaat het regenen? Regen per 5 minuten, radar en een seintje vóór de bui.
```

**Volledige beschrijving** (max 4000):
```
Nat of niet? vertelt je in één oogopslag of je droog blijft.

☔ REGEN OP DE MINUUT
Zie per 5 minuten hoeveel regen er de komende 2 uur valt op jouw plek, of bekijk de komende 24 uur. Sleep over de grafiek om precies te zien wat er om 17:35 gebeurt.

🗺️ RADAR
Bekijk hoe de buien de komende 2 uur over Nederland en België trekken.

🔔 REGENALARM
Krijg een melding voordat het gaat regenen, met hoe hard het wordt en wanneer het weer droog is. Of juist een seintje wanneer de bui voorbij is. Jij kiest hoeveel minuten vooraf.

🌤️ WEER EN 14-DAAGSE
Temperatuur, wind, regenkans per uur en een weekgrafiek voor de komende 14 dagen.

☀️ OCHTENDMELDINGEN
Elke ochtend op jouw tijd: het weerbericht voor vandaag, een UV-waarschuwing en pollenalarm voor bomen, gras of kruiden.

📍 ELKE PLEK
Gebruik je huidige locatie of zoek een andere plaats.

Geen account, geen advertenties, geen tracking.

Gegevens: Buienradar, Open-Meteo, OpenStreetMap, Kleenex pollenradar. Deze app is niet verbonden aan deze partijen.
```

**Categorie**: Weer · **Tags**: Weer, Regen, Radar
**Contact-e-mail**: verplicht (wordt openbaar getoond).
**Privacybeleid-URL**: https://hvdwinden.github.io/natofniet/privacy.html (repo: github.com/hvdwinden/natofniet – bij wijzigingen `docs/privacy.html` aanpassen en pushen).

## App-content (vragenlijsten)

- **Advertenties**: Nee.
- **Toegang tot de app**: Alle functies zonder inloggen.
- **Contentclassificatie (IARC)**: categorie "Hulpprogramma, productiviteit, communicatie of overig"; alle vragen Nee → PEGI 3.
- **Doelgroep**: 18+ (dan gelden de extra regels voor kinderen niet). Niet aantrekkelijk voor kinderen.
- **Nieuws-app**: Nee. **Overheidsapp**: Nee. **Financiële functies**: Geen. **Gezondheid**: Nee.
- **Exacte wekkers**: de app gebruikt `SCHEDULE_EXACT_ALARM` voor de ochtendmelding; geen aparte declaratie nodig
  (`USE_EXACT_ALARM` is verwijderd, dat is voorbehouden aan wekker-apps).
- **Locatie op de achtergrond**: wordt niet gevraagd.

### Gegevensveiligheid (Data safety)

- Verzamelt of deelt de app gegevens? **Ja**
- Versleuteld tijdens verzending? **Ja**
- Kunnen gebruikers verwijdering aanvragen? Niet van toepassing: er wordt niets bewaard buiten de telefoon
  (kies "Nee" en leg dat uit, of de optie dat gegevens niet worden bewaard).

| Gegevenstype | Verzameld | Gedeeld | Tijdelijk verwerkt | Verplicht? | Doel |
|---|---|---|---|---|---|
| Locatie – geschatte locatie | Ja | Ja (weerdiensten) | Ja | Optioneel (je kunt zelf een plaats kiezen) | App-functionaliteit |
| Locatie – precieze locatie | Ja | Ja (weerdiensten) | Ja | Optioneel | App-functionaliteit |

Alle andere typen (persoonlijke info, app-activiteit, apparaat-ID's enz.): niet verzameld.

## Aandachtspunten

- **Buienradar**: de app gebruikt de openbare Buienradar-feeds met bronvermelding. Buienradar staat gebruik
  toe voor niet-commerciële toepassingen met bronvermelding; check hun actuele voorwaarden, en vraag het
  bij twijfel even na bij Buienradar (RTL). Een gratis app zonder advertenties zit het veiligst.
- **Kleenex pollenradar** is geen officiële API. Valt hij weg, dan gebruikt de app automatisch Open-Meteo.
- **Updates**: verhoog `versionCode` in `android/app/build.gradle` en bouw met
  `cd android && JAVA_HOME=/opt/android-studio/jbr ./gradlew bundleRelease`.
- Na publicatie krijgt de Play-versie de handtekening van Google. Een zelf geïnstalleerde test-APK moet je
  dan eerst verwijderen voordat je de Play-versie kunt installeren.
