# Changelog

Storia delle versioni di SMBCommander, la più recente in alto. Le versioni fino alla 1.19.0 sono descritte nelle
[release su GitHub](https://github.com/thamichele/SMBCommander/releases).

## 1.27.2 — 2026-11-21
- Corretto: il percorso grigio sopra il titolo di una cartella sta sempre su una riga sola. Se è corto resta sopra
  il titolo; se è lungo prende tutta la larghezza della pagina, e se non basta mostra la fine (le cartelle più
  vicine) e si scorre di lato per leggere l'inizio
- Corretto: una cartella condivisa che si chiama come il suo NAS non viene più scritta due volte nel percorso

## 1.27.1 — 2026-11-21
- Corretto: toccando File, Galleria o Impostazioni nella barra in basso l'animazione non scatta più mentre la
  sezione si apre. La Galleria legge dal telefono l'elenco delle foto senza bloccare lo schermo, la sezione File non
  decifra più la password del NAS a ogni apertura, e la pastiglia parte un attimo dopo il tocco, a sezione disegnata
- Modificato: niente più lampo chiaro quando tocchi un pulsante della barra in basso; resta solo la pastiglia che
  scivola

## 1.27.0 — 2026-11-21
- Nuovo: nella Galleria (anche nella scheda Video) e in File le anteprime dei video sono animate: mostrano a turno
  due o tre momenti del video (dopo 2 secondi, dopo 10, a metà) con una breve dissolvenza. Si animano solo quelle
  sullo schermo; i fotogrammi si leggono dal NAS una volta sola, quando l'anteprima resta ferma per un attimo, e
  restano nella cache delle miniature con un tetto loro di 100 MB. Mentre guardi un video la lettura aspetta
- Nuovo: interruttore «Anteprime animate dei video» in Impostazioni › Opzioni (acceso di partenza)
- Modificato: i video lunghi partono prima. Il lettore legge il NAS a blocchi che crescono da 256 KB fino a 2 MB e
  chiede il blocco seguente prima che serva; l'inizio e l'indice (la parte in fondo agli MP4) degli ultimi video
  aperti restano in memoria, così riaprirli o tornare all'inizio è immediato. Nessun video viene salvato sul
  telefono

## 1.26.0 — 2026-11-21
- Nuovo: più NAS, ognuno con il suo utente e la sua password. Ogni NAS in più compare in File come una cartella
  con il nome che gli dai; dentro ci sono le sue cartelle condivise e il resto funziona come sempre
- Nuovo: copia e sposta funzionano anche da un NAS all'altro, con la stessa verifica SHA-256 di ogni trasferimento;
  spostando, l'originale viene eliminato solo dopo che la copia è risultata identica
- Nuovo: Impostazioni › NAS › Altri NAS per aggiungere, modificare o togliere un NAS; le password restano cifrate
  sul telefono come quella del NAS principale, e ogni NAS viene riconosciuto prima di ricevere la password
- Nuovo: «Aggiungi una cartella» permette di scegliere su quale NAS, anche per le cartelle condivise nascoste, e
  ha il pulsante «Collega un altro NAS»
- Nuovo: Impostazioni › Opzioni › «Mostra NAS e utente sotto le cartelle» scrive in piccolo, sotto
  ogni cartella di File, l'indirizzo del NAS e l'utente con cui l'app vi accede (spenta di default)
- Backup foto e Galleria restano sul NAS principale

## 1.25.0 — 2026-11-21
- Nuovo: le Impostazioni sono divise in menu separati, uno per argomento: «Backup foto» (backup automatico, cartella
  sul NAS, libera spazio, album da salvare), «Opzioni» (interruttori del backup e della Galleria, funzioni
  facoltative), «Aspetto» (colore principale) e «NAS» (indirizzo e utente). Ogni menu apre la sua pagina
- Modificato: il terzo pulsante della barra in basso si chiama «Impostazioni» e ha l'icona dell'ingranaggio; la
  notifica del backup e i pulsanti «Vai al backup foto» aprono direttamente la pagina «Backup foto»
- Nuovo: nella Galleria, tra le schede Foto e Album, c'è la scheda «Video» che mostra solo i video, per data
- Modificato: con tre schede i quattro pulsanti della Galleria stanno accanto al titolo; scorrendo in giù titolo
  e schede spariscono e restano solo i pulsanti
- Modificato: i pulsanti con un'icona si adattano da soli allo spazio: da 56 dp sugli schermi larghi scendono fino
  a 40 dp su schermi stretti, con lo zoom dello schermo alto o con il testo grande, e l'icona resta metà del pulsante
- Corretto: in «Aspetto» i nomi dei colori non vengono più spezzati a metà parola: se tre caselle per riga non
  bastano diventano due, o una

## 1.24.0 — 2026-11-21
- Modificato: mentre scorri le foto della Galleria o i file di una cartella verso il basso l'intestazione si
  rimpicciolisce: il titolo sparisce e resta solo la riga dei pulsanti; torna intera appena scorri in su
- Modificato: intestazioni più basse. Il titolo va a capo accanto ai pulsanti invece di spingerli su una riga sotto;
  succede solo se nemmeno la parola più lunga del titolo ci sta
- Modificato: nella Galleria i quattro pulsanti (categorie, visualizzazione, Pulizia rapida, Aggiorna) restano
  separati e stanno sulla riga delle schede Foto/Album; il titolo ha una riga sua e non viene mai tagliato
- Modificato: barra di navigazione in basso più bassa, con icone più piccole e meno spazio vuoto; la pastiglia
  colorata scivola da una sezione all'altra allungandosi, e l'icona attiva si ingrandisce appena
- Corretto: anche il pulsante delle categorie della Galleria ha l'icona nel colore principale; con una categoria
  scelta il pulsante ha il fondo colorato scuro, per vedere subito che c'è un filtro

## 1.23.0 — 2026-11-21
- Corretto: nessuna scritta viene più tagliata con i puntini, con qualsiasi dimensione dello schermo, zoom o grandezza
  del carattere. I titoli in alto vanno a capo; se titolo e pulsanti non stanno sulla stessa riga, i pulsanti passano
  su una riga loro sotto il titolo, a destra, e vanno a capo anche loro se serve
- Corretto: nomi di file, percorsi, dettagli e messaggi negli elenchi, nei dialoghi e nel mini lettore vanno a capo
  invece di essere tagliati; schede e pulsanti con del testo crescono in altezza quando serve
- Modificato: le icone di tutti i pulsanti hanno il colore principale scelto nelle Impostazioni, sempre su fondo
  scuro (rosso resta solo per eliminare); il pulsante principale (Riproduci) ha il fondo scuro con il bordo colorato
- Modificato: i pulsanti con un'icona si rimpiccioliscono appena quando li premi e, se non disponibili, sono
  sbiaditi al 50%, come gli altri pulsanti del kit Arancio UI

## 1.22.0 — 2026-11-21
- Modificato: tutti i pulsanti con un'icona hanno lo stile e la misura di quelli del lettore video (56 dp, icona di
  28 dp): intestazioni di File, Galleria, Album, Cestino, Pulizia rapida e Archivi, righe degli elenchi e mini
  lettore musicale
- Modificato: nel visualizzatore le azioni sulla foto o sul video (Informazioni, Condividi, Apri l'originale, Salva sul
  telefono, Elimina) stanno in una barra in basso, come quella del video; in alto restano Indietro e il nome del file
- Modificato: con dei file o delle foto selezionati il titolo è solo il numero, per fare posto ai pulsanti più grandi
- Modificato: nel mini lettore musicale Riproduci/Pausa è pieno del colore principale come nel lettore video, e da
  aperto i pulsanti hanno una riga tutta loro
- Modificato: icone della barra di navigazione in basso più grandi

## 1.21.0 — 2026-11-20
- Aggiunto: opzione «Avvia subito i video» in Impostazioni › Funzioni facoltative (spenta finché non la accendi)
- Aggiunto: si scorre da un video all'altro anche durante la riproduzione, e il video successivo parte da solo
- Aggiunto: pulsanti indietro e avanti di 10 secondi nella barra dei comandi, oltre al doppio tocco
- Aggiunto: messaggio chiaro quando un video non si può riprodurre, con un tocco per riprovare
- Modificato: un tocco in qualsiasi punto del video fermo lo fa partire, non solo sul pulsante Riproduci
- Modificato: i comandi stanno in una barra sotto il video, con pulsanti più grandi: Riproduci/Pausa per primo a
  sinistra, poi indietro e avanti; a destra adattamento e schermo intero
- Modificato: sul video resta solo il pulsante Riproduci, e solo quando il video è fermo
- Modificato: il video parte prima (un solo lettore riusato per tutti i video, meno attesa iniziale sulla rete di
  casa) e la musica in riproduzione si ferma quando parte un video
- Modificato: il video va in pausa quando esci dall'app, e lo schermo può spegnersi quando è in pausa

## 1.20.0 — 2026-11-20
- Aggiunto: album nella Galleria. Ogni cartella dentro la cartella del backup è un album con le foto che contiene;
  si aprono dalla nuova scheda «Album», divisi tra album con un nome proprio e cartelle per data.
- Aggiunto: «Nuovo album» crea la cartella corrispondente sul NAS.
- Aggiunto: «Sposta in un album» dalla selezione sposta le foto di cartella sul NAS, nella coda dei trasferimenti,
  senza mai sovrascrivere un file con lo stesso nome.
- Modificato: con delle foto selezionate il titolo è più corto («3 selezionate»), per fare posto al nuovo pulsante.
