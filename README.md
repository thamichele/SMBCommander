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

## Backup automatico delle foto

- Sceglie gli album del telefono da salvare (di default la fotocamera) e una delle tue cartelle sul NAS.
- Parte da solo ogni ora, **solo sul Wi-Fi di casa** e con batteria sufficiente (facoltativo: solo in carica),
  e quando apri l'app. Si può anche avviare a mano.
- Divide le foto per anno e mese (`2026/10`), mantiene la posizione GPS delle foto, video facoltativi.
- Ogni file passa da un nome temporaneo: un backup interrotto riprende da dove si era fermato e non lascia file a
  metà. Le foto **non vengono mai cancellate dal telefono**.
- Se reinstalli l'app e scegli la stessa cartella, le foto già sul NAS vengono riconosciute e non ricaricate: a parità
  di nome e dimensione l'app confronta l'impronta (SHA-256) dei due file. Una foto diversa con lo stesso nome viene
  salvata come `nome_2.jpg`.

## Galleria foto

La sezione **Galleria** mostra solo le foto della cartella in cui finisce il backup, e nient'altro: niente
indicizzazione dell'intero NAS, niente album automatici o riconoscimento volti. Le foto sono divise per mese, dalla più
recente, con le stesse miniature e lo stesso visualizzatore veloce della sezione File.
Tieni premuta una foto per selezionarne una o più ed eliminarle dal NAS (anche dal visualizzatore). Sul telefono non
cambia nulla e il backup non le ricarica.

## Privacy e sicurezza

- Indirizzo del NAS, utente e password si inseriscono nell'app e restano solo sul telefono, nella memoria privata
  dell'app; la password è **cifrata** con una chiave dell'Android Keystore. Non finiscono nei backup né nel
  trasferimento a un nuovo telefono, e la password salvata non si può più rileggere dalle impostazioni.
  Nel codice non c'è nessun dato personale.
- Il traffico SMB è sempre **firmato** (nessuno in rete può alterare i file in transito) e, con SMB 3, **cifrato**.
- Al primo collegamento l'app memorizza l'identità del NAS: se a quell'indirizzo risponde un'altra macchina (per
  esempio su un'altra rete Wi-Fi), si ferma prima di inviare il login.
- "Scollega e dimentica questo NAS" cancella anche le copie aperte in modifica, le miniature e le anteprime.
- L'app non invia dati a nessun servizio esterno.

## Scaricare l'app

### Con F-Droid (consigliato, aggiornamenti automatici)

SMBCommander ha un suo repository F-Droid, aggiornato a ogni nuova versione:

1. Apri F-Droid (o un client compatibile come Droid-ify o Neo Store) e vai su **Impostazioni → Repository**.
2. Aggiungi il repository `https://raw.githubusercontent.com/thamichele/SMBCommander/fdroid/repo` e, quando F-Droid
   lo chiede, controlla che l'impronta (fingerprint) della chiave sia
   `46FF8711DA0635E81A46ED3A71A6BC8EC19CF8D7F7766829B8F8AD090F539065`. Puoi anche aggiungerlo già verificato con
   `https://raw.githubusercontent.com/thamichele/SMBCommander/fdroid/repo?fingerprint=46FF8711DA0635E81A46ED3A71A6BC8EC19CF8D7F7766829B8F8AD090F539065`.
3. Cerca **SMBCommander** e installala. Gli aggiornamenti arrivano da F-Droid come per le altre app.

### APK diretto

Ogni versione è anche una [release su GitHub](https://github.com/thamichele/SMBCommander/releases) con l'APK firmato.
L'APK di prova di ogni build si trova negli artifact (`smbcommander-debug-apk`) nella scheda **Actions**: è firmato con
una chiave di debug pubblica e si installa come app separata (`io.github.thamichele.smbcommander.debug`), accanto a
quella vera e senza accesso ai suoi dati. Per l'uso quotidiano installa la versione F-Droid o la release firmata.

Le versioni sono numerate `1.0.N`, dove N è il numero della build: ogni build di `main` è un aggiornamento.

## Sviluppo

- Kotlin, Jetpack Compose, Material 3, stile "Arancio UI" (tema scuro caldo, accento arancio, IBM Plex Sans).
- SMB tramite [smbj](https://github.com/hierynomus/smbj), elenco delle condivisioni tramite
  [smbj-rpc](https://github.com/rapid7/smbj-rpc).
- Modulo `core`: logica SMB e di sincronizzazione in Kotlin puro, con test (`./gradlew :core:test`).
- Modulo `app`: interfaccia Android.
- Rilascio: a ogni push su `main` GitHub Actions compila l'APK firmato, crea la release e aggiorna il repository
  F-Droid (ramo `fdroid`). La chiave di firma non è nel repository: arriva dai secret `RELEASE_KEYSTORE_BASE64` e
  `RELEASE_KEYSTORE_PASSWORD`.

Il font IBM Plex Sans è distribuito con licenza SIL Open Font License 1.1 (`fonts/OFL.txt`).
