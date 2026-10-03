import contextlib
import base64, pathlib, json, hashlib, tomllib, fcntl, os, tempfile, datetime, stat

@contextlib.contextmanager
def config_lock(path):
    with open(str(path) + '.pwnpal.lock', 'a') as lock:
        os.chmod(lock.name, 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield

def save_config(path, expected_hash, encoded, lock_held=False):
    data = base64.b64decode(encoded, validate=True)
    if len(data) > 131072: raise ValueError('Configuration exceeds 128 KiB')
    parsed = tomllib.loads(data.decode('utf-8'))
    if not parsed: raise ValueError('Refusing an empty configuration')
    path = pathlib.Path(path)
    if path.is_symlink(): raise ValueError('Refusing to replace a symlink configuration')
    with contextlib.nullcontext() if lock_held else config_lock(path):
        old = path.read_bytes()
        if hashlib.sha256(old).hexdigest() != expected_hash:
            raise ValueError('Configuration changed on the device. Reload before saving.')
        info = path.stat()
        backup_dir = path.parent / 'pwnpal-backups'
        backup_dir.mkdir(mode=0o700, exist_ok=True)
        if backup_dir.is_symlink(): raise ValueError('Backup directory must not be a symlink')
        os.chmod(backup_dir, 0o700)
        fd, backup = tempfile.mkstemp(prefix='config-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S') + '-', suffix='.toml', dir=backup_dir)
        with os.fdopen(fd, 'wb') as f:
            f.write(old); f.flush(); os.fsync(f.fileno())
        fd, temp = tempfile.mkstemp(prefix='.pwnpal-', dir=path.parent)
        try:
            with os.fdopen(fd, 'wb') as f:
                f.write(data); f.flush(); os.fsync(f.fileno())
                os.fchmod(f.fileno(), stat.S_IMODE(info.st_mode) & 0o660)
                os.fchown(f.fileno(), info.st_uid, info.st_gid)
            os.replace(temp, path)
            dfd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try: os.fsync(dfd)
            finally: os.close(dfd)
        finally:
            if os.path.exists(temp): os.unlink(temp)
    return dict(backup=str(backup), hash=hashlib.sha256(data).hexdigest())

if __name__ == '__main__':
    print(json.dumps(save_config('/etc/pwnagotchi/config.toml', EXPECTED_HASH, ENCODED_CONFIG)))
