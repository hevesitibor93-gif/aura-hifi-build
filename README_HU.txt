AURA Hi-Fi – natív Android Studio projekt (forráskód)

ÁLLAPOT: Android Studio projektforrás, nem lefordított APK.

Funkciók:
- Natív Android felület sötét, arany kiemelésű dizájnnal
- Helyi audiofájl kiválasztása és lejátszása
- A készülék által biztosított Android Equalizer és BassBoost használata
- Valós audio-session hullámforma-alapú L/R vizuális szintmérő
- Saját háttérkép kiválasztása
- Lejátszási hangerőszabályzó és EQ alaphelyzet

Korlátok:
- Az Android által elérhető EQ-sávok száma készülékfüggő; ez nem garantált 12 sávos szoftveres DSP.
- Az EQ a saját lejátszó audio sessionjére hat. YouTube/Spotify/játékok teljes rendszerhangját nem vezérli.
- Az extra hangerő 100% fölött digitális torzítást okozhat, és nem növeli a telefon hardveres maximális kimenetét.
- Ez az első forrásverzió, fizikai Vivo V50 5G készüléken még nincs tesztelve.

Fordítás:
1. Telepíts Android Studio-t egy számítógépre.
2. Csomagold ki a ZIP-et.
3. Android Studio > Open > válaszd ki az AURA_HiFi_Native mappát.
4. Várd meg a Gradle szinkronizálást, szükség esetén telepítsd a kért Android SDK 35 platformot.
5. Build > Build Bundle(s) / APK(s) > Build APK(s).
6. A létrejött APK-t a projekt app/build/outputs/apk/debug/app-debug.apk helyen találod.

Csomag: com.aura.hifi | Minimum Android: 8.0 (API 26) | Cél SDK: 35
