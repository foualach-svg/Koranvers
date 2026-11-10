# KoranVers (Android)

Bij elk ontgrendelen van je gsm verschijnt het **volgende vers** van de Koran: Arabische tekst, vertaling en audio van de gekozen qiraat. Het vers verschijnt vóór je startscherm. De app onthoudt waar je bent. Na 6236 verzen heb je de volledige Koran gelezen en begint hij opnieuw (khatm-teller).

## Functies
- **Qiraat kiezen:** Ḥafṣ ʿan ʿĀṣim (Alafasy, Husary, Minshawi, Abdul Basit, Sudais) of Warsh ʿan Nāfiʿ (Ibrahim Al-Dosary, Yassin Al-Jazaery, Abdul Basit)
- **Vertaling:** Siregar, Leemhuis, Keyzer (NL), Saheeh International (EN), Hamidullah (FR) of geen vertaling
- **Lange verzen** worden opgesplitst (na 15/25/40 woorden, instelbaar). Elk ontgrendelen toont dan het volgende deel.
- **Audio** start automatisch, maar niet als je gsm op stil staat of tijdens een gesprek. Het volgende vers wordt alvast gedownload.
- **Vergrendelscherm:** je kunt het vers ook al tonen als het scherm aangaat, nog vóór je ontgrendelt.
- **Frequentie:** elke keer, of maximaal 1× per 30 sec / 1 min / 5 min / 15 min
- Je kunt naar een soera springen. De tekst wordt één keer gedownload (±3 MB) en werkt daarna offline. De audio wordt gestreamd.

## APK bouwen zonder Android Studio (GitHub)
1. Maak een nieuwe repository op GitHub en upload de inhoud van deze map.
2. Ga naar **Actions → Build APK**. Die start vanzelf of via *Run workflow*.
3. Download na ±5 minuten het artefact **KoranVers-apk** en installeer `app-debug.apk` op je gsm. Sta daarvoor "installeren uit onbekende bronnen" toe.

## Bouwen met Android Studio
Open de map in Android Studio (Ladybug of nieuwer) en druk op **Run**.

## Na de installatie
1. Open KoranVers en wacht tot de tekst gedownload is.
2. Zet **"Toon een vers bij elk ontgrendelen"** aan.
3. Geef de toestemming **"Weergeven over andere apps"**. Zonder die toestemming laat Android de app niet vanzelf openen en krijg je alleen een melding.
4. Sta **meldingen** toe en zet de **batterijoptimalisatie** uit voor KoranVers.
5. **Xiaomi / Redmi / POCO:** ga naar Instellingen → Apps → KoranVers → Andere machtigingen en zet *"Pop-upvensters weergeven tijdens uitvoering op achtergrond"* en *"Weergeven op vergrendelscherm"* aan. **Samsung / Huawei / Oppo:** zet *Autostart* aan als die optie bestaat.

## iPhone
Apple laat **geen enkele app** zichzelf openen bij het ontgrendelen of audio afspelen zonder dat je iets aanraakt. Wat op iOS wel kan:
- een **widget op het vergrendelscherm** die het volgende vers toont
- een tik op die widget opent de app, die meteen de audio afspeelt
- een **Siri-opdracht/Automatisering** (bv. "bij openen van Instagram → toon vers") via de Opdrachten-app

## Bronnen
- Tekst & vertalingen: [alquran.cloud](https://alquran.cloud/api)
- Audio per vers: [everyayah.com](https://everyayah.com). Vermeld deze bron als je de app publiceert.

## Opmerkingen
- De Arabische tekst is de Uthmani-tekst volgens Ḥafṣ. Bij Warsh is de **audio** in Warsh, maar de tekst nog niet. Voor een Warsh-tekst is een aparte bron met omzettingstabel nodig, omdat Warsh de verzen anders telt.
- Bij een lang vers speelt de audio van het hele vers bij het eerste deel. Bij de volgende delen kun je de audio opnieuw starten met de knop.
- Google Play kijkt streng naar apps die "weergeven over andere apps" gebruiken. Voor eigen gebruik of verspreiding via een APK is dat geen probleem.
