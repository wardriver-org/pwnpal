import json, os, pathlib, glob, re, shutil, socket, subprocess, tomllib

def read(path):
    try: return pathlib.Path(path).read_text().strip()
    except (OSError, UnicodeError): return ''

config = {}
try: config = tomllib.loads(read('/etc/pwnagotchi/config.toml'))
except Exception: pass
version = 'Unknown'
patterns = ['/home/*/.pwn/lib/python*/site-packages/pwnagotchi/_version.py', '/usr/local/lib/python*/dist-packages/pwnagotchi/_version.py', '/usr/local/lib/python*/site-packages/pwnagotchi/_version.py', '/usr/lib/python*/site-packages/pwnagotchi/_version.py']
for pattern in patterns:
    for path in glob.glob(pattern):
        m = re.search(r"__version__\s*=\s*['\"]([^'\"]+)", read(path))
        if m: version = m.group(1)
usage = shutil.disk_usage('/')
try: temperature = '%.1f °C' % (int(read('/sys/class/thermal/thermal_zone0/temp')) / 1000)
except ValueError: temperature = 'Unavailable'
cap = pathlib.Path(config.get('bettercap', {}).get('handshakes', '/home/pi/handshakes'))
try:
    captures = str(sum(1 for p in cap.iterdir() if p.is_file() and p.suffix.lower() in ('.pcap', '.pcapng')))
except OSError: captures = 'Unavailable'
try: service = subprocess.run(['systemctl', 'is-active', 'pwnagotchi'], capture_output=True, text=True, timeout=5).stdout.strip() or 'unknown'
except Exception: service = 'unknown'
print(json.dumps(dict(version=version, service=service, hostname=socket.gethostname(), uptime=int(float(read('/proc/uptime').split()[0])), temperature=temperature, storage='%.1f GB free' % (usage.free/1e9), captures=captures, privileged=os.geteuid()==0)))
