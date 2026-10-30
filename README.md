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
- **Visualizzatore di foto e video** a schermo intero con zoom e scorrimento: per le foto mostra una copia con gli
  stessi pixel dell'originale (solo compressa in JPEG) salvata in cache, così la seconda volta si apre all'istante.
  I video si riproducono in streaming dal NAS, senza scaricarli prima. L'originale si apre o si salva solo se serve.
- **Modifica sicura dei file**: il file viene scaricato in una cache temporanea e aperto con l'app che preferisci.
  Quando torni in SMBCommander, se il file è cambiato viene ricaricato sul NAS. Prima di sovrascrivere controlla che
  nessun altro l'abbia modificato nel frattempo, e il caricamento passa da un file temporaneo, così un'interruzione
  non rovina mai l'originale.
- **Info** su ogni file, dal visualizzatore, dalla selezione in Galleria o dal menu di un elemento: percorso sul NAS e
  percorso di rete da copiare, dimensione, date e, per foto e video, risoluzione, data di scatto, fotocamera, durata e
  posizione.
- **Ogni trasferimento è verificato**: caricamenti, copie, spostamenti e download confrontano l'impronta SHA-256 di
  ciò che è partito con quella di ciò che è arrivato. Un file diverso non viene mai tenuto, e uno spostamento elimina
  l'originale solo dopo che la copia è risultata identica.
- Operazioni di base: carica dal telefono, scarica sul telefono, nuova cartella, nuovo file di testo vuoto, rinomina,
  elimina.
- **Copia e sposta** tra cartelle del NAS: tieni premuto per selezionare uno o più elementi, scegli Copia o Sposta,
  apri la cartella di destinazione e premi «Incolla qui». I trasferimenti vanno in una coda con l'avanzamento e
  continuano mentre navighi; quando possibile la copia la fa il NAS stesso, senza passare dal telefono.
- **Salva sul NAS dalle altre app**: dal menu Condividi di qualunque app scegli «Salva sul NAS», poi la cartella.
- **Widget** per la schermata Home: avvia il backup o apre una cartella scelta.

Funziona con qualunque NAS o computer che condivida cartelle via SMB, non solo QNAP: non usa API proprietarie.

## Backup automatico delle foto

- Sceglie gli album del telefono da salvare (di default la fotocamera) e una delle tue cartelle sul NAS.
- Parte da solo ogni ora, **solo sul Wi-Fi di casa** e con batteria sufficiente (facoltativo: solo in carica),
  e quando apri l'app. Si può anche avviare a mano.
- Divide le foto per anno e mese (`2026/10`), mantiene la posizione GPS delle foto, video facoltativi.
- Una notifica discreta dice quante foto restano da salvare, com'è andato l'ultimo backup ed eventuali errori
  (si può disattivare).
- Ogni file passa da un nome temporaneo: un backup interrotto riprende da dove si era fermato e non lascia file a
  metà. Il backup **non cancella mai nulla dal telefono** da solo.
- **Libera spazio**: quando vuoi, l'app cerca le foto del telefono che sono già sul NAS e te le propone da eliminare.
  Ogni foto viene confrontata con la sua copia sul NAS (stessa dimensione e stessa impronta SHA-256): se la copia manca
  o è diversa, la foto resta sul telefono. Android chiede sempre l'ultima conferma (serve Android 11 o successivo).
- Se reinstalli l'app e scegli la stessa cartella, le foto già sul NAS vengono riconosciute e non ricaricate: a parità
  di nome e dimensione l'app confronta l'impronta (SHA-256) dei due file. Una foto diversa con lo stesso nome viene
  salvata come `nome_2.jpg`.

## Galleria foto

La sezione **Galleria** mostra solo le foto e i video della cartella in cui finisce il backup, e nient'altro: niente
indicizzazione dell'intero NAS, niente album automatici o riconoscimento volti. Le foto sono divise per mese, dalla più
recente, con le stesse miniature e lo stesso visualizzatore veloce della sezione File.
Va bene qualunque struttura di sottocartelle, anche quelle vecchie di QuMagie o di altre app: il mese viene dal nome
della cartella (`2026/10`, `2026-10`, `2019-03-14`...), altrimenti dalla data nel nome del file, altrimenti dalla data
del file. Il contenuto della galleria resta salvato sul telefono, così si apre subito; ogni aggiornamento guarda solo
cosa è cambiato sul NAS, e dopo un backup o un caricamento la galleria si aggiorna da sola.
Si può vedere a griglia o a elenco. Per eliminare una foto dal NAS: nell'elenco la fai scorrere verso sinistra, come
nelle app di posta (nelle impostazioni puoi scegliere se chiedere conferma), oppure tieni premuto per selezionarne più
di una, oppure usi il cestino nel visualizzatore. Le foto eliminate finiscono in un elenco nascosto nella cartella del
backup (`.smbcommander-eliminate.txt`, dimensione e impronta SHA-256): se sono ancora sul telefono il backup non le
ricarica, nemmeno dopo aver reinstallato l'app. Se nel frattempo un file non è più sul NAS (eliminato o spostato da
un altro dispositivo) viene saltato, gli altri vanno avanti, e non resta nell'elenco delle eliminate. Quando ne elimini
più di uno, una finestra mostra il file in corso, la barra con quanti ne mancano e l'esito di ognuno (eliminato,
saltato o non eliminato); si può interrompere, e si chiude da sola se è andato tutto bene.

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

### Pulizia rapida

Dal tasto con la scopetta della Galleria: una foto alla volta, come su Tinder. Scorri a sinistra (o ✕) per scartarla, a destra
(o ✓) per tenerla; ↶ annulla l'ultima scelta.

- **Da verificare**: l'app propone per prime le foto che di solito sono da buttare, riconosciute dal nome, dalla
  cartella e dalla dimensione: screenshot e registrazioni dello schermo, immagini di WhatsApp, Telegram e altre app,
  GIF e sticker, immagini minuscole, file scaricati, copie modificate e raffiche di foto scattate a pochi secondi
  l'una dall'altra.
  Le foto normali della fotocamera non finiscono qui. Su ogni carta c'è il motivo per cui è stata proposta.
- **Duplicati**: foto e video con lo stesso nome, anche con un finale diverso (`IMG_1234 (1)`, `IMG_1234_2`,
  `IMG_1234 - Copia`, la versione HEIC accanto alla JPG), nella stessa cartella o in due cartelle diverse, per esempio
  le vecchie cartelle di QuMagie. Le due copie stanno una accanto all'altra con il percorso completo sotto: scegli
  quale tenere e l'altra va nel cestino, oppure le tieni entrambe. Se hanno la stessa dimensione l'app confronta il
  contenuto e ti dice se sono identiche. Con tre o più copie, quella tenuta si confronta con la successiva.
- **Tutte le altre**: il resto, dalla più recente, se vuoi controllare anche quelle.
- Le foto scartate vanno nel **cestino** (`.smbcommander-cestino`, nascosto nella cartella del backup), con lo stesso
  percorso che avevano: lo spostamento è istantaneo, non copia nulla, e si può annullare. Il cestino si svuota o si
  ripristina a mano; lo svuotamento mostra file per file cosa è stato eliminato. Le foto scartate entrano anche in `.smbcommander-eliminate.txt`, così il backup non le ricarica.
- Le foto tenute vanno in `.smbcommander-tenute.txt` (dimensione e percorso), sempre nella cartella del backup:
  non vengono più proposte, anche dopo una reinstallazione, e sono riconosciute anche se le sposti in un'altra
  cartella.
- Anche con migliaia di foto la schermata resta leggera: la lista viene dall'indice della galleria già salvato sul
  telefono, si disegnano solo due carte e si scaricano in anticipo solo le prossime anteprime.

