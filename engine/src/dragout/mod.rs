//! Drag out to Windows Explorer / Desktop with VIRTUAL FILES (OLE).
//!
//! The app does not copy anything before the drop: it offers Windows a list of files (`FileGroupDescriptorW`) and
//! hands out each file's content as an `IStream` (`FileContents`) that Explorer reads while it copies. Explorer owns
//! the cursor, the progress dialog and the cancel button; the app stays free whatever the size.
//!
//! The list comes from the same planner as a download (`transfer::plan_remote`: same limits, same safe names) and the
//! bytes from `Provider::open_read`, read sequentially. Nothing is written to disk by the app.
#![allow(non_snake_case)]

use crate::engine::Engine;
use crate::error::Code;
use crate::pathguard::numbered;
use crate::provider::{Progress, Provider, Reader};
use crate::transfer::{plan_remote, PlanItem};
use crate::types::EntryKind;
use std::collections::{HashMap, HashSet};
use std::ffi::c_void;
use std::mem::ManuallyDrop;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};
use tokio::io::AsyncReadExt;
use tokio_util::sync::CancellationToken;
use windows::core::{implement, w, Error, Result, BOOL, HRESULT};
use windows::Win32::Foundation::{
    DATA_S_SAMEFORMATETC, DRAGDROP_S_CANCEL, DRAGDROP_S_DROP, DRAGDROP_S_USEDEFAULTCURSORS, DV_E_FORMATETC, DV_E_LINDEX, E_ABORT, E_FAIL, E_NOTIMPL, E_OUTOFMEMORY,
    FILETIME, HGLOBAL, OLE_E_ADVISENOTSUPPORTED, STG_E_INVALIDFUNCTION, S_FALSE, S_OK,
};
use windows::Win32::System::Com::{
    IAdviseSink, IDataObject, IDataObject_Impl, IEnumFORMATETC, IEnumSTATDATA, ISequentialStream_Impl, IStream, IStream_Impl, DATADIR_GET, DVASPECT_CONTENT,
    FORMATETC, STATFLAG, STATSTG, STGMEDIUM, STGMEDIUM_0, STGTY_STREAM, STREAM_SEEK, STREAM_SEEK_CUR, STREAM_SEEK_END, STREAM_SEEK_SET, TYMED_HGLOBAL, TYMED_ISTREAM,
};
use windows::Win32::System::DataExchange::RegisterClipboardFormatW;
use windows::Win32::System::Memory::{GlobalAlloc, GlobalLock, GlobalUnlock, GMEM_MOVEABLE, GMEM_ZEROINIT};
use windows::Win32::System::Com::IBindCtx;
use windows::Win32::System::Ole::{
    DoDragDrop, IDropSource, IDropSource_Impl, OleInitialize, OleUninitialize, DROPEFFECT, DROPEFFECT_COPY,
    DROPEFFECT_NONE,
};
use windows::Win32::System::SystemServices::MK_LBUTTON;
use windows::Win32::System::Threading::{AttachThreadInput, GetCurrentThreadId};
use windows::Win32::UI::Shell::{IDataObjectAsyncCapability, IDataObjectAsyncCapability_Impl, SHCreateStdEnumFmtEtc, FD_ATTRIBUTES, FD_FILESIZE, FD_PROGRESSUI, FD_WRITESTIME, FILEDESCRIPTORW};
use windows::Win32::UI::WindowsAndMessaging::{
    DispatchMessageW, GetForegroundWindow, GetWindowThreadProcessId, MsgWaitForMultipleObjects, PeekMessageW, TranslateMessage, MSG, PM_REMOVE, QS_ALLINPUT,
};

/// Result codes of [`run_drag_session`] below zero (zero and above are the drop effect reported by Windows).
pub const DRAG_PANIC: i32 = -1;
pub const DRAG_NO_SESSION: i32 = -2;
pub const DRAG_PLAN_FAILED: i32 = -3;
pub const DRAG_TOO_BIG: i32 = -4;

/// A file name of the descriptor holds 259 UTF-16 units: longer relative paths are shortened or left out.
const MAX_DESCRIPTOR_PATH: usize = 259;
/// How long Explorer may wait for the list while the folders are still being enumerated.
const PLAN_WAIT: Duration = Duration::from_secs(180);
/// A server that sends nothing for this long fails the copy instead of hanging it.
const READ_TIMEOUT: Duration = Duration::from_secs(60);

/// Diagnostic trail in the temp folder (`nextdata-drag.log`): no names and no server paths, only what happened and when.
pub(crate) fn trace(msg: &str) {
    use std::io::Write;
    let path = std::env::temp_dir().join("nextdata-drag.log");
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(f, "[native] {msg}");
    }
}

// ------------------------------------------------------------------ the list of files

/// One virtual file or folder as offered to Explorer.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct VFile {
    /// Remote ref (server side).
    pub remote: String,
    /// Relative path with `\` separators, already safe for Windows.
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
    pub mtime_ms: Option<i64>,
}

fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

/// Fits a leaf name into `room` UTF-16 units keeping its extension, or None if there is no sensible way.
fn shorten_leaf(leaf: &str, room: usize) -> Option<String> {
    if utf16_len(leaf) <= room {
        return Some(leaf.to_string());
    }
    let (stem, ext) = match leaf.rfind('.') {
        Some(i) if i > 0 && utf16_len(&leaf[i..]) <= 16 => (&leaf[..i], &leaf[i..]),
        _ => (leaf, ""),
    };
    let mut stem: String = stem.to_string();
    while !stem.is_empty() && utf16_len(&stem) + utf16_len(ext) > room {
        stem.pop();
    }
    while stem.ends_with('.') || stem.ends_with(' ') {
        stem.pop();
    }
    if stem.is_empty() {
        return None;
    }
    Some(format!("{stem}{ext}"))
}

/// Turns the planner's items into the final list: names unique per folder IGNORING CASE (Windows does), paths short
/// enough for the descriptor. Parents always come before their children (the planner guarantees it).
pub(crate) fn flatten(items: Vec<PlanItem>) -> Vec<VFile> {
    let mut out: Vec<VFile> = Vec::with_capacity(items.len());
    let mut taken: HashSet<String> = HashSet::new();
    // original relative path -> final components (so a renamed folder carries its children along)
    let mut finals: HashMap<Vec<String>, Vec<String>> = HashMap::new();
    for it in items {
        let is_dir = match it.kind {
            EntryKind::File => false,
            EntryKind::Dir => true,
            _ => continue,
        };
        let Some(leaf) = it.rel.last() else { continue };
        let parent: Vec<String> = if it.rel.len() > 1 {
            match finals.get(&it.rel[..it.rel.len() - 1]) {
                Some(p) => p.clone(),
                None => continue, // its folder was left out
            }
        } else {
            vec![]
        };
        let prefix_len: usize = parent.iter().map(|c| utf16_len(c) + 1).sum();
        let Some(room) = MAX_DESCRIPTOR_PATH.checked_sub(prefix_len).filter(|r| *r >= 4) else { continue };
        let Some(base) = shorten_leaf(leaf, room) else { continue };
        let mut name = base.clone();
        let mut n = 0u32;
        let key = |nm: &str| format!("{}\\{}", parent.join("\\").to_lowercase(), nm.to_lowercase());
        while !taken.insert(key(&name)) {
            n += 1;
            if n > 10_000 {
                break;
            }
            name = shorten_leaf(&numbered(&base, n), room).unwrap_or_else(|| numbered(&base, n));
        }
        if n > 10_000 {
            continue;
        }
        let mut comps = parent;
        comps.push(name);
        out.push(VFile { remote: it.remote, name: comps.join("\\"), is_dir, size: it.size, mtime_ms: it.mtime_ms });
        finals.insert(it.rel, comps);
    }
    out
}

// ------------------------------------------------------------------ shared state of one drag

type PlanResult = std::result::Result<Arc<Vec<VFile>>, i32>;

struct DragShared {
    rt: tokio::runtime::Handle,
    provider: Arc<dyn Provider>,
    cancel: CancellationToken,
    plan: Mutex<Option<PlanResult>>,
    plan_ready: Condvar,
    served: AtomicU64,
    /// Explorer copies on its own thread (asynchronous operation): set when it starts / ends reading.
    op_started: std::sync::atomic::AtomicBool,
    op_done: std::sync::atomic::AtomicBool,
    /// total bytes of the files offered (known once the list is ready)
    total: AtomicU64,
    /// the drag itself is over (dropped or cancelled); Explorer may still be copying
    dropped: std::sync::atomic::AtomicBool,
}

impl DragShared {
    fn new(rt: tokio::runtime::Handle, provider: Arc<dyn Provider>) -> Arc<DragShared> {
        Arc::new(DragShared { rt, provider, cancel: CancellationToken::new(), plan: Mutex::new(None), plan_ready: Condvar::new(), served: AtomicU64::new(0), op_started: Default::default(), op_done: Default::default(), total: AtomicU64::new(0), dropped: Default::default() })
    }

    fn set_plan(&self, r: PlanResult) {
        *self.plan.lock().unwrap() = Some(r);
        self.plan_ready.notify_all();
    }

    /// The list of files, waiting (without holding up anything but the caller) until the enumeration is done.
    fn wait_plan(&self) -> PlanResult {
        let deadline = Instant::now() + PLAN_WAIT;
        let mut g = self.plan.lock().unwrap();
        loop {
            if let Some(r) = g.as_ref() {
                return r.clone();
            }
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() || self.cancel.is_cancelled() {
                return Err(DRAG_PLAN_FAILED);
            }
            g = self.plan_ready.wait_timeout(g, left.min(Duration::from_millis(500))).unwrap().0;
        }
    }

    /// Enumerates the selection in the background, so it is usually ready by the time the cursor reaches Explorer.
    fn start_planning(self: &Arc<Self>, refs: Vec<String>) {
        let me = self.clone();
        self.rt.spawn(async move {
            let pr = Progress { cb: Arc::new(|_| {}), cancel: me.cancel.clone() };
            let mut all: Vec<PlanItem> = vec![];
            for r in &refs {
                match plan_remote(&*me.provider, r, &pr).await {
                    Ok((items, _)) => all.extend(items),
                    Err(e) => {
                        trace(&format!("planning failed: {:?}", e.code));
                        me.set_plan(Err(if e.code == Code::LimitExceeded { DRAG_TOO_BIG } else { DRAG_PLAN_FAILED }));
                        return;
                    }
                }
            }
            let list = flatten(all);
            me.total.store(list.iter().map(|f| f.size).sum(), Ordering::Relaxed);
            trace(&format!("list ready: {} items", list.len()));
            me.set_plan(Ok(Arc::new(list)));
        });
    }
}

// ------------------------------------------------------------------ content of one file

struct StreamState {
    reader: Option<Reader>,
    /// where the reader is
    rpos: u64,
    /// where the consumer is (after a rewind this differs from `rpos`: the file is then reopened)
    pos: u64,
}

pub(crate) struct RemoteFile {
    shared: Arc<DragShared>,
    remote: String,
    size: u64,
    state: Mutex<StreamState>,
}

impl RemoteFile {
    fn new(shared: Arc<DragShared>, remote: String, size: u64) -> RemoteFile {
        RemoteFile { shared, remote, size, state: Mutex::new(StreamState { reader: None, rpos: 0, pos: 0 }) }
    }

    /// Fills `out` (as far as the file goes). Ok(0) only at the end of the file; a server that ends early is an error,
    /// so Explorer never keeps a silently truncated copy.
    fn read_into(&self, out: &mut [u8]) -> std::result::Result<usize, ()> {
        let mut g = self.state.lock().unwrap();
        let StreamState { reader, rpos, pos } = &mut *g;
        if *pos >= self.size {
            return Ok(0);
        }
        let want = (self.size - *pos).min(out.len() as u64) as usize;
        let cancel = self.shared.cancel.clone();
        if reader.is_none() || *rpos != *pos {
            *reader = None;
            let opened = self.shared.rt.block_on(async {
                tokio::select! {
                    biased;
                    _ = cancel.cancelled() => None,
                    r = tokio::time::timeout(READ_TIMEOUT, self.shared.provider.open_read(&self.remote, *pos)) => r.ok().and_then(|r| r.ok()),
                }
            });
            *reader = Some(opened.ok_or(())?);
            *rpos = *pos;
        }
        let rd = reader.as_mut().unwrap();
        let mut got = 0usize;
        while got < want {
            let n = self.shared.rt.block_on(async {
                tokio::select! {
                    biased;
                    _ = cancel.cancelled() => None,
                    r = tokio::time::timeout(READ_TIMEOUT, rd.read(&mut out[got..want])) => r.ok().and_then(|r| r.ok()),
                }
            });
            let n = n.ok_or(())?;
            if n == 0 {
                break;
            }
            got += n;
            *pos += n as u64;
            *rpos += n as u64;
        }
        self.shared.served.fetch_add(got as u64, Ordering::Relaxed);
        if got == 0 {
            return Err(());
        }
        Ok(got)
    }

    fn seek_to(&self, delta: i64, origin: i32) -> Option<u64> {
        let mut g = self.state.lock().unwrap();
        let base = match origin {
            0 => 0i64,
            1 => g.pos as i64,
            2 => self.size as i64,
            _ => return None,
        };
        g.pos = (base.checked_add(delta)?).clamp(0, self.size as i64) as u64;
        Some(g.pos)
    }
}

#[implement(IStream)]
struct RemoteStream {
    file: RemoteFile,
}

impl ISequentialStream_Impl for RemoteStream_Impl {
    fn Read(&self, pv: *mut c_void, cb: u32, pcbread: *mut u32) -> HRESULT {
        let out = unsafe { std::slice::from_raw_parts_mut(pv as *mut u8, cb as usize) };
        match self.file.read_into(out) {
            Ok(n) => {
                if !pcbread.is_null() {
                    unsafe { *pcbread = n as u32 };
                }
                if n < cb as usize { S_FALSE } else { S_OK }
            }
            Err(()) => {
                if !pcbread.is_null() {
                    unsafe { *pcbread = 0 };
                }
                if self.file.shared.cancel.is_cancelled() { E_ABORT } else { E_FAIL }
            }
        }
    }

    fn Write(&self, _pv: *const c_void, _cb: u32, _pcbwritten: *mut u32) -> HRESULT {
        STG_E_INVALIDFUNCTION
    }
}

impl IStream_Impl for RemoteStream_Impl {
    fn Seek(&self, dlibmove: i64, dworigin: STREAM_SEEK, plibnewposition: *mut u64) -> Result<()> {
        let origin = match dworigin {
            STREAM_SEEK_SET => 0,
            STREAM_SEEK_CUR => 1,
            STREAM_SEEK_END => 2,
            _ => return Err(Error::from(STG_E_INVALIDFUNCTION)),
        };
        let p = self.file.seek_to(dlibmove, origin).ok_or_else(|| Error::from(STG_E_INVALIDFUNCTION))?;
        if !plibnewposition.is_null() {
            unsafe { *plibnewposition = p };
        }
        Ok(())
    }

    fn SetSize(&self, _libnewsize: u64) -> Result<()> {
        Err(Error::from(E_NOTIMPL))
    }

    fn CopyTo(&self, _pstm: windows::core::Ref<'_, IStream>, _cb: u64, _pcbread: *mut u64, _pcbwritten: *mut u64) -> Result<()> {
        Err(Error::from(E_NOTIMPL))
    }

    fn Commit(&self, _grfcommitflags: &windows::Win32::System::Com::STGC) -> Result<()> {
        Ok(())
    }

    fn Revert(&self) -> Result<()> {
        Ok(())
    }

    fn LockRegion(&self, _liboffset: u64, _cb: u64, _dwlocktype: &windows::Win32::System::Com::LOCKTYPE) -> Result<()> {
        Err(Error::from(E_NOTIMPL))
    }

    fn UnlockRegion(&self, _liboffset: u64, _cb: u64, _dwlocktype: u32) -> Result<()> {
        Err(Error::from(E_NOTIMPL))
    }

    fn Stat(&self, pstatstg: *mut STATSTG, _grfstatflag: &STATFLAG) -> Result<()> {
        let mut s: STATSTG = unsafe { std::mem::zeroed() };
        s.r#type = STGTY_STREAM.0 as u32;
        s.cbSize = self.file.size;
        unsafe { *pstatstg = s };
        Ok(())
    }

    fn Clone(&self) -> Result<IStream> {
        Err(Error::from(E_NOTIMPL))
    }
}

// ------------------------------------------------------------------ data object

#[implement(IDataObject, IDataObjectAsyncCapability)]
struct VirtualFiles {
    shared: Arc<DragShared>,
    fmt_descriptor: u16,
    fmt_contents: u16,
    fmt_effect: u16,
}

impl VirtualFiles {
    fn new(shared: Arc<DragShared>) -> VirtualFiles {
        let reg = |name: windows::core::PCWSTR| unsafe { RegisterClipboardFormatW(name) } as u16;
        VirtualFiles { shared, fmt_descriptor: reg(w!("FileGroupDescriptorW")), fmt_contents: reg(w!("FileContents")), fmt_effect: reg(w!("Preferred DropEffect")) }
    }

    fn formats(&self) -> [FORMATETC; 3] {
        let f = |cf: u16, tymed: i32, lindex: i32| FORMATETC { cfFormat: cf, ptd: std::ptr::null_mut(), dwAspect: DVASPECT_CONTENT.0, lindex, tymed: tymed as u32 };
        [f(self.fmt_descriptor, TYMED_HGLOBAL.0, -1), f(self.fmt_contents, TYMED_ISTREAM.0, -1), f(self.fmt_effect, TYMED_HGLOBAL.0, -1)]
    }
}

fn hglobal_with(bytes: &[u8]) -> Result<HGLOBAL> {
    unsafe {
        let h = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, bytes.len())?;
        let p = GlobalLock(h);
        if p.is_null() {
            return Err(Error::from(E_OUTOFMEMORY));
        }
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), p as *mut u8, bytes.len());
        let _ = GlobalUnlock(h);
        Ok(h)
    }
}

fn filetime(ms: i64) -> FILETIME {
    // 100 ns ticks since 1601-01-01
    let t = (ms.max(0) as u64).saturating_mul(10_000).saturating_add(116_444_736_000_000_000);
    FILETIME { dwLowDateTime: t as u32, dwHighDateTime: (t >> 32) as u32 }
}

/// `FILEGROUPDESCRIPTORW`: the item count followed by one `FILEDESCRIPTORW` per file or folder.
fn build_descriptor(files: &[VFile]) -> Result<HGLOBAL> {
    let one = std::mem::size_of::<FILEDESCRIPTORW>();
    let mut buf = vec![0u8; std::mem::size_of::<u32>() + one * files.len()];
    buf[..4].copy_from_slice(&(files.len() as u32).to_le_bytes());
    for (i, f) in files.iter().enumerate() {
        let mut d: FILEDESCRIPTORW = unsafe { std::mem::zeroed() };
        let mut flags = FD_ATTRIBUTES.0;
        if f.mtime_ms.is_some() {
            flags |= FD_WRITESTIME.0;
            d.ftLastWriteTime = filetime(f.mtime_ms.unwrap_or(0));
        }
        if f.is_dir {
            d.dwFileAttributes = 0x10; // FILE_ATTRIBUTE_DIRECTORY
        } else {
            flags |= FD_FILESIZE.0 | FD_PROGRESSUI.0;
            d.dwFileAttributes = 0x80; // FILE_ATTRIBUTE_NORMAL
            d.nFileSizeHigh = (f.size >> 32) as u32;
            d.nFileSizeLow = f.size as u32;
        }
        d.dwFlags = flags as u32;
        for (k, c) in f.name.encode_utf16().take(MAX_DESCRIPTOR_PATH).enumerate() {
            d.cFileName[k] = c;
        }
        let at = std::mem::size_of::<u32>() + i * one;
        let src = unsafe { std::slice::from_raw_parts(&d as *const FILEDESCRIPTORW as *const u8, one) };
        buf[at..at + one].copy_from_slice(src);
    }
    hglobal_with(&buf)
}

impl IDataObject_Impl for VirtualFiles_Impl {
    fn GetData(&self, pformatetcin: *const FORMATETC) -> Result<STGMEDIUM> {
        let f = unsafe { &*pformatetcin };
        if f.cfFormat == self.fmt_descriptor && f.tymed & TYMED_HGLOBAL.0 as u32 != 0 {
            let list = self.shared.wait_plan().map_err(|_| Error::from(E_FAIL))?;
            trace(&format!("Explorer asks for the list ({} items)", list.len()));
            let h = build_descriptor(&list)?;
            return Ok(STGMEDIUM { tymed: TYMED_HGLOBAL.0 as u32, u: STGMEDIUM_0 { hGlobal: h }, pUnkForRelease: ManuallyDrop::new(None) });
        }
        if f.cfFormat == self.fmt_contents && f.tymed & TYMED_ISTREAM.0 as u32 != 0 {
            let list = self.shared.wait_plan().map_err(|_| Error::from(E_FAIL))?;
            let Some(file) = usize::try_from(f.lindex).ok().and_then(|i| list.get(i)).filter(|v| !v.is_dir) else {
                return Err(Error::from(DV_E_LINDEX));
            };
            let stream: IStream = RemoteStream { file: RemoteFile::new(self.shared.clone(), file.remote.clone(), file.size) }.into();
            return Ok(STGMEDIUM { tymed: TYMED_ISTREAM.0 as u32, u: STGMEDIUM_0 { pstm: ManuallyDrop::new(Some(stream)) }, pUnkForRelease: ManuallyDrop::new(None) });
        }
        if f.cfFormat == self.fmt_effect && f.tymed & TYMED_HGLOBAL.0 as u32 != 0 {
            let h = hglobal_with(&DROPEFFECT_COPY.0.to_le_bytes())?;
            return Ok(STGMEDIUM { tymed: TYMED_HGLOBAL.0 as u32, u: STGMEDIUM_0 { hGlobal: h }, pUnkForRelease: ManuallyDrop::new(None) });
        }
        Err(Error::from(DV_E_FORMATETC))
    }

    fn GetDataHere(&self, _pformatetc: *const FORMATETC, _pmedium: *mut STGMEDIUM) -> Result<()> {
        Err(Error::from(E_NOTIMPL))
    }

    fn QueryGetData(&self, pformatetc: *const FORMATETC) -> HRESULT {
        let f = unsafe { &*pformatetc };
        let ok = (f.cfFormat == self.fmt_descriptor && f.tymed & TYMED_HGLOBAL.0 as u32 != 0)
            || (f.cfFormat == self.fmt_contents && f.tymed & TYMED_ISTREAM.0 as u32 != 0)
            || (f.cfFormat == self.fmt_effect && f.tymed & TYMED_HGLOBAL.0 as u32 != 0);
        if ok { S_OK } else { DV_E_FORMATETC }
    }

    fn GetCanonicalFormatEtc(&self, _pformatectin: *const FORMATETC, pformatetcout: *mut FORMATETC) -> HRESULT {
        unsafe { (*pformatetcout).ptd = std::ptr::null_mut() };
        DATA_S_SAMEFORMATETC
    }

    fn SetData(&self, _pformatetc: *const FORMATETC, _pmedium: *const STGMEDIUM, _frelease: BOOL) -> Result<()> {
        // Explorer reports things like "performed drop effect" here: accepted and ignored.
        Ok(())
    }

    fn EnumFormatEtc(&self, dwdirection: u32) -> Result<IEnumFORMATETC> {
        if dwdirection != DATADIR_GET.0 as u32 {
            return Err(Error::from(E_NOTIMPL));
        }
        unsafe { SHCreateStdEnumFmtEtc(&self.formats()) }
    }

    fn DAdvise(&self, _pformatetc: *const FORMATETC, _advf: u32, _padvsink: windows::core::Ref<'_, IAdviseSink>) -> Result<u32> {
        Err(Error::from(OLE_E_ADVISENOTSUPPORTED))
    }

    fn DUnadvise(&self, _dwconnection: u32) -> Result<()> {
        Err(Error::from(OLE_E_ADVISENOTSUPPORTED))
    }

    fn EnumDAdvise(&self) -> Result<IEnumSTATDATA> {
        Err(Error::from(OLE_E_ADVISENOTSUPPORTED))
    }
}

/// Asynchronous operation: Explorer is told it may copy on a thread of its own. Without it the copy runs INSIDE the drop,
/// on Explorer's window thread, which freezes (no progress window, spinning cursor) until the last byte is read.
impl IDataObjectAsyncCapability_Impl for VirtualFiles_Impl {
    fn SetAsyncMode(&self, _fdoopasync: BOOL) -> Result<()> {
        Ok(())
    }

    fn GetAsyncMode(&self) -> Result<BOOL> {
        Ok(BOOL(1))
    }

    fn StartOperation(&self, _pbcreserved: windows::core::Ref<'_, IBindCtx>) -> Result<()> {
        trace("Explorer starts the copy (asynchronous)");
        self.shared.op_started.store(true, Ordering::SeqCst);
        Ok(())
    }

    fn InOperation(&self) -> Result<BOOL> {
        let running = self.shared.op_started.load(Ordering::SeqCst) && !self.shared.op_done.load(Ordering::SeqCst);
        Ok(BOOL(running as i32))
    }

    fn EndOperation(&self, hresult: HRESULT, _pbcreserved: windows::core::Ref<'_, IBindCtx>, _dweffects: u32) -> Result<()> {
        trace(&format!("Explorer ended the copy: {:?}", hresult));
        self.shared.op_done.store(true, Ordering::SeqCst);
        Ok(())
    }
}

// ------------------------------------------------------------------ drop source

#[implement(IDropSource)]
struct Source {
    shared: Arc<DragShared>,
}

impl IDropSource_Impl for Source_Impl {
    fn QueryContinueDrag(&self, fescapepressed: BOOL, grfkeystate: windows::Win32::System::SystemServices::MODIFIERKEYS_FLAGS) -> HRESULT {
        if fescapepressed.as_bool() || self.shared.cancel.is_cancelled() {
            DRAGDROP_S_CANCEL
        } else if grfkeystate.0 & MK_LBUTTON.0 == 0 {
            DRAGDROP_S_DROP
        } else {
            S_OK
        }
    }

    fn GiveFeedback(&self, _dweffect: DROPEFFECT) -> HRESULT {
        // Windows draws the cursor (copy / not allowed): no custom image
        DRAGDROP_S_USEDEFAULTCURSORS
    }
}

/// The drags running now by token (several copies can run at once), for progress and cancel from the UI.
static CURRENT: Mutex<Option<HashMap<i64, Arc<DragShared>>>> = Mutex::new(None);

fn current(token: i64) -> Option<Arc<DragShared>> {
    CURRENT.lock().unwrap().as_ref().and_then(|m| m.get(&token).cloned())
}

/// Runs the drag of `refs` (remote refs of `session`) on the CALLING thread, which must be a thread of its own:
/// `DoDragDrop` loops until the drop and, with virtual files, until Explorer has finished reading.
/// Returns the effect Windows reports (1 = copied, 0 = cancelled / not accepted) or one of the `DRAG_*` codes.
pub fn run_drag_session(engine: &Engine, session: &str, refs: Vec<String>, token: i64) -> i32 {
    let Ok((rt, provider)) = engine.drag_source(session) else { return DRAG_NO_SESSION };
    let shared = DragShared::new(rt, provider);
    CURRENT.lock().unwrap().get_or_insert_with(HashMap::new).insert(token, shared.clone());
    let code = run_drag(&shared, refs);
    if let Some(m) = CURRENT.lock().unwrap().as_mut() {
        m.remove(&token);
    }
    code
}

fn run_drag(shared: &Arc<DragShared>, refs: Vec<String>) -> i32 {
    shared.start_planning(refs);
    unsafe {
        let init = OleInitialize(None);
        // `DoDragDrop` needs the mouse: while the button is down the capture belongs to the Java window, in another
        // thread. Sharing that thread's input queue lets this thread capture the mouse and see the drag.
        let ui_thread = GetWindowThreadProcessId(GetForegroundWindow(), None);
        let me = GetCurrentThreadId();
        let attached = ui_thread != 0 && ui_thread != me && AttachThreadInput(me, ui_thread, true).as_bool();
        let data: IDataObject = VirtualFiles::new(shared.clone()).into();
        let source: IDropSource = Source { shared: shared.clone() }.into();
        let mut effect = DROPEFFECT_NONE;
        let r = DoDragDrop(&data, &source, DROPEFFECT_COPY, &mut effect);
        trace(&format!("drag ended: {:?} effect {} bytes {}", r, effect.0, shared.served.load(Ordering::Relaxed)));
        // The shared input queue was only needed for the drag. Left attached while this thread waits for Explorer, it
        // would tie the window's mouse and keyboard handling to this thread (the app would not take drops or clicks).
        if attached {
            let _ = AttachThreadInput(me, ui_thread, false);
        }
        shared.dropped.store(true, Ordering::SeqCst);
        // Explorer copies on its own thread: its calls into our streams arrive on THIS thread, so it must keep pumping
        // messages until Explorer says it is done (otherwise the copy would stall).
        if shared.op_started.load(Ordering::SeqCst) {
            while !shared.op_done.load(Ordering::SeqCst) {
                MsgWaitForMultipleObjects(None, false, 200, QS_ALLINPUT);
                let mut msg = MSG::default();
                while PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE).as_bool() {
                    let _ = TranslateMessage(&msg);
                    DispatchMessageW(&msg);
                }
            }
            trace(&format!("copy finished, bytes {}", shared.served.load(Ordering::Relaxed)));
        }
        shared.cancel.cancel();
        drop(data);
        if init.is_ok() {
            OleUninitialize();
        }
        let failed = shared.plan.lock().unwrap().as_ref().and_then(|p| p.as_ref().err().copied());
        match failed {
            Some(code) if effect.0 == 0 => code,
            _ => effect.0 as i32,
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_nextdata_bridge_NativeBridge_dragOutProgress<'a>(env: jni::JNIEnv<'a>, _c: jni::objects::JClass<'a>, token: jni::sys::jlong) -> jni::sys::jlongArray {
    // [bytes copied so far, total bytes (0 = not known yet), 1 once Explorer has started copying, 1 once the drag is over]
    let v: [i64; 4] = match current(token) {
        Some(s) => [s.served.load(Ordering::Relaxed) as i64, s.total.load(Ordering::Relaxed) as i64, s.op_started.load(Ordering::SeqCst) as i64, s.dropped.load(Ordering::SeqCst) as i64],
        None => [0, 0, 0, 1],
    };
    match env.new_long_array(4) {
        Ok(a) => {
            let _ = env.set_long_array_region(&a, 0, &v);
            a.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_nextdata_bridge_NativeBridge_dragOutCancel(_env: jni::JNIEnv, _c: jni::objects::JClass, token: jni::sys::jlong) {
    if let Some(s) = current(token) {
        s.cancel.cancel();
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_nextdata_bridge_NativeBridge_dragOut(
    mut env: jni::JNIEnv,
    _c: jni::objects::JClass,
    session: jni::objects::JString,
    refs_json: jni::objects::JString,
    token: jni::sys::jlong,
) -> jni::sys::jint {
    let (Ok(session), Ok(refs)) = (env.get_string(&session).map(String::from), env.get_string(&refs_json).map(String::from)) else { return DRAG_PANIC };
    let Ok(refs) = serde_json::from_str::<Vec<String>>(&refs) else { return DRAG_PANIC };
    std::panic::catch_unwind(|| crate::bridge::with_engine(|e| run_drag_session(e, &session, refs, token))).unwrap_or(DRAG_PANIC)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::provider::fake::FakeProvider;

    fn item(rel: &[&str], kind: EntryKind, size: u64) -> PlanItem {
        PlanItem { remote: format!("/{}", rel.join("/")), rel: rel.iter().map(|s| s.to_string()).collect(), kind, size, mtime_ms: Some(1_700_000_000_000) }
    }

    #[test]
    fn names_that_differ_only_by_case_get_a_number_and_children_follow() {
        let list = flatten(vec![
            item(&["Foto"], EntryKind::Dir, 0),
            item(&["foto"], EntryKind::Dir, 0),
            item(&["foto", "a.txt"], EntryKind::File, 1),
            item(&["Foto", "a.txt"], EntryKind::File, 2),
        ]);
        let names: Vec<&str> = list.iter().map(|f| f.name.as_str()).collect();
        assert_eq!(names, ["Foto", "foto (1)", "foto (1)\\a.txt", "Foto\\a.txt"]);
    }

    #[test]
    fn long_paths_are_shortened_keeping_the_extension_or_left_out() {
        let long = format!("{}.txt", "x".repeat(400));
        let list = flatten(vec![item(&[&long], EntryKind::File, 1)]);
        assert_eq!(list.len(), 1);
        assert!(utf16_len(&list[0].name) <= MAX_DESCRIPTOR_PATH && list[0].name.ends_with(".txt"));
        // a folder chain that leaves no room for the leaf: the item is dropped, not mangled
        let deep = "d".repeat(130);
        let list = flatten(vec![item(&[&deep], EntryKind::Dir, 0), item(&[&deep, &deep], EntryKind::Dir, 0), item(&[&deep, &deep, "file.txt"], EntryKind::File, 1)]);
        assert!(list.iter().all(|f| utf16_len(&f.name) <= MAX_DESCRIPTOR_PATH));
        assert!(list.len() < 3);
    }

    #[test]
    fn links_and_children_of_skipped_folders_are_not_offered() {
        let list = flatten(vec![item(&["a"], EntryKind::Dir, 0), item(&["a", "l"], EntryKind::Symlink, 0), item(&["zz", "orphan"], EntryKind::File, 1)]);
        assert_eq!(list.len(), 1);
    }

    fn rig(files: &[(&str, Vec<u8>)]) -> (tokio::runtime::Runtime, Arc<DragShared>) {
        let rt = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap();
        let p = FakeProvider::new();
        for (path, data) in files {
            p.put_file(path, data);
        }
        let shared = DragShared::new(rt.handle().clone(), p);
        (rt, shared)
    }

    #[test]
    fn planning_a_folder_lists_what_a_download_would_copy() {
        let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().unwrap();
        let p = FakeProvider::new();
        p.put_dir("/Progetto");
        p.put_dir("/Progetto/vuota");
        p.put_file("/Progetto/a.txt", b"abc");
        p.put_file("/Progetto/b.txt", b"x");
        let shared = DragShared::new(rt.handle().clone(), p);
        shared.start_planning(vec!["/Progetto".into()]);
        let list = shared.wait_plan().unwrap();
        let mut names: Vec<String> = list.iter().map(|f| f.name.clone()).collect();
        names.sort();
        assert_eq!(names, ["Progetto", "Progetto\\a.txt", "Progetto\\b.txt", "Progetto\\vuota"]);
        assert!(list.iter().filter(|f| f.name == "Progetto" || f.name.ends_with("vuota")).all(|f| f.is_dir));
    }

    #[test]
    fn a_missing_selection_reports_a_plan_error() {
        let (_rt, shared) = rig(&[]);
        shared.start_planning(vec!["/non-esiste".into()]);
        assert_eq!(shared.wait_plan().err(), Some(DRAG_PLAN_FAILED));
    }

    #[test]
    fn the_stream_serves_exact_bytes_across_chunks_and_after_a_rewind() {
        let data: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();
        let (_rt, shared) = rig(&[("/big.bin", data.clone())]);
        let f = RemoteFile::new(shared.clone(), "/big.bin".into(), data.len() as u64);
        let mut got = vec![];
        let mut buf = vec![0u8; 65_536];
        loop {
            let n = f.read_into(&mut buf).unwrap();
            if n == 0 {
                break;
            }
            got.extend_from_slice(&buf[..n]);
        }
        assert_eq!(got, data);
        // Explorer rewinds before releasing; a seek into the middle reopens at the right offset
        assert_eq!(f.seek_to(0, 0), Some(0));
        assert_eq!(f.seek_to(150_000, 0), Some(150_000));
        let n = f.read_into(&mut buf[..1000]).unwrap();
        assert_eq!(&buf[..n], &data[150_000..150_000 + n]);
        assert_eq!(shared.served.load(Ordering::Relaxed) as usize, data.len() + n);
    }

    #[test]
    fn a_file_shorter_than_announced_is_an_error_not_a_truncated_copy() {
        let (_rt, shared) = rig(&[("/short.bin", vec![1u8; 10])]);
        let f = RemoteFile::new(shared, "/short.bin".into(), 100);
        let mut buf = vec![0u8; 64];
        assert_eq!(f.read_into(&mut buf), Ok(10));
        assert!(f.read_into(&mut buf).is_err());
    }

    #[test]
    fn cancelling_stops_reads() {
        let (_rt, shared) = rig(&[("/f.bin", vec![1u8; 1000])]);
        let f = RemoteFile::new(shared.clone(), "/f.bin".into(), 1000);
        shared.cancel.cancel();
        let mut buf = vec![0u8; 64];
        assert!(f.read_into(&mut buf).is_err());
    }

    #[test]
    fn the_descriptor_holds_names_sizes_and_folder_flags() {
        let list = vec![
            VFile { remote: "/d".into(), name: "d".into(), is_dir: true, size: 0, mtime_ms: None },
            VFile { remote: "/d/f".into(), name: "d\\f.txt".into(), is_dir: false, size: 5_000_000_000, mtime_ms: Some(1_700_000_000_000) },
        ];
        let h = build_descriptor(&list).unwrap();
        unsafe {
            let p = GlobalLock(h) as *const u8;
            assert_eq!(u32::from_le_bytes([*p, *p.add(1), *p.add(2), *p.add(3)]), 2);
            let rd = |i: usize| std::ptr::read_unaligned((p.add(4) as *const FILEDESCRIPTORW).add(i));
            let d = [rd(0), rd(1)];
            let a0 = d[0].dwFileAttributes;
            assert_eq!(a0, 0x10);
            assert_eq!(d[0].dwFlags & FD_FILESIZE.0 as u32, 0);
            assert_eq!(d[1].dwFlags & FD_FILESIZE.0 as u32, FD_FILESIZE.0 as u32);
            assert_eq!(((d[1].nFileSizeHigh as u64) << 32) | d[1].nFileSizeLow as u64, 5_000_000_000);
            let nm = d[1].cFileName;
            assert_eq!(String::from_utf16_lossy(&nm).trim_end_matches('\0'), "d\\f.txt");
            let _ = GlobalUnlock(h);
        }
    }
}
