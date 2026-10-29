# SMBCommander

File manager Android per il NAS di casa, veloce e pulito, che parla direttamente SMB.

## Perché esiste

Le app ufficiali per gestire i file del NAS da telefono (nel mio caso Qfile Pro e QuMagie su un NAS QNAP) funzionano,
ma hanno problemi che nell'uso di tutti i giorni pesano:

- **Lente**: ogni cartella e ogni miniatura passano dalle API web del NAS, anche quando telefono e NAS sono sulla
  stessa rete di casa.
- **Interfaccia poco curata e a scatti**: scorrere una cartella con molti file o molte foto non è fluido.
- **Troppe impostazioni inutili**: funzioni pensate per l'accesso remoto e il cloud che, usando il NAS solo in casa,
  non servono e complicano l'app.
- **Modificare un file è scomodo**: aprire un documento, cambiarlo e rimetterlo sul NAS richiede troppi passaggi.

## Cosa fa SMBCommander

- Si collega al NAS **solo in rete locale** tramite **SMB 2/3**, il protocollo più veloce in casa. Niente cloud,
  niente relay, niente porte aperte su Internet.
- Mostra **solo le cartelle che scegli tu**: le aggiungi a mano per nome, comprese le cartelle condivise nascoste,
  e quelle che non ti interessa gestire dal telefono restano fuori.
- Interfaccia moderna (Jetpack Compose), fluida anche con migliaia di file.
- Genera le **miniature delle foto una volta sola** e le tiene in cache sul telefono.
- **Visualizzatore di foto** a schermo intero con zoom e scorrimento: mostra un'anteprima leggera (1600 px, JPEG
  compresso) salvata in cache, così la seconda volta si apre all'istante. L'originale si apre o si salva solo se
  serve.
- **Modifica sicura dei file**: il file viene scaricato in una cache temporanea e aperto con l'app che preferisci.
  Quando torni in SMBCommander, se il file è cambiato viene ricaricato sul NAS. Prima di sovrascrivere controlla che
  nessun altro l'abbia modificato nel frattempo, e il caricamento passa da un file temporaneo, così un'interruzione
  non rovina mai l'originale.
- Operazioni di base: carica dal telefono, scarica sul telefono, nuova cartella, rinomina, elimina.

Funziona con qualunque NAS o computer che condivida cartelle via SMB, non solo QNAP: non usa API proprietarie.

## In arrivo

- **Backup automatico delle foto** dalla fotocamera a una cartella del NAS, in background, quando il telefono è sul
  Wi-Fi di casa.

## Privacy e sicurezza

- Indirizzo del NAS, utente e password si inseriscono nell'app e restano **cifrati sul telefono** (Android Keystore).
  Nel codice non c'è nessun dato personale.
- L'app non invia dati a nessun servizio esterno.

## Scaricare l'app

Ogni push su `main` compila l'APK di prova con GitHub Actions: lo trovi negli artifact della build
(`smbcommander-debug-apk`) nella scheda **Actions**.

## Sviluppo

- Kotlin, Jetpack Compose, Material 3, stile "Arancio UI" (tema scuro caldo, accento arancio, IBM Plex Sans).
- SMB tramite [smbj](https://github.com/hierynomus/smbj), elenco delle condivisioni tramite
  [smbj-rpc](https://github.com/rapid7/smbj-rpc).
- Modulo `core`: logica SMB e di sincronizzazione in Kotlin puro, con test (`./gradlew :core:test`).
- Modulo `app`: interfaccia Android.

Il font IBM Plex Sans è distribuito con licenza SIL Open Font License 1.1 (`fonts/OFL.txt`).
