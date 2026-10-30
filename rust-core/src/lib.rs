//! Núcleo de Musicore: listas, cola aleatoria y persistencia.
//!
//! - Los datos de cada canción viven UNA sola vez, en su lista (`Playlist::songs`).
//! - La cola es solo una permutación de posiciones (`Vec<u32>`) dentro de esa lista.
//! - Se guarda en tres ficheros y cada operación escribe únicamente el que cambia,
//!   desde un hilo propio (la llamada desde Kotlin nunca espera al disco):
//!     library.json  listas y canciones      (solo al crear/editar/borrar)
//!     queue.json    orden de la cola        (solo al empezar, nueva vuelta, editar, borrar)
//!     pos.json      posición en la cola     (en cada siguiente/anterior)

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use rand::seq::SliceRandom;
use rand::Rng;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Mutex, OnceLock};
use std::{fs, thread};
use unicode_normalization::{char::is_combining_mark, UnicodeNormalization};

// ───────────────────────────────────────────── modelo

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Song {
    pub id: String, // identidad estable del archivo (documentId de SAF)
    pub uri: String,
    pub title: String,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct Playlist {
    pub id: u64,
    pub name: String,
    pub folders: Vec<String>,
    /// Ordenadas por título (sin acentos ni mayúsculas). La cola referencia posiciones de aquí.
    pub songs: Vec<Song>,
}

/// `queue` es una permutación de `0..n` (n = canciones de la lista).
/// `queue[..pos]` ya sonaron, `queue[pos]` suena ahora, `queue[pos+1..]` están pendientes.
#[derive(Clone, Debug)]
pub struct Session {
    pub playlist_id: u64,
    pub queue: Vec<u32>,
    pub pos: usize,
}

/// Minúsculas y sin acentos: "Canción" → "cancion".
fn norm(s: &str) -> String {
    s.nfd().filter(|c| !is_combining_mark(*c)).collect::<String>().to_lowercase()
}

fn shuffled(n: usize, avoid_first: Option<u32>) -> Vec<u32> {
    let mut v: Vec<u32> = (0..n as u32).collect();
    let mut rng = rand::thread_rng();
    v.shuffle(&mut rng);
    if v.len() > 1 {
        if let Some(a) = avoid_first {
            if v[0] == a {
                let j = rng.gen_range(1..v.len());
                v.swap(0, j);
            }
        }
    }
    v
}

impl Session {
    pub fn new(playlist_id: u64, n: usize) -> Self {
        Session { playlist_id, queue: shuffled(n, None), pos: 0 }
    }

    /// Cola nueva que empieza por una canción concreta.
    pub fn with_first(playlist_id: u64, n: usize, first: usize) -> Self {
        let mut s = Self::new(playlist_id, n);
        if let Some(i) = s.queue.iter().position(|&x| x as usize == first) {
            s.queue.swap(0, i);
        }
        s
    }

    /// Posición (dentro de la lista) de la canción que suena.
    pub fn current(&self) -> Option<usize> {
        self.queue.get(self.pos).map(|&i| i as usize)
    }

    /// Avanza. Devuelve true si hubo que generar una cola nueva (vuelta completada).
    pub fn next(&mut self, n: usize) -> bool {
        if self.pos + 1 < self.queue.len() {
            self.pos += 1;
            return false;
        }
        let last = self.queue.get(self.pos).copied();
        let q = shuffled(n, last); // la vuelta nueva no empieza por la última sonada
        if q.is_empty() {
            return false;
        }
        self.queue = q;
        self.pos = 0;
        true
    }

    pub fn prev(&mut self) {
        self.pos = self.pos.saturating_sub(1);
    }

    /// Salta a una canción de la cola (las anteriores quedan como ya vistas).
    pub fn play_song(&mut self, song_idx: usize) {
        if let Some(i) = self.queue.iter().position(|&x| x as usize == song_idx) {
            self.pos = i;
        }
    }

    /// Se quitó la canción `removed` de la lista (las posiciones posteriores se desplazan).
    /// Devuelve true si hubo que regenerar la cola (se acabó la vuelta o quedó vacía).
    pub fn remove_song(&mut self, removed: usize, n_after: usize) -> bool {
        let r = removed as u32;
        if let Some(k) = self.queue.iter().position(|&x| x == r) {
            self.queue.remove(k);
            if k < self.pos {
                self.pos -= 1;
            }
        }
        for x in self.queue.iter_mut() {
            if *x > r {
                *x -= 1;
            }
        }
        if self.pos >= self.queue.len() {
            self.queue = shuffled(n_after, None);
            self.pos = 0;
            return true;
        }
        false
    }

    /// La lista cambió (carpetas editadas): se conserva el orden y el historial, se quitan las
    /// canciones que ya no están y las nuevas se reparten al azar entre las pendientes.
    pub fn resync(&mut self, old: &[Song], new: &[Song]) {
        let new_idx: HashMap<&str, u32> =
            new.iter().enumerate().map(|(i, s)| (s.id.as_str(), i as u32)).collect();
        let mut q = Vec::with_capacity(self.queue.len());
        let (mut before, mut cur_present) = (0usize, false);
        for (k, &oi) in self.queue.iter().enumerate() {
            if let Some(&ni) = old.get(oi as usize).and_then(|s| new_idx.get(s.id.as_str())) {
                if k < self.pos {
                    before += 1;
                }
                if k == self.pos {
                    cur_present = true;
                }
                q.push(ni);
            }
        }
        self.queue = q;
        self.pos = before;

        let have: HashSet<u32> = self.queue.iter().copied().collect();
        let mut fresh: Vec<u32> = (0..new.len() as u32).filter(|i| !have.contains(i)).collect();
        let mut rng = rand::thread_rng();
        fresh.shuffle(&mut rng);
        for s in fresh {
            let lo = if cur_present { self.pos + 1 } else { self.pos };
            let i = rng.gen_range(lo..=self.queue.len());
            self.queue.insert(i, s);
        }
        if self.current().is_none() && !new.is_empty() {
            *self = Session::new(self.playlist_id, new.len());
        }
    }
}

// ───────────────────────────────────────────── estado en memoria

#[derive(Default, Clone, Copy)]
struct Dirty {
    library: bool,
    queue: bool,
    pos: bool,
}

#[derive(Default)]
struct State {
    next_id: u64,
    playlists: Vec<Playlist>,
    session: Option<Session>,
    dirty: Dirty,
    /// Sube cuando cambia el CONTENIDO de lo que se muestra (no la posición).
    /// Kotlin lo usa para saber cuándo volver a pedir las filas.
    rev: u64,
}

impl State {
    fn pl(&self, id: u64) -> Option<&Playlist> {
        self.playlists.iter().find(|p| p.id == id)
    }

    fn current_song(&self) -> Option<Song> {
        let s = self.session.as_ref()?;
        self.pl(s.playlist_id)?.songs.get(s.current()?).cloned()
    }

    fn lib_changed(&mut self) {
        self.dirty.library = true;
        self.rev += 1;
    }

    fn queue_changed(&mut self) {
        self.dirty.queue = true;
        self.dirty.pos = true;
        self.rev += 1;
    }

    /// Quita una canción (por id de archivo) de todas las listas y ajusta la cola si hace falta.
    fn remove_everywhere(&mut self, sid: &str) {
        let (mut lib, mut q) = (false, false);
        for p in self.playlists.iter_mut() {
            if let Some(i) = p.songs.iter().position(|s| s.id == sid) {
                p.songs.remove(i);
                lib = true;
                if let Some(s) = self.session.as_mut().filter(|s| s.playlist_id == p.id) {
                    s.remove_song(i, p.songs.len());
                    q = true;
                }
            }
        }
        if lib {
            self.lib_changed();
        }
        if q {
            self.queue_changed();
        }
    }
}

#[derive(Deserialize)]
struct PlaylistIn {
    id: Option<u64>,
    name: String,
    folders: Vec<String>,
    songs: Vec<Song>,
}

fn song_json(s: Option<Song>) -> Value {
    s.map_or(Value::Null, |s| json!(s))
}

fn handle(st: &mut State, method: &str, a: Value) -> Result<Value, String> {
    let id = a["id"].as_u64();
    match method {
        "playlists" => Ok(json!(st
            .playlists
            .iter()
            .map(|p| json!({"id": p.id, "name": p.name, "folders": p.folders, "count": p.songs.len()}))
            .collect::<Vec<_>>())),

        "save_playlist" => {
            let p: PlaylistIn = serde_json::from_value(a).map_err(|e| e.to_string())?;
            let id = p.id.unwrap_or_else(|| {
                st.next_id += 1;
                st.next_id
            });
            let mut songs = p.songs;
            songs.sort_by_cached_key(|s| norm(&s.title));
            let old = st.pl(id).map(|x| x.songs.clone()).unwrap_or_default();
            let pl = Playlist { id, name: p.name, folders: p.folders, songs };
            if let Some(s) = st.session.as_mut().filter(|s| s.playlist_id == id) {
                s.resync(&old, &pl.songs);
                st.dirty.queue = true;
                st.dirty.pos = true;
            }
            match st.playlists.iter_mut().find(|x| x.id == id) {
                Some(e) => *e = pl,
                None => st.playlists.push(pl),
            }
            st.lib_changed();
            Ok(json!(id))
        }

        "delete_playlist" => {
            let id = id.ok_or("falta id")?;
            st.playlists.retain(|p| p.id != id);
            if st.session.as_ref().map_or(false, |s| s.playlist_id == id) {
                st.session = None;
                st.dirty.queue = true;
                st.dirty.pos = true;
            }
            st.lib_changed();
            Ok(Value::Null)
        }

        "start" => {
            let id = id.ok_or("falta id")?;
            let n = st.pl(id).ok_or("lista inexistente")?.songs.len();
            st.session = Some(Session::new(id, n));
            st.queue_changed();
            Ok(song_json(st.current_song()))
        }

        "play_song" => {
            let id = id.ok_or("falta id")?;
            let sid = a["song_id"].as_str().ok_or("falta song_id")?.to_string();
            let (n, idx) = {
                let pl = st.pl(id).ok_or("lista inexistente")?;
                (pl.songs.len(), pl.songs.iter().position(|s| s.id == sid).ok_or("canción inexistente")?)
            };
            if st.session.as_ref().map_or(true, |s| s.playlist_id != id) {
                st.session = Some(Session::with_first(id, n, idx));
                st.queue_changed();
            } else if let Some(s) = st.session.as_mut() {
                let before = s.pos;
                s.play_song(idx);
                if s.pos != before {
                    st.dirty.pos = true;
                }
            }
            Ok(song_json(st.current_song()))
        }

        // ── Lectura mínima para pintar la pantalla de una lista ──────────────────────
        // queue_info: solo tamaño, posición y revisión (unos 50 bytes).
        "queue_info" => {
            let id = id.ok_or("falta id")?;
            let pl = st.pl(id).ok_or("lista inexistente")?;
            Ok(match st.session.as_ref().filter(|s| s.playlist_id == id) {
                Some(s) => json!({"active": true, "len": s.queue.len(), "pos": s.pos, "rev": st.rev}),
                None => json!({"active": false, "len": pl.songs.len(), "pos": 0, "rev": st.rev}),
            })
        }

        // item_at: UNA canción, la de la fila `index` (en orden de cola si la lista está activa,
        // alfabético si no). Kotlin solo la pide para las filas que se ven.
        "item_at" => {
            let id = id.ok_or("falta id")?;
            let idx = a["index"].as_u64().ok_or("falta index")? as usize;
            let pl = st.pl(id).ok_or("lista inexistente")?;
            let real = match st.session.as_ref().filter(|s| s.playlist_id == id) {
                Some(s) => s.queue.get(idx).map(|&i| i as usize),
                None => Some(idx),
            };
            Ok(song_json(real.and_then(|i| pl.songs.get(i)).cloned()))
        }

        // search: solo los números de fila que coinciden (sin acentos ni mayúsculas).
        "search" => {
            let id = id.ok_or("falta id")?;
            let q = norm(a["q"].as_str().unwrap_or("").trim());
            let pl = st.pl(id).ok_or("lista inexistente")?;
            let hit = |i: usize| pl.songs.get(i).map_or(false, |s| norm(&s.title).contains(&q));
            let rows: Vec<usize> = match st.session.as_ref().filter(|s| s.playlist_id == id) {
                Some(s) => s.queue.iter().enumerate().filter(|&(_, &qi)| hit(qi as usize)).map(|(k, _)| k).collect(),
                None => (0..pl.songs.len()).filter(|&i| hit(i)).collect(),
            };
            Ok(json!(rows))
        }

        "active" => Ok(st.session.as_ref().map_or(Value::Null, |s| json!(s.playlist_id))),
        "current" => Ok(song_json(st.current_song())),

        "next" | "prev" => {
            let (queue_new, moved, cur) = {
                let State { playlists, session, .. } = &mut *st;
                let s = session.as_mut().ok_or("sin lista activa")?;
                let pl = playlists.iter().find(|p| p.id == s.playlist_id).ok_or("lista inexistente")?;
                let before = s.pos;
                let queue_new = if method == "next" {
                    s.next(pl.songs.len())
                } else {
                    s.prev();
                    false
                };
                (queue_new, s.pos != before, s.current().and_then(|i| pl.songs.get(i)).cloned())
            };
            if queue_new {
                st.queue_changed();
            } else if moved {
                st.dirty.pos = true; // lo único que cambia: un número
            }
            Ok(song_json(cur))
        }

        "remove_current" => {
            let gone = st.current_song().ok_or("nada sonando")?;
            st.remove_everywhere(&gone.id);
            Ok(song_json(st.current_song()))
        }

        "remove_song" => {
            let sid = a["song_id"].as_str().ok_or("falta song_id")?.to_string();
            st.remove_everywhere(&sid);
            Ok(Value::Null)
        }

        _ => Err(format!("método desconocido: {method}")),
    }
}

// ───────────────────────────────────────────── persistencia (hilo propio)

enum Job {
    Write(PathBuf, Vec<u8>),
    Remove(PathBuf),
    #[allow(dead_code)] // lo usan los tests para esperar a que se escriba todo
    Barrier(Sender<()>),
}

impl Job {
    fn path(&self) -> Option<&Path> {
        match self {
            Job::Write(p, _) | Job::Remove(p) => Some(p),
            Job::Barrier(_) => None,
        }
    }
}

static WRITER: OnceLock<Sender<Job>> = OnceLock::new();

fn writer() -> &'static Sender<Job> {
    WRITER.get_or_init(|| {
        let (tx, rx) = channel();
        thread::spawn(move || write_loop(rx));
        tx
    })
}

fn write_loop(rx: Receiver<Job>) {
    while let Ok(first) = rx.recv() {
        let mut jobs = vec![first];
        while let Ok(j) = rx.try_recv() {
            jobs.push(j);
        }
        // Si se acumularon varias escrituras al mismo fichero, solo importa la última.
        let mut seen: HashSet<PathBuf> = HashSet::new();
        let mut out = Vec::new();
        for j in jobs.into_iter().rev() {
            let keep = match j.path() {
                Some(p) => seen.insert(p.to_path_buf()),
                None => true,
            };
            if keep {
                out.push(j);
            }
        }
        out.reverse();
        for j in out {
            match j {
                Job::Write(p, bytes) => {
                    let tmp = p.with_extension("tmp");
                    if fs::write(&tmp, &bytes).is_ok() {
                        let _ = fs::rename(&tmp, &p);
                    }
                }
                Job::Remove(p) => {
                    let _ = fs::remove_file(p);
                }
                Job::Barrier(tx) => {
                    let _ = tx.send(());
                }
            }
        }
    }
}

/// Espera a que se haya escrito todo lo pendiente (para tests).
#[cfg(test)]
fn flush() {
    let (tx, rx) = channel();
    let _ = writer().send(Job::Barrier(tx));
    let _ = rx.recv();
}

#[derive(Serialize)]
struct LibraryRef<'a> {
    next_id: u64,
    playlists: &'a [Playlist],
}
#[derive(Serialize)]
struct QueueRef<'a> {
    playlist_id: u64,
    queue: &'a [u32],
}
#[derive(Deserialize)]
struct LibraryFile {
    #[serde(default)]
    next_id: u64,
    #[serde(default)]
    playlists: Vec<Playlist>,
}
#[derive(Deserialize)]
struct QueueFile {
    playlist_id: u64,
    queue: Vec<u32>,
}
#[derive(Deserialize)]
struct PosFile {
    pos: usize,
}

/// Envía al hilo de escritura SOLO lo que cambió desde la última vez.
fn persist(st: &mut State, dir: &Path) {
    let d = std::mem::take(&mut st.dirty);
    let tx = writer();
    if d.library {
        if let Ok(b) = serde_json::to_vec(&LibraryRef { next_id: st.next_id, playlists: &st.playlists }) {
            let _ = tx.send(Job::Write(dir.join("library.json"), b));
        }
    }
    if d.queue {
        let path = dir.join("queue.json");
        match &st.session {
            Some(s) => {
                if let Ok(b) = serde_json::to_vec(&QueueRef { playlist_id: s.playlist_id, queue: &s.queue }) {
                    let _ = tx.send(Job::Write(path, b));
                }
            }
            None => {
                let _ = tx.send(Job::Remove(path));
            }
        }
    }
    if d.pos {
        let path = dir.join("pos.json");
        match &st.session {
            Some(s) => {
                let _ = tx.send(Job::Write(path, format!(r#"{{"pos":{}}}"#, s.pos).into_bytes()));
            }
            None => {
                let _ = tx.send(Job::Remove(path));
            }
        }
    }
}

// Formato anterior (un único state.json con las canciones duplicadas dentro de la cola)
#[derive(Deserialize)]
struct OldSession {
    playlist_id: u64,
    #[serde(default)]
    queue: Vec<Song>,
    #[serde(default)]
    pos: usize,
    #[serde(default)]
    pool: Vec<Song>,
}
#[derive(Deserialize, Default)]
struct OldState {
    #[serde(default)]
    next_id: u64,
    #[serde(default)]
    playlists: Vec<Playlist>,
    session: Option<OldSession>,
}

fn from_legacy(old: OldState) -> State {
    let mut st = State { next_id: old.next_id, playlists: old.playlists, ..Default::default() };
    for p in st.playlists.iter_mut() {
        p.songs.sort_by_cached_key(|s| norm(&s.title));
    }
    if let Some(o) = old.session {
        if let Some(p) = st.playlists.iter().find(|p| p.id == o.playlist_id) {
            let idx: HashMap<&str, u32> =
                p.songs.iter().enumerate().map(|(i, s)| (s.id.as_str(), i as u32)).collect();
            let mut seen: HashSet<u32> = HashSet::new();
            let (mut q, mut before) = (Vec::new(), 0usize);
            for (k, s) in o.queue.iter().enumerate() {
                if let Some(&i) = idx.get(s.id.as_str()) {
                    if seen.insert(i) {
                        if k < o.pos {
                            before += 1;
                        }
                        q.push(i);
                    }
                }
            }
            let mut rest: Vec<u32> = Vec::new();
            for s in &o.pool {
                if let Some(&i) = idx.get(s.id.as_str()) {
                    if seen.insert(i) {
                        rest.push(i);
                    }
                }
            }
            rest.extend((0..p.songs.len() as u32).filter(|i| !seen.contains(i)));
            rest.shuffle(&mut rand::thread_rng());
            q.extend(rest);
            if !q.is_empty() {
                let pos = before.min(q.len() - 1);
                st.session = Some(Session { playlist_id: o.playlist_id, queue: q, pos });
            }
        }
    }
    st
}

fn load_dir(dir: &Path) -> State {
    let read = |n: &str| fs::read_to_string(dir.join(n)).ok();
    let mut st = State::default();
    if let Some(t) = read("library.json") {
        if let Ok(l) = serde_json::from_str::<LibraryFile>(&t) {
            st.next_id = l.next_id;
            st.playlists = l.playlists;
        }
    } else if let Some(t) = read("state.json") {
        if let Ok(old) = serde_json::from_str::<OldState>(&t) {
            st = from_legacy(old);
            st.dirty = Dirty { library: true, queue: true, pos: true }; // se reescribe en formato nuevo
            return st;
        }
    }
    if let Some(qf) = read("queue.json").and_then(|t| serde_json::from_str::<QueueFile>(&t).ok()) {
        let n = st.pl(qf.playlist_id).map(|p| p.songs.len());
        let valid = n == Some(qf.queue.len()) && qf.queue.iter().all(|&i| (i as usize) < qf.queue.len());
        if valid {
            let pos = read("pos.json").and_then(|t| serde_json::from_str::<PosFile>(&t).ok()).map_or(0, |p| p.pos);
            let pos = if pos < qf.queue.len() { pos } else { 0 };
            st.session = Some(Session { playlist_id: qf.playlist_id, queue: qf.queue, pos });
        }
    }
    st
}

// ───────────────────────────────────────────── JNI

static STATE: Mutex<Option<(PathBuf, State)>> = Mutex::new(None);

fn call(method: &str, args: &str) -> String {
    let mut g = STATE.lock().unwrap_or_else(|e| e.into_inner());
    let Some((dir, st)) = g.as_mut() else {
        return r#"{"ok":false,"error":"no inicializado"}"#.into();
    };
    let args: Value = serde_json::from_str(args).unwrap_or(Value::Null);
    let res = handle(st, method, args);
    persist(st, dir);
    match res {
        Ok(d) => json!({"ok": true, "data": d}),
        Err(e) => json!({"ok": false, "error": e}),
    }
    .to_string()
}

#[no_mangle]
pub extern "system" fn Java_com_example_musicore_RustCore_nativeInit(
    mut env: JNIEnv,
    _c: JClass,
    dir: JString,
) {
    let dir: String = env.get_string(&dir).map(|s| s.into()).unwrap_or_default();
    let dir = PathBuf::from(dir);
    let mut st = load_dir(&dir);
    let migrated = st.dirty.library;
    persist(&mut st, &dir);
    if migrated {
        let _ = writer().send(Job::Remove(dir.join("state.json")));
    }
    *STATE.lock().unwrap_or_else(|e| e.into_inner()) = Some((dir, st));
}

#[no_mangle]
pub extern "system" fn Java_com_example_musicore_RustCore_nativeCall(
    mut env: JNIEnv,
    _c: JClass,
    method: JString,
    args: JString,
) -> jstring {
    let m: String = env.get_string(&method).map(|s| s.into()).unwrap_or_default();
    let a: String = env.get_string(&args).map(|s| s.into()).unwrap_or_default();
    env.new_string(call(&m, &a)).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

// ───────────────────────────────────────────── tests

#[cfg(test)]
mod tests {
    use super::*;

    fn songs(n: usize) -> Vec<Song> {
        (0..n).map(|i| Song { id: i.to_string(), uri: format!("u{i}"), title: format!("t{i}") }).collect()
    }

    fn mk(n: usize) -> (State, u64) {
        let mut st = State::default();
        let sg: Vec<Value> = songs(n).iter().map(|s| json!(s)).collect();
        let id = handle(&mut st, "save_playlist", json!({"name": "x", "folders": [], "songs": sg}))
            .unwrap()
            .as_u64()
            .unwrap();
        (st, id)
    }

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("musicore-test-{}-{name}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn queue_is_a_full_permutation_of_references() {
        let s = Session::new(1, 50);
        let set: HashSet<u32> = s.queue.iter().copied().collect();
        assert_eq!(s.queue.len(), 50);
        assert_eq!(set.len(), 50);
    }

    #[test]
    fn new_lap_does_not_start_with_last_song() {
        for _ in 0..200 {
            let mut s = Session::new(1, 20);
            let mut regenerated = false;
            for _ in 0..19 {
                regenerated |= s.next(20);
            }
            assert!(!regenerated);
            let last = s.current().unwrap();
            assert!(s.next(20)); // aquí sí se genera la vuelta nueva
            assert_ne!(s.current().unwrap(), last);
            assert_eq!(s.pos, 0);
        }
    }

    #[test]
    fn random_walk_never_repeats_on_next() {
        let mut s = Session::new(1, 30);
        let mut rng = rand::thread_rng();
        for _ in 0..5000 {
            let before = s.current().unwrap();
            if rng.gen_bool(0.7) {
                s.next(30);
                assert_ne!(before, s.current().unwrap(), "next devolvió la misma canción");
            } else {
                s.prev();
            }
            assert_eq!(s.queue.len(), 30);
        }
    }

    #[test]
    fn prev_then_next_replays_queue() {
        let mut s = Session::new(1, 10);
        let a = s.current().unwrap();
        s.next(10);
        let b = s.current().unwrap();
        s.prev();
        assert_eq!(s.current().unwrap(), a);
        s.next(10);
        assert_eq!(s.current().unwrap(), b);
    }

    #[test]
    fn play_song_jumps_and_with_first() {
        let mut s = Session::with_first(1, 10, 7);
        assert_eq!(s.current().unwrap(), 7);
        let target = s.queue[5] as usize;
        s.play_song(target);
        assert_eq!((s.pos, s.current().unwrap()), (5, target));
    }

    #[test]
    fn remove_song_keeps_current_identity() {
        let mut names = songs(10);
        let mut s = Session::new(1, 10);
        for _ in 0..4 {
            s.next(10);
        }
        let cur_id = names[s.current().unwrap()].id.clone();
        for k in [1usize, 6] {
            let r = s.queue[k] as usize; // una ya sonada y otra pendiente
            names.remove(r);
            assert!(!s.remove_song(r, names.len()));
            assert_eq!(names[s.current().unwrap()].id, cur_id);
        }
        assert_eq!(s.queue.len(), 8);
        assert_eq!(s.queue.iter().copied().collect::<HashSet<_>>().len(), 8);
    }

    #[test]
    fn removing_all_songs_one_by_one_ends_empty() {
        let mut n = 5;
        let mut s = Session::new(1, n);
        while let Some(c) = s.current() {
            n -= 1;
            s.remove_song(c, n);
        }
        assert_eq!(n, 0);
    }

    #[test]
    fn resync_keeps_history_drops_removed_adds_new_upcoming() {
        let old = songs(10);
        let mut s = Session::new(1, 10);
        s.next(10);
        s.next(10); // pos = 2
        let cur = old[s.current().unwrap()].id.clone();
        let played: Vec<String> = s.queue[..2].iter().map(|&i| old[i as usize].id.clone()).collect();
        let victim = old[s.queue[5] as usize].id.clone();
        let mut new = songs(12); // añade "10" y "11"
        new.retain(|x| x.id != victim);
        s.resync(&old, &new);
        assert_eq!(new[s.current().unwrap()].id, cur);
        assert_eq!(s.pos, 2);
        let played_now: Vec<String> = s.queue[..2].iter().map(|&i| new[i as usize].id.clone()).collect();
        assert_eq!(played_now, played);
        assert_eq!(s.queue.len(), 11);
        let at = |id: &str| s.queue.iter().position(|&i| new[i as usize].id == id);
        assert!(at(&victim).is_none());
        assert!(at("10").unwrap() > 2 && at("11").unwrap() > 2);
    }

    #[test]
    fn screen_queries_are_tiny_and_correct() {
        let (mut st, id) = mk(30);
        // sin sesión: filas = canciones ordenadas
        let info = handle(&mut st, "queue_info", json!({"id": id})).unwrap();
        assert_eq!((info["active"].as_bool(), info["len"].as_u64()), (Some(false), Some(30)));
        let hits = handle(&mut st, "search", json!({"id": id, "q": "T2"})).unwrap();
        assert_eq!(hits.as_array().unwrap().len(), 11); // t2, t20..t29
        // con sesión: filas = orden de la cola
        handle(&mut st, "start", json!({"id": id})).unwrap();
        let info = handle(&mut st, "queue_info", json!({"id": id})).unwrap();
        assert!(info.to_string().len() < 80, "queue_info debe ser diminuto: {info}");
        let cur = handle(&mut st, "current", Value::Null).unwrap();
        assert_eq!(handle(&mut st, "item_at", json!({"id": id, "index": 0})).unwrap(), cur);
        let hits = handle(&mut st, "search", json!({"id": id, "q": "t1"})).unwrap();
        assert_eq!(hits.as_array().unwrap().len(), 11); // t1, t10..t19
        // las filas devueltas coinciden de verdad
        for r in hits.as_array().unwrap() {
            let s = handle(&mut st, "item_at", json!({"id": id, "index": r})).unwrap();
            assert!(s["title"].as_str().unwrap().contains("t1"));
        }
    }

    #[test]
    fn search_ignores_accents_and_case() {
        let mut st = State::default();
        let sg = json!([{"id":"a","uri":"u","title":"Canción del Año"},{"id":"b","uri":"u","title":"Otra"}]);
        let id = handle(&mut st, "save_playlist", json!({"name":"x","folders":[],"songs":sg})).unwrap();
        let r = handle(&mut st, "search", json!({"id": id, "q": "CANCION ano"})).unwrap();
        assert_eq!(r.as_array().unwrap().len(), 0); // "cancion ano" no es subcadena
        let r = handle(&mut st, "search", json!({"id": id, "q": "cancion del ano"})).unwrap();
        assert_eq!(r.as_array().unwrap().len(), 1);
    }

    #[test]
    fn next_marks_only_position_dirty() {
        let (mut st, id) = mk(10);
        handle(&mut st, "start", json!({"id": id})).unwrap();
        st.dirty = Dirty::default();
        handle(&mut st, "next", Value::Null).unwrap();
        assert!(st.dirty.pos && !st.dirty.queue && !st.dirty.library);
        let rev = st.rev;
        handle(&mut st, "next", Value::Null).unwrap();
        assert_eq!(st.rev, rev, "avanzar no cambia el contenido que se muestra");
        // al terminar la vuelta sí cambia la cola
        let mut queue_dirty = false;
        for _ in 0..10 {
            st.dirty = Dirty::default();
            handle(&mut st, "next", Value::Null).unwrap();
            queue_dirty |= st.dirty.queue;
        }
        assert!(queue_dirty);
    }

    #[test]
    fn disk_roundtrip_and_next_rewrites_only_pos_file() {
        let dir = tmp("roundtrip");
        let (mut st, id) = mk(10);
        handle(&mut st, "start", json!({"id": id})).unwrap();
        persist(&mut st, &dir);
        flush();
        let loaded = load_dir(&dir);
        assert_eq!(loaded.playlists[0].songs.len(), 10);
        let (a, b) = (st.session.as_ref().unwrap(), loaded.session.as_ref().unwrap());
        assert_eq!((&a.queue, a.pos), (&b.queue, b.pos));

        // marcamos los otros ficheros: si "siguiente" los reescribiera, se perdería la marca
        fs::write(dir.join("library.json"), "SENTINEL").unwrap();
        fs::write(dir.join("queue.json"), "SENTINEL").unwrap();
        handle(&mut st, "next", Value::Null).unwrap();
        persist(&mut st, &dir);
        flush();
        assert_eq!(fs::read_to_string(dir.join("library.json")).unwrap(), "SENTINEL");
        assert_eq!(fs::read_to_string(dir.join("queue.json")).unwrap(), "SENTINEL");
        assert_eq!(fs::read_to_string(dir.join("pos.json")).unwrap(), r#"{"pos":1}"#);
    }

    #[test]
    fn queue_file_stores_only_numbers() {
        let dir = tmp("numbers");
        let (mut st, id) = mk(400);
        handle(&mut st, "start", json!({"id": id})).unwrap();
        persist(&mut st, &dir);
        flush();
        let q = fs::read_to_string(dir.join("queue.json")).unwrap();
        assert!(!q.contains("uri") && !q.contains("title"));
        assert!(q.len() < 3000, "cola de 400 canciones = {} bytes", q.len());
    }

    #[test]
    fn legacy_state_json_is_migrated() {
        let dir = tmp("legacy");
        let s = |i: usize| json!({"id": i.to_string(), "uri": format!("u{i}"), "title": format!("t{i}")});
        let old = json!({
            "next_id": 1,
            "playlists": [{"id":1,"name":"x","folders":[],"songs":[s(0),s(1),s(2),s(3),s(4)]}],
            "session": {"playlist_id":1,"queue":[s(3),s(1),s(4)],"pos":1,"pool":[s(0),s(2)]}
        });
        fs::write(dir.join("state.json"), old.to_string()).unwrap();
        let st = load_dir(&dir);
        assert!(st.dirty.library); // se reescribirá en el formato nuevo
        let sess = st.session.as_ref().unwrap();
        let ids: Vec<&str> = sess.queue.iter().map(|&i| st.playlists[0].songs[i as usize].id.as_str()).collect();
        assert_eq!(&ids[..3], &["3", "1", "4"]);
        assert_eq!(sess.queue.len(), 5);
        assert_eq!(sess.pos, 1);
    }

    #[test]
    fn inconsistent_saved_queue_is_discarded_not_misapplied() {
        let dir = tmp("inconsistent");
        let (mut st, id) = mk(10);
        handle(&mut st, "start", json!({"id": id})).unwrap();
        persist(&mut st, &dir);
        flush();
        // la lista cambió pero la cola guardada es de otro tamaño
        let mut lib: Value = serde_json::from_str(&fs::read_to_string(dir.join("library.json")).unwrap()).unwrap();
        lib["playlists"][0]["songs"].as_array_mut().unwrap().pop();
        fs::write(dir.join("library.json"), lib.to_string()).unwrap();
        assert!(load_dir(&dir).session.is_none());
    }
}
