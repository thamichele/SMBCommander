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
  I video si riproducono in streaming dal NAS, senza scaricarli prima, direttamente a schermo intero: basta toccare
  il video in un punto qualsiasi. I comandi stanno in una barra sotto il video, non sopra: da sinistra Riproduci/Pausa,
  indietro e avanti di 10 secondi, poi la linea del tempo, l'adattamento (Adatta, Ritaglia, Allunga, 16:9, 4:3) e lo
  schermo intero. Sul video compare solo il pulsante Riproduci, e solo quando è fermo. Doppio tocco a sinistra o a
  destra per tornare indietro o andare avanti di 10 secondi, al centro per mettere in pausa. Si passa da un video
  all'altro scorrendo anche durante la riproduzione, e il successivo parte da solo. Con l'opzione «Avvia subito i
  video» (Impostazioni › Funzioni facoltative) i video partono appena aperti. L'originale si apre o si salva solo se
  serve.
- **Modifica sicura dei file**: il file viene scaricato in una cache temporanea e aperto con l'app che preferisci.
  Quando torni in SMBCommander, se il file è cambiato viene ricaricato sul NAS. Prima di sovrascrivere controlla che
  nessun altro l'abbia modificato nel frattempo, e il caricamento passa da un file temporaneo, così un'interruzione
  non rovina mai l'originale.
- **Musica**: tocca un brano e parte in streaming dal NAS, seguito dagli altri brani della cartella. Un piccolo lettore
  resta in basso su ogni schermata (toccalo per la barra di avanzamento), e la musica continua con lo schermo spento,
  con i comandi nella notifica, sulla schermata di blocco e dalle cuffie; staccando le cuffie si mette in pausa.
- **Anteprime di PDF e copertine**: negli elenchi i PDF (fino a 30 MB) mostrano la prima pagina e le canzoni la
  copertina dell'album salvata nel file, con lo stesso sistema di cache delle foto.
- **Archivi ZIP**: un file `.zip` sul NAS si apre come una cartella senza scaricarlo (viaggia solo l'indice). Un file
  dell'archivio si apre con un'altra app estraendo solo quello; «Estrai nella cartella del NAS» mette sul NAS un file,
  una cartella o tutto l'archivio (in una cartella con il suo nome), con le solite verifiche e la coda dei
  trasferimenti.
- **Info** su ogni file, dal visualizzatore, dalla selezione in Galleria o dal menu di un elemento: percorso sul NAS e
  percorso di rete da copiare, dimensione, date e, per foto e video, risoluzione, data di scatto, fotocamera, durata e
  posizione.
- **Ogni trasferimento è verificato**: caricamenti, copie, spostamenti e download confrontano l'impronta SHA-256 di
  ciò che è partito con quella di ciò che è arrivato. Un file diverso non viene mai tenuto, e uno spostamento elimina
  l'originale solo dopo che la copia è risultata identica.
- Operazioni di base: carica dal telefono (file singoli o un'intera cartella con le sue sottocartelle), scarica sul
  telefono, nuova cartella, nuovo file di testo vuoto, rinomina, elimina.
- **Cestino**: ciò che elimini dalle cartelle non sparisce subito, va in un cestino nascosto in cima alla cartella
  condivisa (`.smbcommander-cestino-file`, con uno spostamento istantaneo che non copia nulla). Subito dopo puoi
  premere «Annulla»; dalla schermata **Cestino** (in «Le mie cartelle» o dal menu di una cartella) ogni elemento si
  ripristina esattamente dov'era o si elimina definitivamente. Dopo 30 giorni se ne va da solo.
- **Ricerca anche nelle sottocartelle**: durante la ricerca scegli «Anche nelle sottocartelle» e l'app cerca il nome
  a ogni livello sotto la cartella aperta, mostrando i risultati man mano con la cartella in cui si trovano.
- **Spazio e dimensioni**: le Info di una cartella (anche di quella aperta, dal menu) contano file, sottocartelle e
  dimensione totale a ogni livello, e mostrano quanto spazio resta sul disco del NAS.
- **Copia e sposta** tra cartelle del NAS: tieni premuto per selezionare uno o più elementi, scegli Copia o Sposta,
  apri la cartella di destinazione e premi «Incolla qui». I trasferimenti vanno in una coda con l'avanzamento e
  continuano mentre navighi; quando possibile la copia la fa il NAS stesso, senza passare dal telefono.
- **Trasferimenti affidabili**: copie, spostamenti e caricamenti vanno avanti anche con l'app chiusa o lo schermo
  spento, con una notifica che mostra l'avanzamento e i pulsanti Pausa e Annulla. Si possono mettere in pausa e
  riprendere; se il Wi-Fi o il NAS spariscono per un momento l'app riprova da sola, aspettando un po' di più ogni
  volta, e riparte da dove si era fermata senza ricopiare i file già arrivati. Un trasferimento fallito si riprende con
  «Riprova».
- **Nomi già presenti**: se nella cartella di destinazione c'è già un elemento con lo stesso nome, l'app chiede se
  sostituirlo, saltarlo o tenere entrambi (il nuovo prende « (2)»). Le cartelle con lo stesso nome vengono unite e la
  scelta vale per ogni file al loro interno; un file viene sostituito solo dopo che la nuova copia è risultata
  identica all'originale.
- **Salva sul NAS dalle altre app**: dal menu Condividi di qualunque app scegli «Salva sul NAS», poi la cartella.
- **Il NAS nel selettore file di Android**: quando un'altra app chiede di scegliere un file (allegare a una mail,
  aprire un PDF, salvare un documento), nel menu laterale c'è **SMBCommander** con le tue cartelle. I file si leggono
  direttamente dal NAS, senza scaricarli prima; quando un'app salva, la copia passa dalla coda dei trasferimenti con
  le stesse verifiche (e se nel frattempo il file sul NAS era cambiato, viene salvata accanto come «nome (modificato)»).
  Eliminare da lì sposta nel cestino. Si vedono solo le cartelle che hai aggiunto nell'app.
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
Si può vedere a griglia, a mosaico (foto più grandi, ognuna con la sua forma, che si incastrano in righe piene;
le proporzioni si ricordano sul telefono, senza rileggere il NAS) o a elenco. Con il pulsante delle categorie si passa da tutte
le foto a una sola categoria: Persone, Schermate, Scritte, Altre foto o Video, con il numero di foto di ognuna. Le
foto vengono divise sul telefono, dalle miniature, come nella Pulizia rapida (niente internet, nessun modello
scaricato); la divisione parte la prima volta che scegli una categoria e poi continua in sottofondo. La scelta resta
anche alla riapertura dell'app. Per eliminare una foto dal NAS: nell'elenco la fai scorrere verso sinistra, come
nelle app di posta (nelle impostazioni puoi scegliere se chiedere conferma), oppure tieni premuto per selezionarne più
di una, oppure usi il cestino nel visualizzatore. Le foto eliminate finiscono in un elenco nascosto nella cartella del
backup (`.smbcommander-eliminate.txt`, dimensione e impronta SHA-256): se sono ancora sul telefono il backup non le
ricarica, nemmeno dopo aver reinstallato l'app. Se nel frattempo un file non è più sul NAS (eliminato o spostato da
un altro dispositivo) viene saltato, gli altri vanno avanti, e non resta nell'elenco delle eliminate. Quando ne elimini
più di uno, una finestra mostra il file in corso, la barra con quanti ne mancano e l'esito di ognuno (eliminato,
saltato o non eliminato); si può interrompere, e si chiude da sola se è andato tutto bene.

## Funzioni facoltative

In **Impostazioni › Funzioni facoltative**, tutte spente finché non le accendi:

- **Blocca con impronta o blocco schermo**: all'apertura, e quando torni dopo più di mezzo minuto, l'app chiede
  impronta, volto o PIN del telefono. Con il blocco acceso le altre app vedono il NAS nel selettore file solo nei 10
  minuti dopo lo sblocco.
- **Accendi il NAS (Wake-on-LAN)**: con l'indirizzo MAC del NAS, «Accendi il NAS» compare in «Le mie cartelle» e
  quando il NAS non risponde. Il Wake-on-LAN va attivato anche sul NAS.
- **Ritrova il NAS se cambia indirizzo**: se il NAS non risponde, l'app lo cerca nella rete e passa al nuovo
  indirizzo, solo se risponde lo stesso NAS abbinato (riconosciuto prima di inviare la password).

Nelle impostazioni c'è anche «Cerca il NAS nella rete», che elenca i NAS che si presentano sul Wi-Fi (Bonjour) e
riempie l'indirizzo con un tocco.

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

Le versioni sono `MAGGIORE.MINORE.CORREZIONE`: la correzione sale per i piccoli fix, la minore per ogni funzione nuova
o modifica, la maggiore quando l'app raggiunge una nuova maturità. Ogni build di `main` è un aggiornamento (fino alla
1.0.46 l'ultimo numero era quello della build).

## Sviluppo

- Kotlin, Jetpack Compose, Material 3, stile "Arancio UI" (tema scuro caldo, IBM Plex Sans). Il colore principale
  (Arancio, Viola, Rosso, Giallo, Blu o Verde) si sceglie in **Impostazioni → Aspetto** e cambia app, widget e icona.
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
  contenuto: quando sono identiche le due foto hanno il bordo verde e sopra c'è «Sono identiche», quando sono diverse
  il bordo è rosso e c'è «Sono diverse», con il motivo. Con tre o più copie, quella tenuta si confronta con la successiva.
  Finché una foto ha ancora una copia da confrontare compare solo qui, non in **Da verificare** né in **Tutte le
  altre**: ci arriva quando è rimasta singola.
- **Tutte le altre**: il resto, dalla più recente, se vuoi controllare anche quelle. Sono divise in gruppi:
  **Persone**, **Schermate**, **Scritte** (documenti, scontrini, appunti, lavagne), **Altre foto** e **Video**.
  L'app guarda le miniature direttamente sul telefono, in sottofondo, mentre scorri: i volti con il riconoscimento
  facciale integrato in Android, schermate e scritte da forma, colori e righe dell'immagine. Niente va su internet e
  non si scarica nessun modello; ogni foto viene guardata una volta sola. È un riconoscimento approssimativo: qualche
  foto può finire nel gruppo sbagliato, ma in **Tutte** ci sono sempre tutte.
- Le foto scartate vanno nel **cestino** (`.smbcommander-cestino`, nascosto nella cartella del backup), con lo stesso
  percorso che avevano: lo spostamento è istantaneo, non copia nulla, e si può annullare. Il cestino si svuota o si
  ripristina a mano; lo svuotamento mostra file per file cosa è stato eliminato. Le foto scartate entrano anche in `.smbcommander-eliminate.txt`, così il backup non le ricarica.
- Le foto tenute vanno in `.smbcommander-tenute.txt` (dimensione e percorso), sempre nella cartella del backup:
  non vengono più proposte, anche dopo una reinstallazione, e sono riconosciute anche se le sposti in un'altra
  cartella.
- Anche con migliaia di foto la schermata resta leggera: la lista viene dall'indice della galleria già salvato sul
  telefono, si disegnano solo due carte e si scaricano in anticipo solo le prossime anteprime.

