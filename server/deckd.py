#!/usr/bin/env python3
"""deckd — бэкенд для Android deck: MPRIS (playerctl), громкость и микрофон (wpctl),
запуск приложений (быстрые из конфига и все .desktop), расписание и уведомления от других программ
(POST /schedule, POST /notify — так сюда пишет QuickSchedule; что нажали на телефоне, он забирает
длинным опросом GET /schedule/actions).
Связь — по сети или через ADB (adb reverse)."""
import asyncio
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import quote, unquote, urlparse

import uvicorn
import yaml
from fastapi import Body, Depends, FastAPI, Header, HTTPException, Query, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse

CFG_PATH = Path(os.environ.get("DECK_CONFIG", "~/.config/deckd/config.yaml")).expanduser()
CFG = yaml.safe_load(CFG_PATH.read_text())
TOKEN = str(CFG.get("token", ""))
PORT = int(CFG.get("port", 8765))
ADB = bool(CFG.get("adb", False))
PLAYERS = CFG.get("players", "%any")
APPS = {a["id"]: a for a in CFG.get("apps", [])}
SINK = "@DEFAULT_AUDIO_SINK@"
SOURCE = "@DEFAULT_AUDIO_SOURCE@"
# ссылки из расписания и уведомлений открываем на этом компьютере — только такие
OPEN_SCHEMES = set(CFG.get("open_schemes", ["http", "https", "tg"]))
SCHEDULE_TTL = 180  # источник шлёт снимок раз в минуту; замолчал — расписание на телефоне гасим
# последний снимок переживает перезапуск deckd: иначе телефон до минуты ждал бы следующего
SCHEDULE_FILE = Path(os.environ.get("XDG_CACHE_HOME") or "~/.cache").expanduser() / "deckd" / "schedule.json"
SEP = "\x1f"
FMT = SEP.join(["{{status}}", "{{playerName}}", "{{artist}}", "{{title}}", "{{album}}",
                "{{mpris:artUrl}}", "{{mpris:length}}", "{{position}}"])

clients: set[WebSocket] = set()
kick = asyncio.Event()
art_files: dict[str, Path] = {}
last: dict = {}
desktop: dict[str, dict] = {}
icons: dict[str, Path] = {}
icons_at = 0.0
schedule: dict | None = None  # {"data": снимок для телефона, "links": {ключ: url}, "at": когда пришёл}
notes: dict[str, dict] = {}  # уведомления: id -> {..., "url", "until"}
actions: list[dict] = []  # нажатия с телефона, которые ждёт источник расписания
actions_ready = asyncio.Event()
ACTION_TTL = 30  # источник не забрал за это время — нажатие устарело: не отмечать дело через час


async def run(*args: str) -> str | None:
    p = await asyncio.create_subprocess_exec(
        *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
    out, _ = await p.communicate()
    return out.decode(errors="replace").strip() if p.returncode == 0 else None


def pctl(*args: str):
    return run("playerctl", f"--player={PLAYERS}", *args)


def us(v: str) -> float:
    try:
        return round(int(float(v)) / 1e6, 1)
    except ValueError:
        return 0.0


def art_ref(u: str) -> str:
    if not u.startswith("file://"):
        return u  # http(s) или пусто — клиент грузит сам
    path = Path(unquote(urlparse(u).path))
    try:
        mtime = path.stat().st_mtime
    except OSError:
        return ""
    key = hashlib.sha1(f"{path}:{mtime}".encode()).hexdigest()[:16]
    if len(art_files) > 64:
        art_files.clear()
    art_files[key] = path
    return f"/art/{key}"


async def read_player() -> dict | None:
    out = await pctl("metadata", "--format", FMT)
    if not out:
        return None
    status, name, artist, title, album, art, length, pos = (out.split(SEP) + [""] * 8)[:8]
    return {"status": status, "name": name, "artist": artist, "title": title, "album": album,
            "art": art_ref(art), "length": us(length), "position": us(pos)}


async def read_volume(node: str = SINK) -> tuple[float, bool]:
    out = await run("wpctl", "get-volume", node) or ""  # "Volume: 0.45 [MUTED]"
    try:
        vol = float(out.split()[1])
    except (IndexError, ValueError):
        vol = 0.0
    return vol, "[MUTED]" in out


def load_schedule() -> dict | None:
    try:
        d = json.loads(SCHEDULE_FILE.read_text())
        age = time.time() - d["time"]
        if 0 <= age < SCHEDULE_TTL:
            return {"data": d["data"], "links": d["links"], "at": time.monotonic() - age}
    except (OSError, ValueError, KeyError, TypeError):
        pass
    return None


def save_schedule():
    try:
        SCHEDULE_FILE.parent.mkdir(parents=True, exist_ok=True)
        tmp = SCHEDULE_FILE.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)  # внутри ссылки на созвоны
        with os.fdopen(fd, "w") as f:
            json.dump({"data": schedule["data"], "links": schedule["links"], "time": time.time()}, f)
        os.replace(tmp, SCHEDULE_FILE)
    except OSError as e:
        print("schedule cache:", e)


def schedule_msg() -> dict:
    return {"event": "schedule", "data": schedule["data"] if schedule else None}


def note_msg(n: dict) -> dict:
    return {"event": "notify", **{k: n[k] for k in ("id", "app", "title", "body", "action", "urgent")},
            "ttl": round(n["until"] - time.monotonic(), 1)}


def open_url(url: str) -> bool:
    if urlparse(url).scheme not in OPEN_SCHEMES:
        return False
    subprocess.run(["setsid", "-f", "xdg-open", url],
                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return True


async def broadcast(state: dict):
    msg = json.dumps(state)
    for ws in list(clients):
        try:
            await ws.send_text(msg)
        except Exception:
            clients.discard(ws)


async def poller():
    global last, schedule
    while True:
        kick.clear()
        try:
            if schedule and time.monotonic() - schedule["at"] > SCHEDULE_TTL:
                schedule = None
                await broadcast(schedule_msg())
            vol, muted = await read_volume()
            state = {"player": await read_player(), "volume": vol, "muted": muted,
                     "mic_muted": (await read_volume(SOURCE))[1]}
            if state != last:
                last = state
                await broadcast(state)
        except Exception as e:
            print("poll:", e)
        try:
            await asyncio.wait_for(kick.wait(), 1.0)
        except asyncio.TimeoutError:
            pass


async def adb_watcher():
    """Держит `adb reverse` на подключённых устройствах: deck ходит на свой 127.0.0.1:PORT без Wi-Fi."""
    spec = f"tcp:{PORT}"
    while True:
        ready: set[str] = set()
        try:
            p = await asyncio.create_subprocess_exec(
                "adb", "track-devices", stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
        except FileNotFoundError:
            print("adb: не найден в PATH")
            return
        try:
            while True:  # на каждое изменение: 4 hex-цифры длины + строки "serial\tstate"
                n = int(await p.stdout.readexactly(4), 16)
                rows = (await p.stdout.readexactly(n)).decode(errors="replace").splitlines()
                online = {r.split("\t")[0] for r in rows if r.endswith("\tdevice")}
                for serial in online - ready:
                    if await run("adb", "-s", serial, "reverse", spec, spec) is None:
                        print("adb: reverse не удался:", serial)
                        online.discard(serial)
                ready = online
        except (asyncio.IncompleteReadError, ValueError):
            pass  # adb-сервер упал/перезапущен — переподключаемся
        finally:
            if p.returncode is None:
                p.kill()
        await asyncio.sleep(3)


def data_dirs() -> list[Path]:
    dirs = [os.environ.get("XDG_DATA_HOME") or "~/.local/share",
            *(os.environ.get("XDG_DATA_DIRS") or "/usr/local/share:/usr/share").split(":"),
            "~/.local/share/flatpak/exports/share", "/var/lib/flatpak/exports/share",
            "/var/lib/snapd/desktop"]
    return list(dict.fromkeys(Path(d).expanduser() for d in dirs if d))


def build_icons() -> dict[str, Path]:
    """Имя иконки -> лучший файл: сначала hicolor, потом прочие темы и pixmaps."""
    # плитка на телефоне ~200px: 256 в самый раз, 512 и svg декодируются заметно дольше
    sizes = ["256x256", "192x192", "512x512", "scalable", "128x128", "96x96", "72x72", "64x64", "48x48"]
    best: dict[str, tuple[tuple, Path]] = {}
    roots = [d / "icons" for d in data_dirs()] + [Path("/usr/share/pixmaps")]
    for ri, root in enumerate(roots):
        for cur, _, files in os.walk(root):
            parts = Path(cur).relative_to(root).parts
            size = next((sizes.index(x) for x in parts if x in sizes), len(sizes))
            rank = (parts[:1] != ("hicolor",), ri, size)
            for fn in files:
                stem, ext = os.path.splitext(fn)
                if ext in (".png", ".svg") and (stem not in best or rank < best[stem][0]):
                    best[stem] = (rank, Path(cur, fn))
    return {k: v[1] for k, v in best.items()}


def find_icon(name: str) -> Path | None:
    global icons, icons_at
    if not name:
        return None
    p = Path(name).expanduser()
    if p.is_file():
        return p
    if time.monotonic() - icons_at > 60:
        icons, icons_at = build_icons(), time.monotonic()
    return icons.get(re.sub(r"\.(png|svg|xpm)$", "", name))


def parse_desktop(f: Path) -> dict[str, str]:
    d, on = {}, False
    try:
        for line in f.read_text(errors="replace").splitlines():
            line = line.strip()
            if line.startswith("["):
                on = line == "[Desktop Entry]"
            elif on and "=" in line and not line.startswith("#"):
                k, v = line.split("=", 1)
                d[k.strip()] = v.strip()
    except OSError:
        pass
    return d


def scan_desktop() -> dict[str, dict]:
    """Приложения из .desktop, как в меню: id -> {name, icon, cmd, cwd}."""
    loc = (os.environ.get("LC_MESSAGES") or os.environ.get("LANG") or "").split(".")[0]
    env = set(os.environ.get("XDG_CURRENT_DESKTOP", "").split(":"))
    seen, out = set(), {}
    for base in data_dirs():
        root = base / "applications"
        for f in sorted(root.rglob("*.desktop")):
            app_id = "-".join(f.relative_to(root).with_suffix("").parts)
            if app_id in seen:  # файл из более приоритетного каталога перекрывает (и скрывает) остальные
                continue
            seen.add(app_id)
            d = parse_desktop(f)
            only, deny = d.get("OnlyShowIn"), d.get("NotShowIn", "")
            if (d.get("Type") != "Application" or not d.get("Exec")
                    or "true" in (d.get("NoDisplay"), d.get("Hidden"), d.get("Terminal"))
                    or (only and not env & set(only.split(";"))) or env & set(deny.split(";")) - {""}
                    or (d.get("TryExec") and not shutil.which(d["TryExec"]))):
                continue
            out[app_id] = {
                "name": d.get(f"Name[{loc}]") or d.get(f"Name[{loc[:2]}]") or d.get("Name") or app_id,
                "icon": d.get("Icon", ""),
                # коды полей (%U, %f, ...) нам подставлять нечем
                "cmd": re.sub(r"%(.)", lambda m: "%" if m[1] == "%" else "", d["Exec"]).strip(),
                "cwd": d.get("Path"),
            }
    return out


def entry(prefix: str, app_id: str, name: str, icon: str) -> dict:
    q, f = quote(app_id, safe=""), find_icon(icon)
    # v= меняется вместе с файлом, поэтому саму иконку клиент кэширует навсегда
    return {"id": app_id, "name": name, "icon": f"{prefix}/icon/{q}?v={int(f.stat().st_mtime)}" if f else "",
            "launch": f"{prefix}/launch/{q}"}


def icon_file(name: str) -> FileResponse:
    f = find_icon(name)
    if not f:
        raise HTTPException(404)
    return FileResponse(f, headers={"Cache-Control": "max-age=31536000, immutable"})


def spawn(cmd: str, cwd: str | None = None):
    # setsid -f: отцепляем процесс, без зомби
    subprocess.run(["setsid", "-f", "sh", "-c", cmd], cwd=cwd if cwd and os.path.isdir(cwd) else None,
                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def auth(t: str = Query(""), x_token: str = Header("")):
    if TOKEN and TOKEN not in (t, x_token):
        raise HTTPException(401)


@asynccontextmanager
async def lifespan(_):
    global schedule
    schedule = load_schedule()
    tasks = [asyncio.create_task(poller())]
    if ADB:
        tasks.append(asyncio.create_task(adb_watcher()))
    yield
    for t in tasks:
        t.cancel()


app = FastAPI(lifespan=lifespan)
A = [Depends(auth)]


@app.get("/apps", dependencies=A)
def list_apps():
    return [entry("", a["id"], a.get("name", a["id"]), a.get("icon", "")) for a in APPS.values()]


@app.get("/icon/{app_id}", dependencies=A)
def icon(app_id: str):
    return icon_file(APPS.get(app_id, {}).get("icon", ""))


@app.get("/art/{key}", dependencies=A)
def art(key: str):
    f = art_files.get(key)
    if not f or not f.is_file():
        raise HTTPException(404)
    return FileResponse(f)


@app.post("/launch/{app_id}", dependencies=A)
def launch(app_id: str):
    a = APPS.get(app_id)
    if not a:
        raise HTTPException(404)
    spawn(a["cmd"])
    return {"ok": True}


@app.get("/desktop", dependencies=A)
def list_desktop():
    global desktop
    desktop = scan_desktop()
    return sorted((entry("/desktop", i, a["name"], a["icon"]) for i, a in desktop.items()),
                  key=lambda e: e["name"].casefold())


@app.get("/desktop/icon/{app_id}", dependencies=A)
def desktop_icon(app_id: str):
    return icon_file((desktop or scan_desktop()).get(app_id, {}).get("icon", ""))


@app.post("/desktop/launch/{app_id}", dependencies=A)
def desktop_launch(app_id: str):
    a = (desktop or scan_desktop()).get(app_id)
    if not a:
        raise HTTPException(404)
    spawn(a["cmd"], a["cwd"])
    return {"ok": True}


@app.post("/player/{action}", dependencies=A)
async def player(action: str, pos: float | None = None):
    if action in ("play-pause", "next", "previous"):
        await pctl(action)
    elif action == "seek" and pos is not None:
        await pctl("position", f"{pos:.1f}")
    else:
        raise HTTPException(400)
    kick.set()
    return {"ok": True}


@app.post("/volume", dependencies=A)
async def volume(v: float):
    await run("wpctl", "set-volume", "-l", "1.0", SINK, f"{max(0.0, min(v, 1.0)):.2f}")
    kick.set()
    return {"ok": True}


@app.post("/mute", dependencies=A)
async def mute():
    await run("wpctl", "set-mute", SINK, "toggle")
    kick.set()
    return {"ok": True}


@app.post("/mic-mute", dependencies=A)
async def mic_mute():
    await run("wpctl", "set-mute", SOURCE, "toggle")
    kick.set()
    return {"ok": True}


@app.get("/schedule", dependencies=A)
def get_schedule():
    return schedule["data"] if schedule else None


@app.post("/schedule", dependencies=A)
async def push_schedule(body: dict = Body(...)):
    """Снимок расписания от источника. data уходит на телефон как есть, links остаются здесь."""
    global schedule
    changed = schedule is None or schedule["data"] != body.get("data")
    schedule = {"data": body.get("data"), "links": body.get("links") or {}, "at": time.monotonic()}
    save_schedule()
    if changed:
        await broadcast(schedule_msg())
    return {"ok": True}


@app.post("/schedule/open/{key}", dependencies=A)
def schedule_open(key: str):
    if not open_url(((schedule or {}).get("links") or {}).get(key, "")):
        raise HTTPException(404)
    return {"ok": True}


@app.post("/schedule/task/{task_id}", dependencies=A)
async def task_done(task_id: str, done: bool = True):
    """На телефоне отметили дело. Само дело меняет источник расписания — он заберёт это из /schedule/actions."""
    now = time.monotonic()
    actions[:] = [a for a in actions if a["at"] > now - ACTION_TTL][-100:]
    actions.append({"type": "task", "id": task_id, "done": done, "at": now})
    actions_ready.set()
    return {"ok": True}


@app.get("/schedule/actions", dependencies=A)
async def take_actions(wait: float = 0):
    """Длинный опрос для источника расписания: ответ — как только на телефоне что-то нажали или через wait секунд."""
    if not actions:
        actions_ready.clear()
        try:
            await asyncio.wait_for(actions_ready.wait(), max(0.0, min(wait, 60.0)))
        except asyncio.TimeoutError:
            pass
    now = time.monotonic()
    out = [{k: v for k, v in a.items() if k != "at"} for a in actions if a["at"] > now - ACTION_TTL]
    actions.clear()
    return out


@app.post("/notify", dependencies=A)
async def push_note(body: dict = Body(...)):
    """Уведомление на телефон: title, body, app, urgent, ttl (секунд на экране), url + label — кнопка."""
    now = time.monotonic()
    for k in [k for k, n in notes.items() if n["until"] + 60 < now]:  # минуту держим: по кнопке могли нажать в последний миг
        del notes[k]
    url = str(body.get("url") or "")
    if urlparse(url).scheme not in OPEN_SCHEMES:  # такую не откроем — и кнопку не показываем
        url = ""
    n = {"id": uuid.uuid4().hex[:12], "app": str(body.get("app") or ""), "title": str(body.get("title") or ""),
         "body": str(body.get("body") or ""), "action": str(body.get("label") or "Открыть") if url else "",
         "urgent": bool(body.get("urgent")), "url": url,
         "until": now + max(5.0, min(float(body.get("ttl") or 30), 600.0))}
    notes[n["id"]] = n
    await broadcast(note_msg(n))
    return {"ok": True, "id": n["id"]}


@app.post("/notify/{note_id}/open", dependencies=A)
def note_open(note_id: str):
    if not open_url(notes.get(note_id, {}).get("url", "")):
        raise HTTPException(404)
    return {"ok": True}


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket, t: str = ""):
    if TOKEN and t != TOKEN:
        await ws.close(code=4401)
        return
    await ws.accept()
    clients.add(ws)
    if last:
        await ws.send_text(json.dumps(last))
    await ws.send_text(json.dumps(schedule_msg()))
    for n in notes.values():  # уведомления, которые ещё должны висеть на экране
        if n["until"] > time.monotonic():
            await ws.send_text(json.dumps(note_msg(n)))
    try:
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        pass
    finally:
        clients.discard(ws)


if __name__ == "__main__":
    uvicorn.run(app, host=CFG.get("listen", "0.0.0.0"), port=PORT,
                log_level="warning")
