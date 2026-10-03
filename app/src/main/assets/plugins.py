"""PwnPal plugin operations. Inspects Python with AST; never imports plugin code."""
import ast, base64, datetime, glob, hashlib, json, os, pathlib, re, tempfile, tomllib

NAME = re.compile(r'[A-Za-z][A-Za-z0-9_-]{0,79}\Z')
MAX_PLUGIN = 262144
CONFIG = pathlib.Path('/etc/pwnagotchi/config.toml')

def digest(data): return hashlib.sha256(data).hexdigest()
def valid_name(name):
    if not isinstance(name, str) or not NAME.fullmatch(name): raise ValueError('Use a simple plugin name: letters, numbers, hyphens or underscores.')
    return name

def metadata(data):
    if len(data) > MAX_PLUGIN: raise ValueError('Plugin exceeds the 256 KiB limit.')
    tree = ast.parse(data.decode('utf-8-sig'))
    info = {'version': 'Unknown', 'author': 'Unknown', 'description': ''}
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign) and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id in ('__version__', '__author__', '__description__'):
                    info[{'__version__':'version','__author__':'author','__description__':'description'}[target.id]] = node.value.value[:500]
    info['plugin_class'] = any(isinstance(n, ast.ClassDef) and any((isinstance(b, ast.Attribute) and b.attr == 'Plugin') or (isinstance(b, ast.Name) and b.id == 'Plugin') for b in n.bases) for n in ast.walk(tree))
    return info

def merge(dst, src):
    for k,v in src.items():
        if isinstance(v,dict) and isinstance(dst.get(k),dict): merge(dst[k],v)
        else: dst[k]=v
    return dst

def firmware_root():
    patterns=['/home/*/.pwn/lib/python*/site-packages/pwnagotchi','/usr/local/lib/python*/dist-packages/pwnagotchi','/usr/local/lib/python*/site-packages/pwnagotchi','/usr/lib/python*/site-packages/pwnagotchi']
    for pattern in patterns:
        for path in glob.glob(pattern):
            if (pathlib.Path(path)/'defaults.toml').is_file(): return pathlib.Path(path)
    raise ValueError('Cannot locate the installed Pwnagotchi package. Plugin management requires a supported firmware layout.')

def snapshot(config=CONFIG, root=None):
    root = root or firmware_root()
    default_bytes=(root/'defaults.toml').read_bytes()
    user_bytes=config.read_bytes()
    effective=merge(tomllib.loads(default_bytes.decode()),tomllib.loads(user_bytes.decode()))
    h=hashlib.sha256(default_bytes+b'\x00'+user_bytes)
    override=set(); custom_override=False
    confd=effective.get('main',{}).get('confd')
    if confd:
        for name in glob.glob(str(pathlib.Path(confd)/'*.toml')):
            data=pathlib.Path(name).read_bytes();h.update(name.encode()+b'\x00'+data)
            extra=tomllib.loads(data.decode()); main=extra.get('main',{})
            for plugin,options in main.get('plugins',{}).items():
                if isinstance(options,dict) and 'enabled' in options: override.add(plugin)
            custom_override |= 'custom_plugins' in main
            merge(effective,extra)
    custom=effective.get('main',{}).get('custom_plugins') or '/usr/local/share/pwnagotchi/installed-plugins'
    builtins=root/'plugins/default'; entries={}
    for folder,kind in [(builtins,'Built-in'),(pathlib.Path(custom),'Custom')]:
        for path in folder.glob('*.py'):
            if not NAME.fullmatch(path.stem) or path.is_symlink(): continue
            data=path.read_bytes()
            try: info=metadata(data)
            except Exception: info={'version':'Unknown','author':'Unknown','description':'Could not parse plugin metadata.'}
            entries[path.stem]=dict(name=path.stem,kind=kind,enabled=bool(effective.get('main',{}).get('plugins',{}).get(path.stem,{}).get('enabled',False)),sha256=digest(data),overridden=path.stem in override,**info)
    return dict(token=h.hexdigest(),config_hash=digest(user_bytes),plugins=sorted(entries.values(),key=lambda p:p['name']),custom=str(custom),custom_override=custom_override,overridden_names=sorted(override)), effective, user_bytes, builtins

def editable(config_bytes):
    try: import tomlkit
    except ImportError: raise ValueError('Plugin changes need tomlkit in the firmware Python environment. No packages were installed automatically.')
    return tomlkit, tomlkit.parse(config_bytes.decode())

def configure(user_bytes, name, enabled, custom=None):
    tk,doc=editable(user_bytes)
    if 'main' not in doc: doc['main']=tk.table()
    if 'plugins' not in doc['main']: doc['main']['plugins']=tk.table()
    if name not in doc['main']['plugins']:doc['main']['plugins'][name]=tk.table()
    doc['main']['plugins'][name]['enabled']=enabled
    if custom is not None:doc['main']['custom_plugins']=custom
    return tk.dumps(doc).encode()

def safe_folder(folder):
    p=pathlib.Path(folder)
    if not p.is_absolute() or p.resolve()!=p or p in (pathlib.Path('/'), pathlib.Path('/etc'),pathlib.Path('/usr'),pathlib.Path('/usr/local'),pathlib.Path('/home'),pathlib.Path('/root')):
        raise ValueError('Custom plugin directory must be an absolute, non-symlink plugin folder.')
    p.mkdir(parents=True,exist_ok=True)
    return p

def backup_plugin(config, name, data):
    folder=config.parent/'pwnpal-plugin-backups'
    if folder.is_symlink():raise ValueError('Plugin backup folder must not be a symlink.')
    folder.mkdir(mode=0o700,exist_ok=True);os.chmod(folder,0o700)
    fd,path=tempfile.mkstemp(prefix=name+'-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S')+'-',suffix='.py.backup',dir=folder)
    with os.fdopen(fd,'wb') as f:f.write(data);f.flush();os.fsync(f.fileno())
    return path

def _handle(request, save, config=CONFIG, root=None):
    state,effective,user,builtins=snapshot(config,root)
    action=request.get('action','list')
    if action=='list':return state
    name=valid_name(request.get('name'))
    installed=next((p for p in state['plugins'] if p['name']==name),None)
    if action=='inspect':
        data=base64.b64decode(request['source'],validate=True)
        info=metadata(data)
        if not info['plugin_class']:raise ValueError('No Pwnagotchi Plugin subclass found in this file. Multi-file packages and scripts are not supported by this installer.')
        if (builtins/(name+'.py')).exists():raise ValueError('This name belongs to a built-in plugin. Choose a different name; built-in plugins cannot be replaced.')
        return dict(**info,token=state['token'],sha256=digest(data),previous=installed['sha256'] if installed else '',updating=bool(installed),overridden=name in state['overridden_names'])
    if request.get('token')!=state['token']:raise ValueError('Configuration changed on the device. Refresh plugins and review again.')
    if name in state['overridden_names']:raise ValueError('This plugin is controlled by a conf.d override. Edit that file on the device first.')
    if action=='toggle':
        if not installed:raise ValueError('Plugin is no longer installed. Refresh the list.')
        if request.get('previous')!=installed['sha256']:raise ValueError('Plugin file changed. Refresh and review again.')
        data=configure(user,name,bool(request['enabled']))
        result=save(config,state['config_hash'],base64.b64encode(data).decode())
        return dict(message='Saved plugin setting. Restart the service to apply it.',backup=result['backup'])
    if action not in ('install','remove'):raise ValueError('Unknown plugin action.')
    if (builtins/(name+'.py')).exists():raise ValueError('Built-in plugins cannot be replaced or removed.')
    if state['custom_override']:raise ValueError('The custom plugin path is controlled by conf.d. Manage that override on the device first.')
    folder=safe_folder(state['custom']);target=folder/(name+'.py')
    if target.is_symlink():raise ValueError('Refusing to replace a symlink plugin.')
    prior=target.read_bytes() if target.exists() else None
    if request.get('previous','')!=(digest(prior) if prior is not None else ''):raise ValueError('Plugin file changed. Review it again before continuing.')
    if action=='remove' and prior is None:raise ValueError('Plugin is no longer installed.')
    data=None
    if action=='install':
        data=base64.b64decode(request['source'],validate=True)
        if digest(data)!=request.get('sha256'):raise ValueError('Plugin checksum mismatch.')
        if not metadata(data)['plugin_class']:raise ValueError('No Pwnagotchi Plugin subclass found.')
    backup=backup_plugin(config,name,prior) if prior is not None else ''
    new_config=configure(user,name,False,state['custom'])
    result=save(config,state['config_hash'],base64.b64encode(new_config).decode())
    try:
        if action=='remove':target.unlink()
        else:
            fd,temp=tempfile.mkstemp(prefix='.pwnpal-',dir=folder)
            try:
                with os.fdopen(fd,'wb') as f:f.write(data);f.flush();os.fsync(f.fileno());os.fchmod(f.fileno(),0o644)
                os.replace(temp,target)
            finally:
                if os.path.exists(temp):os.unlink(temp)
    except Exception as exc:
        raise ValueError('Plugin file operation failed; its configuration was saved as disabled. Config backup: '+result['backup']+'. '+str(exc))
    return dict(message=('Plugin removed and backed up.' if action=='remove' else 'Plugin installed disabled. Configure it before enabling.' )+' Restart the service to apply changes.',backup=backup or result['backup'])

def handle(request, save, config=CONFIG, root=None):
    import fcntl
    with open(str(config)+'.pwnpal.lock','a') as lock:
        os.chmod(lock.name,0o600)
        fcntl.flock(lock,fcntl.LOCK_EX)
        def locked_save(path, expected, encoded):
            return save(path, expected, encoded, lock_held=True)
        result = _handle(request,locked_save,config,root)
        if request.get('action') in ('install','remove'):
            state,effective,_,_=snapshot(config,root)
            name=request['name']
            if effective.get('main',{}).get('plugins',{}).get(name,{}).get('enabled',False):
                raise ValueError('Plugin configuration changed during installation. Refresh and check the device before restarting.')
            installed=next((p for p in state['plugins'] if p['name']==name),None)
            if request['action']=='install' and (not installed or installed['sha256']!=request['sha256']):
                raise ValueError('Installed plugin changed unexpectedly. Refresh and review again.')
            if request['action']=='remove' and installed:
                raise ValueError('Plugin is still present. Refresh and check the device.')
        return result

if __name__=='__main__':
    ns={'__name__':'pwnpal_config_helper'}
    exec(compile(base64.b64decode(CONFIG_HELPER),'<pwnpal-config>','exec'),ns)
    try: print(json.dumps(handle(json.loads(base64.b64decode(REQUEST)),ns['save_config'])))
    except Exception as exc: print(str(exc),file=__import__('sys').stderr);raise SystemExit(1)
