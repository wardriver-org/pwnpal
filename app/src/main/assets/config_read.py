import pathlib, json, hashlib
p = pathlib.Path('/etc/pwnagotchi/config.toml')
data = p.read_bytes()
if len(data) > 131072: raise ValueError('Configuration exceeds the 128 KiB editor limit')
print(json.dumps(dict(text=data.decode('utf-8'), hash=hashlib.sha256(data).hexdigest())))
