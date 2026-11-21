# Changelog

Storia delle versioni di SMBCommander, la più recente in alto. Le versioni fino alla 1.19.0 sono descritte nelle
[release su GitHub](https://github.com/thamichele/SMBCommander/releases).

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
