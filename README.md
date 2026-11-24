# eBike live

Eigen Android- en Wear OS-app die live snelheid en accu leest uit een Bosch eBike met het smart system, via de openbare Bosch Live Data Interface (LDI v1.0, mei 2026).

- `core`: Bluetooth-verbinding en decoder (gedeeld)
- `phone`: telefoon-app
- `wear`: horloge-app (Galaxy Watch, Wear OS), werkt zelfstandig zonder telefoon

## Hoe het werkt

De fiets is de "baas" over de verbinding. De app zendt een Bluetooth-signaal uit met het LDI-service-UUID (`0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4`). De fiets zoekt daarnaar, maakt verbinding en koppelt. Daarna schrijft de app zich in op `eb21` en krijgt elke wijziging binnen als protobuf-bericht.

Gebaseerd op het werk van [Xunil99/ha-bosch-ebike](https://github.com/Xunil99/ha-bosch-ebike) en [possm/ha-bosch-ebike-pibridge](https://github.com/possm/ha-bosch-ebike-pibridge).

## Eerste keer koppelen

1. Fiets aan, app open, Start.
2. Bosch Flow: fiets, tandwiel, Componenten, Nieuw apparaat toevoegen.
3. De fiets zoekt ongeveer 30 seconden. Kies het toestel (kan als Bluetooth-adres verschijnen).
4. Bevestig op het display van de fiets. Accepteer de koppelvraag op telefoon of horloge.

Daarna maakt de fiets zelf weer verbinding zodra hij aangaat en de app draait.

Telefoon en horloge zijn twee losse accessoires. Wil je beide gebruiken, dan koppel je ze allebei apart.

## Bouwen

GitHub Actions bouwt bij elke push beide APK's (artifact `apks`). Zet eventueel een eigen `debug.keystore` in de hoofdmap, dan kunnen updates over elkaar heen geïnstalleerd worden.
