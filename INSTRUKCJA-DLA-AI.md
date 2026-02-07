# Instrukcja: Przystosowanie projektu do VoicePortalLauncher

## Kontekst

Ten projekt bedzie uruchamiany przez aplikacje Android **VoicePortalLauncher** na urzadzeniu z Termux.
Launcher startuje dev server przez intent do Termux, czeka az serwer odpowie na HTTP GET na podanym porcie,
a nastepnie otwiera `http://127.0.0.1:<port>` w pelnoekranowym Android WebView.

## Srodowisko uruchomieniowe

| Parametr | Wartosc |
|----------|---------|
| System | Android z Termux |
| Shell | `/data/data/com.termux/files/usr/bin/bash` |
| HOME | `/data/data/com.termux/files/home` |
| Sciezka projektu | `$HOME/<projectPath>` (np. `$HOME/projekty/MojaApka`) |
| Przegladarka | Android WebView (Chromium) - pelny ekran |
| Siec | Tylko localhost (127.0.0.1), cleartext HTTP dozwolony |

## Co robi launcher krok po kroku

1. **Uruchamia komende** w Termux jako proces w tle:
   ```bash
   cd /data/data/com.termux/files/home/<projectPath> && \
   termux-fix-shebang /data/data/com.termux/files/home/<projectPath>/node_modules/.bin/* 2>/dev/null; \
   <devCommand>
   ```
2. **Czeka 2 sekundy** po wyslaniu komendy
3. **(Opcjonalnie)** Startuje VoicePortal proxy na porcie 3456
4. **Polluje port** - co 2s wysyla HTTP GET na `http://127.0.0.1:<port>`, timeout 30s
5. **Kryterium gotowosci**: serwer zwraca HTTP status 200-499 (czyli dowolna odpowiedz, nawet 404)
6. **Otwiera WebView** pod adresem `http://127.0.0.1:<port>`

## Wymagania dla projektu

### 1. Dev server MUSI nasluchiwac na wlasciwym porcie

Serwer musi bindowac sie na `127.0.0.1:<port>` lub `0.0.0.0:<port>`.

**Vite (domyslnie port 5173):**
```js
// vite.config.js / vite.config.ts
export default defineConfig({
  server: {
    host: '0.0.0.0',  // WAZNE: nie tylko localhost
    port: 5173,        // musi zgadzac sie z konfiguracją w launcherze
    strictPort: true   // nie zmieniaj portu jesli zajety - fail zamiast tego
  }
})
```

**Next.js (domyslnie port 3000):**
```json
// package.json
{
  "scripts": {
    "dev": "next dev -H 0.0.0.0 -p 3000"
  }
}
```

**Express / Node.js custom:**
```js
app.listen(3000, '0.0.0.0', () => {
  console.log('Server ready on port 3000');
});
```

**Python (Flask/FastAPI):**
```bash
# Flask
flask run --host=0.0.0.0 --port=5000

# FastAPI/Uvicorn
uvicorn main:app --host 0.0.0.0 --port 8000
```

### 2. Dev command musi byc jednolinijkowa komenda bash

Launcher wykonuje komende przez `bash -c "<devCommand>"` w katalogu projektu.

Przyklady poprawnych komend:
```
npm run dev
npx vite --host 0.0.0.0
node server.js
python -m http.server 8080
PORT=3000 npm start
```

**UWAGA:** Komenda jest uruchamiana w tle (nie w interaktywnym terminalu). Nie moze wymagac interakcji uzytkownika.

### 3. GET / MUSI odpowiedziec w mniej niz 2 sekundy

**KRYTYCZNE:** Launcher polluje `GET http://127.0.0.1:<port>/` z **2-sekundowym read timeout** na kazdy request. Jesli serwer przyjmie polaczenie ale nie wysle odpowiedzi w 2s → launcher traktuje to jako "not responding".

To oznacza ze handler `GET /` **NIE MOZE BLOKOWAC** na:
- Synchronicznych wywolaniach API (fetch do zewnetrznych serwisow, bazy danych)
- Ciezkich obliczeniach (renderowanie szablonow z remote data)
- I/O ktore moze trwac dluzej niz 1-2 sekundy

**Zly wzorzec (blokuje odpowiedz):**
```python
def handle_root():
    data = fetch_from_api()  # 3 sekundy!
    return render(template, data)  # za pozno, launcher juz timeout
```

**Dobry wzorzec (natychmiastowa odpowiedz):**
```python
def handle_root():
    data = cache.get('data', [])  # z pamieci, instant
    return render(template, data)  # <10ms

# Background thread wypelnia cache
threading.Thread(target=prefetch, daemon=True).start()
```

### 4. Calkowity czas startu musi byc <30 sekund

Launcher polluje port co 2s przez 30 sekund (15 prob). Serwer musi bindowac port w tym czasie.

Jesli projekt wymaga dlugiego buildu:
- Rozważ prebuild: `npm run build && npm run preview` zamiast `npm run dev`
- Albo upewnij sie ze dev server odpowiada szybko (hot reload bez pelnego rebuildu)

### 5. Serwer musi odpowiadac na HTTP GET /

Launcher sprawdza gotowosc przez prosty `GET http://127.0.0.1:<port>`. Akceptuje odpowiedz HTTP ze statusem **200-499** (nawet 404 jest OK). Status 500+ jest traktowany jako "not responding". Nie moze byc connection refused / timeout.

### 5. Aplikacja dziala w Android WebView

WebView ma nastepujace ustawienia:
- JavaScript: **wlaczony**
- DOM Storage / localStorage: **wlaczony**
- Cookies (w tym third-party): **wlaczone**
- `getUserMedia` (mikrofon): **wlaczony** (permission auto-granted)
- Media autoplay bez gestu: **wlaczony**
- Cache: **domyslny (LOAD_DEFAULT)**
- Mixed content: **dozwolony**
- WebView debugging: **wlaczony** (chrome://inspect)

**Ograniczenia WebView vs pelna przegladarka:**
- Brak `window.open()` / otwierania nowych kart
- Brak `alert()` / `confirm()` / `prompt()` (moga nie dzialac)
- Brak dostępu do systemu plikow uzytkownika (File API ograniczone)
- Back button = `history.back()`, potem zamkniecie activity
- Brak paska adresu - wszystko pelnoekranowe
- Brak service workers (w niektorych wersjach WebView)

### 6. Shebangs w node_modules

Launcher automatycznie uruchamia `termux-fix-shebang node_modules/.bin/*` przed devCommand.
To naprawia `#!/usr/bin/env node` → sciezke Termux. Nie trzeba tego robic recznie.

## Konfiguracja w launcherze

Aby dodac aplikacje do launchera, uzytkownik moze:

**A) Przez UI** - przycisk "+" w MainActivity, pola formularza:

| Pole | Opis | Przyklad |
|------|------|---------|
| Name | Nazwa wyswietlana | `Moja Apka` |
| Description | Opis (opcjonalny) | `Todo app z voice` |
| Project Path | Sciezka wzgledem HOME | `projekty/MojaApka` |
| Port | Port dev servera | `5173` |
| Dev Command | Komenda startowa | `npm run dev` |
| Use VoicePortal | Checkbox - czy uzywac proxy | zaznaczony/nie |
| Voice Mode | Tryb VoicePortal (jesli wlaczony) | `default` |
| Idle Timeout | Minuty do auto-kill (0=nigdy) | `30` |

**B) Przez plik `assets/apps.json`** (wbudowane aplikacje przy pierwszym uruchomieniu):
```json
[
  {
    "id": "mojaapka",
    "name": "Moja Apka",
    "description": "Opis aplikacji",
    "projectPath": "projekty/MojaApka",
    "port": 5173,
    "devCommand": "npm run dev",
    "voicePortalMode": "none",
    "idleTimeoutMin": 0
  }
]
```

### Pola konfiguracji

| Pole | Typ | Wymagane | Domyslna | Opis |
|------|-----|----------|----------|------|
| `id` | string | tak | - | Unikalny identyfikator (lowercase, bez spacji) |
| `name` | string | tak | - | Nazwa wyswietlana w UI |
| `description` | string | nie | `""` | Opis w liscie |
| `projectPath` | string | tak | - | Sciezka wzgledem `$HOME` (np. `projekty/App`) |
| `port` | int | tak | - | Port na ktorym nasluchuje dev server |
| `devCommand` | string | nie | `npm run dev` | Komenda do uruchomienia serwera |
| `voicePortalMode` | string | nie | `"default"` | `"none"` = bez proxy, cokolwiek innego = wlacz proxy |
| `idleTimeoutMin` | int | nie | `0` | Minuty bez aktywnosci do auto-kill (0 = nigdy) |

## Checklist przed uruchomieniem

- [ ] Projekt jest w `$HOME/<projectPath>` (np. `/data/data/com.termux/files/home/projekty/MojaApka`)
- [ ] `npm install` (lub odpowiednik) zostal wykonany w Termux
- [ ] Dev server binduje na `0.0.0.0` (nie tylko `localhost`)
- [ ] Port jest poprawny i nie zajety przez inny proces
- [ ] Dev server startuje w <30 sekund
- [ ] Aplikacja dziala w przegladarce pod `http://localhost:<port>`
- [ ] Konfiguracja dodana w launcherze (UI lub apps.json)

## Typowe problemy i rozwiazania

### Serwer nie odpowiada (timeout 30s)
```bash
# Sprawdz czy serwer dziala:
curl http://127.0.0.1:<port>

# Sprawdz czy port nasluchuje:
ss -tlnp | grep <port>

# Sprawdz logi procesu:
logcat | grep ServerLauncher
```

### "Command not found" / bledne shebangi
```bash
# Recznie napraw shebangi:
termux-fix-shebang node_modules/.bin/*

# Lub uzyj pelnej sciezki:
# devCommand: "node_modules/.bin/vite --host 0.0.0.0"
```

### Port juz zajety
```bash
# Zabij proces na porcie:
fuser -k <port>/tcp

# Lub uzywaj strictPort: true w vite.config
```

### WebView nie laduje strony
- Upewnij sie ze serwer odpowiada na HTTP (nie HTTPS)
- Sprawdz czy nie ma przekierowan na inny host
- WebView otwiera `http://127.0.0.1:<port>` - serwer musi tam odpowiadac

### Mikrofon nie dziala w WebView
- Uprawnienie RECORD_AUDIO jest auto-granted przez launcher
- getUserMedia jest pre-warmed po zaladowaniu strony
- Uzywaj `navigator.mediaDevices.getUserMedia({audio: true})`
- Nie uzywaj starego `navigator.getUserMedia`

## Przyklad: Minimalny projekt Vite + React

```bash
# W Termux:
cd ~/projekty
npm create vite@latest MojaApka -- --template react
cd MojaApka
npm install
```

```js
// vite.config.js
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true
  }
})
```

Konfiguracja w launcherze:
- **Project Path:** `projekty/MojaApka`
- **Port:** `5173`
- **Dev Command:** `npm run dev`
- **VoicePortal:** nie (unchecked)

## Przyklad: Python FastAPI

```bash
cd ~/projekty/MyAPI
pip install fastapi uvicorn
```

```python
# main.py
from fastapi import FastAPI
from fastapi.responses import HTMLResponse

app = FastAPI()

@app.get("/")
def root():
    return HTMLResponse("<h1>Hello from FastAPI</h1>")
```

Konfiguracja w launcherze:
- **Project Path:** `projekty/MyAPI`
- **Port:** `8000`
- **Dev Command:** `uvicorn main:app --host 0.0.0.0 --port 8000`
- **VoicePortal:** nie (unchecked)

## Przyklad: Statyczny HTML z live-server

```bash
cd ~/projekty/MySite
npm install -g live-server
```

Konfiguracja w launcherze:
- **Project Path:** `projekty/MySite`
- **Port:** `8080`
- **Dev Command:** `live-server --port=8080 --host=0.0.0.0 --no-browser`
- **VoicePortal:** nie (unchecked)
