<div align="center">

<img src="./.github/assets/honyaku-logo.png" alt="Logo di Honyaku" title="Honyaku" width="96"/>

# Honyaku

### Leggi manga, manhwa e manhua tradotti in automatico

Un lettore di fumetti per Android basato su [Mihon](https://github.com/mihonapp/mihon) che traduce il testo dei balloon direttamente sulla pagina, sul telefono o con Gemini.

[![Licenza: Apache-2.0](https://img.shields.io/badge/licenza-Apache--2.0-0877d2?labelColor=27303D)](/LICENSE)

</div>

## Obiettivo

Molti fumetti escono solo in giapponese, coreano o cinese. Honyaku li rende leggibili subito: apri un capitolo e il testo di ogni balloon viene riconosciuto, tradotto e scritto al suo posto, nella lingua che scegli (l'italiano di partenza).

La traduzione può avvenire **tutta sul telefono**, senza account e senza mandare le pagine a nessuno, oppure con **Gemini** di Google, che capisce il contesto del capitolo e traduce in modo più naturale. Tutto il resto è Mihon: libreria, estensioni, download, tracker e backup.

Honyaku si installa accanto a Mihon senza sostituirlo.

## Funzioni

* **Traduzione sul dispositivo**: il testo viene letto con ML Kit (giapponese, coreano, cinese, alfabeto latino) e tradotto offline. Ogni lingua si scarica una volta.
* **Riconoscimento dei balloon**: un modello sul telefono ([comic-text-and-bubble-detector](https://huggingface.co/ogkalu/comic-text-and-bubble-detector), Apache 2.0) trova ogni balloon, così il suo testo viene tradotto tutto insieme e la traduzione lo riempie.
* **Gemini (facoltativo)**: legge la pagina con il contesto del capitolo letto finora, per mantenere nomi e tono. Usa la tua chiave gratuita di Google AI Studio e puoi scegliere il modello da un elenco. Se Gemini non risponde, la pagina viene tradotta sul telefono.
* **Capitoli tradotti in anticipo**: un capitolo scaricato viene tradotto subito, così le pagine sono pronte quando lo leggi. Le pagine tradotte restano salvate (fino a 500 MB).
* **Avvisi chiari**: se una pagina non si traduce, l'app dice perché (chiave sbagliata, quota esaurita, nessun testo trovato).
* **Accesso rapido**: un pulsante nel menu del lettore accende e spegne la traduzione; una pressione lunga apre le sue impostazioni.

## Come attivare la traduzione

1. Apri un capitolo e tocca il centro della pagina per far comparire i menu.
2. Tocca il **mappamondo** nella barra in basso per accendere la traduzione. Tienilo premuto per aprire le impostazioni (le stesse sono anche in *Altro › Impostazioni › Traduzione*).
3. In **Traduci da** scegli la lingua del fumetto e in **Traduci in** la tua.
4. In **Traduttore** lascia *Sul dispositivo*, oppure scegli *Gemini (online)*:
   1. su [aistudio.google.com](https://aistudio.google.com) crea una chiave API gratuita;
   2. incollala in **Chiave API di Gemini**;
   3. scegli il modello dall'elenco.

La prima volta servono internet e qualche secondo per scaricare la lingua e il modello dei balloon; dopo, la traduzione sul dispositivo funziona offline.

> Con Gemini le pagine vengono inviate a Google, che nel piano gratuito può usarle per migliorare i suoi prodotti.

## Download

L'APK viene compilato da GitHub Actions a ogni modifica: apri l'ultima esecuzione riuscita in [Actions](../../actions) e scarica **Honyaku.apk** (serve aver fatto l'accesso a GitHub).

*Richiede Android 8.0 o successivo.*

## Licenza

Honyaku è un fork di [Mihon](https://github.com/mihonapp/mihon), distribuito come Mihon sotto licenza [Apache 2.0](/LICENSE).

    Copyright © 2015 Javier Tomás
    Copyright © 2024 Mihon Open Source Project

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

## Disclaimer

Gli sviluppatori di questa applicazione non sono affiliati ai fornitori di contenuti disponibili e questa applicazione non ospita né distribuisce alcun contenuto.
