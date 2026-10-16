//! What Windows' `tasklist`, `taskkill`, `netstat` and its file sharing rules gave the launcher,
//! from macOS' own process interface (libproc, the one Activity Monitor and `lsof` read) and
//! signals. Asked every couple of seconds, so nothing here starts a program but [`port_owner`].

use std::ffi::{c_int, c_void};
use std::os::unix::fs::MetadataExt;
use std::path::Path;
use std::process::{Command, Stdio};

/// A running process: its id and its program's file name, lowercased (`benilla`, `java`).
#[derive(Clone, Debug)]
pub struct Proc {
    pub pid: i32,
    pub name: String,
}

/// Every process we may look at (other users' are skipped: macOS doesn't tell us their names).
pub fn processes() -> Vec<Proc> {
    all_pids()
        .into_iter()
        .filter_map(|pid| {
            let path = exe_path(pid)?;
            let name = Path::new(&path).file_name()?.to_string_lossy().to_lowercase();
            Some(Proc { pid, name })
        })
        .collect()
}

fn all_pids() -> Vec<i32> {
    // Asked once for the count, then with room to spare: processes start in between.
    let n = unsafe { libc::proc_listallpids(std::ptr::null_mut(), 0) };
    if n <= 0 {
        return Vec::new();
    }
    let mut pids = vec![0i32; n as usize + 64];
    let bytes = (pids.len() * std::mem::size_of::<i32>()) as c_int;
    let got = unsafe { libc::proc_listallpids(pids.as_mut_ptr().cast::<c_void>(), bytes) };
    pids.truncate(got.max(0) as usize);
    pids.retain(|&p| p > 0);
    pids
}

/// The program a process runs, by its full path.
pub fn exe_path(pid: i32) -> Option<String> {
    let mut buf = vec![0u8; libc::PROC_PIDPATHINFO_MAXSIZE as usize];
    let len = unsafe { libc::proc_pidpath(pid, buf.as_mut_ptr().cast::<c_void>(), buf.len() as u32) };
    if len <= 0 {
        return None;
    }
    buf.truncate(len as usize);
    String::from_utf8(buf).ok()
}

/// Asks (`SIGTERM`: the program saves and quits) or forces (`SIGKILL`) every process of ours
/// running the program named `name` to end, as `taskkill /IM` did.
pub fn kill_name(name: &str, force: bool) {
    let name = name.to_lowercase();
    for p in processes().into_iter().filter(|p| p.name == name) {
        kill_pid(p.pid, force);
    }
}

pub fn kill_pid(pid: i32, force: bool) {
    unsafe {
        libc::kill(pid, if force { libc::SIGKILL } else { libc::SIGTERM });
    }
}

/// Ends a whole process group (a script and what it started), as `taskkill /T` did a tree.
pub fn kill_group(pgid: i32) {
    if pgid > 1 {
        unsafe {
            libc::killpg(pgid, libc::SIGKILL);
        }
    }
}

/// The program listening on a local TCP port (its executable's path), if any: `lsof` for the
/// process id, then its path. Only asked when the port is taken, so the cost doesn't matter.
pub fn port_owner_path(port: u16) -> Option<String> {
    let out = Command::new("/usr/sbin/lsof")
        .args(["-nP", &format!("-iTCP:{port}"), "-sTCP:LISTEN", "-Fp"])
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .ok()?;
    let text = String::from_utf8_lossy(&out.stdout);
    let pid: i32 = text.lines().find_map(|l| l.strip_prefix('p')?.trim().parse().ok())?;
    exe_path(pid)
}

// libproc's per-file answer (sys/proc_info.h), which the libc crate doesn't carry.
const PROC_PIDFDVNODEPATHINFO: c_int = 2;

#[repr(C)]
struct ProcFileInfo {
    fi_openflags: u32,
    fi_status: u32,
    fi_offset: i64,
    fi_type: i32,
    fi_guardflags: u32,
}

#[repr(C)]
struct VnodeFdInfoWithPath {
    pfi: ProcFileInfo,
    pvip: libc::vnode_info_path,
}

/// Whether some process holds `path` open (compared by device and inode, so another name for the
/// same file counts). Windows answered this with a sharing violation; macOS has no such locks,
/// so every process's open files are looked through, as `lsof` does, without its second's wait.
pub fn file_held_open(path: &Path) -> bool {
    let Ok(meta) = std::fs::metadata(path) else {
        return false;
    };
    let (dev, ino) = (meta.dev() as u32, meta.ino());
    all_pids().into_iter().any(|pid| holds(pid, dev, ino))
}

fn holds(pid: i32, dev: u32, ino: u64) -> bool {
    let size = std::mem::size_of::<libc::proc_fdinfo>();
    let need = unsafe { libc::proc_pidinfo(pid, libc::PROC_PIDLISTFDS, 0, std::ptr::null_mut(), 0) };
    if need <= 0 {
        return false;
    }
    let mut fds: Vec<libc::proc_fdinfo> = Vec::with_capacity(need as usize / size + 16);
    let bytes = (fds.capacity() * size) as c_int;
    let got = unsafe { libc::proc_pidinfo(pid, libc::PROC_PIDLISTFDS, 0, fds.as_mut_ptr().cast::<c_void>(), bytes) };
    if got <= 0 {
        return false;
    }
    unsafe { fds.set_len(got as usize / size) };
    fds.iter().filter(|fd| fd.proc_fdtype == libc::PROX_FDTYPE_VNODE as u32).any(|fd| {
        let mut info = std::mem::MaybeUninit::<VnodeFdInfoWithPath>::zeroed();
        let n = unsafe {
            libc::proc_pidfdinfo(
                pid,
                fd.proc_fd,
                PROC_PIDFDVNODEPATHINFO,
                info.as_mut_ptr().cast::<c_void>(),
                std::mem::size_of::<VnodeFdInfoWithPath>() as c_int,
            )
        };
        if n as usize != std::mem::size_of::<VnodeFdInfoWithPath>() {
            return false;
        }
        let st = unsafe { &info.assume_init_ref().pvip.vip_vi.vi_stat };
        st.vst_dev == dev && st.vst_ino == ino
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn we_find_ourselves() {
        let me = std::process::id() as i32;
        assert!(processes().iter().any(|p| p.pid == me), "our own process is listed");
        assert!(exe_path(me).is_some_and(|p| p.starts_with('/')));
    }

    #[test]
    fn an_open_file_is_held_and_a_closed_one_not() {
        let path = std::env::temp_dir().join(format!("wowmaccraft-held-{}", std::process::id()));
        std::fs::write(&path, "").unwrap();
        assert!(!file_held_open(&path), "closed");
        let f = std::fs::File::open(&path).unwrap();
        assert!(file_held_open(&path), "held open by us");
        drop(f);
        assert!(!file_held_open(&path), "closed again");
        let _ = std::fs::remove_file(path);
    }
}

/// Removes the WoW-Minecraft link (the POSIX shared memory `/SkyCraft_v1.<uid>` WoW makes and its
/// Minecraft lock file). Unlike a Windows mapping it outlives both games, 200 MB of it; once
/// both have gone it's dropped, and the next WoW makes a fresh one.
pub fn remove_link() {
    // SAFETY: getuid has no preconditions.
    let uid = unsafe { libc::getuid() };
    let name = std::ffi::CString::new(format!("/SkyCraft_v1.{uid}")).unwrap();
    // SAFETY: a valid C string; a missing name is only ENOENT.
    unsafe { libc::shm_unlink(name.as_ptr()) };
    let _ = std::fs::remove_file(format!("/tmp/SkyCraft_v1_minecraft.{uid}.lock"));
}
