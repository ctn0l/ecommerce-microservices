# E-commerce microservices

Migrazione del progetto monolitico in applicazioni Spring Boot indipendenti. `order-service` gestisce carrello e checkout, verifica gli utenti via HTTP e coordina le prenotazioni delle scorte con `product-service`.

| Applicazione | HTTP locale | Database locale |
| --- | --- | --- |
| user-service | 8081 | users, PostgreSQL su 5433 |
| product-service | 8082 | products, PostgreSQL su 5434 |
| order-service | 8083 | orders, PostgreSQL su 5435 |

Ogni applicazione usa Java 26, Spring Boot 4.1.0, Maven Wrapper, JPA e Flyway. Docker Compose avvia **soltanto i database**. Le applicazioni si avviano separatamente.

## Avvio locale

Prerequisiti: JDK 26, Docker avviato e accesso alle dipendenze Maven.

Dalla root:

```sh
cp .env.example .env
# Facoltativo: modificare porte e credenziali prima di avviare i database.
docker compose up -d --wait
```

In tre terminali, dalla root, avviare rispettivamente:

```sh
set -a
source .env
set +a
cd user-service
./mvnw spring-boot:run
```

```sh
set -a
source .env
set +a
cd product-service
./mvnw spring-boot:run
```

```sh
set -a
source .env
set +a
cd order-service
./mvnw spring-boot:run
```

Compose legge `.env` automaticamente; Spring Boot riceve le variabili esportate nel terminale. In alternativa configurarle nell'IDE. Le password di esempio sono esclusivamente per sviluppo locale.

Verificare `http://localhost:8081/actuator/health`, `http://localhost:8082/actuator/health` e `http://localhost:8083/actuator/health`.

Se in futuro le applicazioni saranno eseguite in container, gli URL dovranno usare i nomi DNS dei servizi Docker: `localhost` dentro un container indica quel container. Per i database si userà la porta interna 5432.

## Esempio di checkout

Creare un utente e un prodotto; usare gli ID restituiti nelle chiamate successive:

```sh
curl -i http://localhost:8081/api/users \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Ada","lastName":"Lovelace","email":"ada@example.test"}'

curl -i http://localhost:8082/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Keyboard","price":39.99,"stockQuantity":5,"category":"Electronics"}'

# Sostituire 1 con gli ID ricevuti.
curl -i http://localhost:8083/api/cart \
  -H 'X-User-ID: 1' -H 'Content-Type: application/json' \
  -d '{"productId":1,"quantity":2}'

curl -i http://localhost:8083/api/orders \
  -X POST -H 'X-User-ID: 1' \
  -H 'Idempotency-Key: 3e95a8f0-2c37-47a8-bc63-a9e5a516bdab'

curl -i http://localhost:8083/api/orders/1 -H 'X-User-ID: 1'
```

Generare un nuovo UUID per ogni nuovo acquisto, per esempio con `uuidgen`. Conservare e riutilizzare **la stessa chiave** quando si ripete una richiesta di cui non si conosce l'esito. Una chiave identifica il primo checkout persistito per quell'utente: non acquista il nuovo contenuto del carrello e non viene riutilizzata dopo un checkout fallito.

Il prezzo e il nome sono acquisiti dal catalogo quando si aggiunge il prodotto al carrello. Aggiunte successive dello stesso prodotto aggiornano questi dati per tutta la riga, come il comportamento del prezzo nel monolito. Il checkout conserva tali valori, calcola il totale sul server e ricontrolla disponibilità e stato attivo mediante la prenotazione.

## API ordini e carrello

Tutte queste API richiedono `X-User-ID`, un intero positivo.

| Metodo | Endpoint | Esito |
| --- | --- | --- |
| POST | `/api/cart` | Aggiunge quantità: `201`; body `productId`, `quantity` positivi |
| GET | `/api/cart` | Carrello corrente, anche durante il checkout |
| DELETE | `/api/cart/items/{productId}` | Rimuove la riga: `204` o `404`; `409` se il carrello è congelato |
| POST | `/api/orders` | Acquista il carrello; richiede `Idempotency-Key` UUID |
| GET | `/api/orders/{id}` | Ordine dell'utente; `404` anche se appartiene a un altro utente |
| GET | `/api/orders?page=0&size=20` | Ordini dell'utente, dal più recente; massimo 100 per pagina |
| POST | `/api/orders/{id}/cancel` | Annulla un checkout ancora in fase `RESERVING`; `202` se il rilascio deve essere recuperato |

`POST /api/orders` restituisce un ordine con `Location`:

- `201`: checkout completato, stato `CONFIRMED`.
- `202`: ordine persistito, stato `PENDING`; consultare `Location`. `Retry-After: 5` suggerisce l'intervallo di polling. Il recupero prosegue anche senza richieste del client.
- `409`: checkout fallito e compensato, stato `CANCELLED`. Il carrello rimane disponibile. Anche carrello vuoto o un altro checkout in corso producono `409`, senza creare un nuovo ordine.
- `400`: header, UUID, body o parametri non validi.
- `404`: utente inesistente durante la creazione.
- `503`: dipendenza non verificabile prima di registrare il checkout, per esempio il servizio utenti non risponde.

Una ripetizione dello stesso checkout restituisce l'esito corrente. Le letture di ordini e carrello e la rimozione delle righe non richiedono la disponibilità degli altri servizi.

`CONFIRMED` significa che ordine e consumo delle scorte sono stati completati; **non** significa pagamento ricevuto. Pagamenti, spedizioni, resi e annullamento di ordini già confermati richiedono processi di business ulteriori e non sono implementati dal checkout.

Limiti: massimo 100 prodotti distinti nel carrello, quantità positive entro il limite di un intero Java e totale massimo `9.999.999.999,99`. Il controllo delle scorte durante l'aggiunta al carrello è indicativo: la garanzia concorrenziale viene applicata alla prenotazione.

## API delle scorte

Queste operazioni appartengono a `product-service` e sono usate da ordini:

| Metodo | Endpoint | Operazione |
| --- | --- | --- |
| PUT | `/api/stock-reservations/{checkoutId}` | Prenota un insieme di prodotti in una transazione |
| POST | `/api/stock-reservations/{checkoutId}/confirm` | Conferma il consumo, senza scalare di nuovo le scorte |
| POST | `/api/stock-reservations/{checkoutId}/release` | Ripristina le scorte prenotate una sola volta |

Richiesta di prenotazione:

```json
{"items":[{"productId":1,"quantity":2}]}
```

Risposta:

```json
{"checkoutId":"3e95a8f0-2c37-47a8-bc63-a9e5a516bdab","status":"RESERVED"}
```

Le risposte di successo sono `200`. Lo stesso ID e lo stesso insieme di prodotti restituiscono la prenotazione esistente; un body diverso con lo stesso ID restituisce `409`. Sono vietati prodotti duplicati e quantità non positive. Un prodotto mancante produce `404`; indisponibile o inattivo produce `409`, annullando la prenotazione dell'intero insieme.

Il rilascio di un ID sconosciuto registra uno stato `RELEASED` che impedisce a una richiesta di prenotazione ritardata di consumare scorte. Una prenotazione confermata non è rilasciabile. Il CRUD prodotti non permette di sostituire `stockQuantity` finché esistono prenotazioni attive per quel prodotto (`409`). Dopo la prenotazione il campo rappresenta la quantità ancora disponibile.

## Test

Con Docker avviato, eseguire in ciascuna directory di servizio:

```sh
./mvnw verify
```

I test di integrazione usano database PostgreSQL temporanei tramite Testcontainers, senza toccare i database Compose. Coprono anche acquisti concorrenti, idempotenza, rollback dell'intero insieme di prodotti, timeout HTTP, compensazioni e un errore PostgreSQL dopo una conferma remota.

Dopo aver creato i tre JAR con `verify`, dalla root:

```sh
python3 scripts/smoke-checkout.py
```

Lo smoke test avvia tre database nuovi e i tre servizi, prova checkout e replay, arresta prodotti, riavvia anche ordini e verifica il recupero persistente. Verifica infine scorte insufficienti e compensazione. Usa porte locali temporanee e rimuove solo i container che ha creato, compresi i volumi anonimi; conserva i log in una directory temporanea stampata all'avvio.

## Architettura e recupero

Vedere [il flusso del checkout e le differenze dal monolito](docs/order-service-design.md) per confini dei dati, transazioni e gestione dei guasti.

L'applicazione mantiene il modello di accesso del progetto esistente: `X-User-ID` è un'identità dichiarata, **non autenticata**. Prima di esporre queste API fuori da un ambiente di sviluppo fidato serviranno autenticazione, autorizzazione e protezione delle API delle scorte per l'accesso tra servizi. Non usare questo header come prova d'identità in produzione.
