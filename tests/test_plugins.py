import base64
import importlib.util
from pathlib import Path
import tempfile
import tomllib
import unittest

ASSETS=Path(__file__).resolve().parents[1]/'app/src/main/assets'
def module(name):
    spec=importlib.util.spec_from_file_location(name,ASSETS/(name+'.py'))
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
p=module('plugins');save=module('config_save').save_config
SOURCE=b"import pwnagotchi.plugins as plugins\n__version__='1'\nclass Demo(plugins.Plugin):\n    pass\n"
class PluginsTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.pkg=self.root/'package';self.custom=self.root/'custom';self.confd=self.root/'conf.d';self.confd.mkdir()
        (self.pkg/'plugins/default').mkdir(parents=True)
        (self.pkg/'plugins/default/builtin.py').write_bytes(SOURCE)
        (self.pkg/'defaults.toml').write_text('[main.plugins.builtin]\nenabled=true\n')
        self.config=self.root/'config.toml'
        self.config.write_text(f'# Preserve this comment\n[main]\ncustom_plugins="{self.custom}"\nconfd="{self.confd}"\nname="test"\n')
    def call(self,**req):return p.handle(req,save,self.config,self.pkg)
    def review(self,source=SOURCE,name='demo'):
        return self.call(action='inspect',name=name,source=base64.b64encode(source).decode())
    def install(self,source=SOURCE):
        r=self.review(source);return self.call(action='install',name='demo',source=base64.b64encode(source).decode(),sha256=r['sha256'],token=r['token'],previous=r['previous'])
    def test_inspection_does_not_execute_code(self):
        marker=self.root/'executed';self.review(f"open({str(marker)!r},'w').write('oops')\n".encode()+SOURCE)
        self.assertFalse(marker.exists());self.assertFalse(self.custom.exists())
    def test_install_disabled_preserves_config(self):
        self.install();self.assertEqual((self.custom/'demo.py').read_bytes(),SOURCE)
        self.assertFalse(tomllib.loads(self.config.read_text())['main']['plugins']['demo']['enabled'])
        self.assertIn('# Preserve this comment',self.config.read_text())
    def test_update_and_remove_back_up_files(self):
        self.install();updated=SOURCE.replace(b"'1'",b"'2'");result=self.install(updated)
        self.assertEqual(Path(result['backup']).read_bytes(),SOURCE)
        s=self.call();entry=next(x for x in s['plugins'] if x['name']=='demo')
        r=self.call(action='remove',name='demo',token=s['token'],previous=entry['sha256'])
        self.assertEqual(Path(r['backup']).read_bytes(),updated);self.assertFalse((self.custom/'demo.py').exists())
    def test_builtin_protection(self):
        with self.assertRaisesRegex(ValueError,'built-in'):self.review(name='builtin')
        s=self.call()
        with self.assertRaisesRegex(ValueError,'Built-in'):self.call(action='remove',name='builtin',token=s['token'])
    def test_stale_config_and_file(self):
        r=self.review();self.config.write_text(self.config.read_text()+'# changed\n')
        with self.assertRaisesRegex(ValueError,'Configuration changed'):
            self.call(action='install',name='demo',token=r['token'])
        self.install();s=self.call();(self.custom/'demo.py').write_bytes(SOURCE+b'# changed')
        with self.assertRaisesRegex(ValueError,'Plugin file changed'):
            self.call(action='toggle',name='demo',token=s['token'],previous=p.digest(SOURCE),enabled=True)
    def test_override_blocks_toggle(self):
        self.install();(self.confd/'extra.toml').write_text('[main.plugins.demo]\nenabled=true\n');s=self.call()
        with self.assertRaisesRegex(ValueError,'conf.d'):
            self.call(action='toggle',name='demo',token=s['token'],previous=p.digest(SOURCE),enabled=False)
    def test_override_blocks_new_install(self):
        (self.confd/'extra.toml').write_text('[main.plugins.demo]\nenabled=true\n')
        self.assertTrue(self.review()['overridden'])
        with self.assertRaisesRegex(ValueError,'conf.d'):self.install()
        self.assertFalse((self.custom/'demo.py').exists())
    def test_invalid_files_and_names(self):
        for name in ['../escape','a/b','x.py',';touch x','']:
            with self.assertRaises(ValueError):self.review(name=name)
        with self.assertRaises(SyntaxError):self.review(b'not python !!!')
        with self.assertRaisesRegex(ValueError,'No Pwnagotchi'):self.review(b'print(1)')
        with self.assertRaisesRegex(ValueError,'256 KiB'):self.review(b'#'*(262145))
    def test_symlink_protection(self):
        self.custom.mkdir();target=self.root/'other.py';target.write_bytes(SOURCE);(self.custom/'demo.py').symlink_to(target)
        with self.assertRaisesRegex(ValueError,'symlink'):self.install()
        self.assertEqual(target.read_bytes(),SOURCE)
    def test_config_editor_cannot_interleave_install(self):
        import threading, fcntl
        started=threading.Event();done=threading.Event();observed=[];errors=[]
        def editor():
            try:
                with open(str(self.config)+'.pwnpal.lock','a') as lock:
                    started.set()
                    fcntl.flock(lock,fcntl.LOCK_EX)
                    observed.append((self.custom/'demo.py').exists())
                    current=self.config.read_bytes()
                    save(self.config,p.digest(current),base64.b64encode(p.configure(current,'demo',True)).decode(),lock_held=True)
            except Exception as e:errors.append(e)
            finally:done.set()
        thread=threading.Thread(target=editor,daemon=True)
        def hooked_save(path,expected,encoded,lock_held=False):
            result=save(path,expected,encoded,lock_held=lock_held)
            thread.start()
            self.assertTrue(started.wait(2))
            self.assertFalse(done.wait(0.1),'Editor entered before plugin transaction completed')
            return result
        r=self.review()
        try:
            p.handle(dict(action='install',name='demo',token=r['token'],previous='',sha256=r['sha256'],source=base64.b64encode(SOURCE).decode()),hooked_save,self.config,self.pkg)
        finally:
            if thread.ident:thread.join(2)
        self.assertTrue(done.is_set());self.assertEqual(errors,[]);self.assertEqual(observed,[True])
    def test_enable_disable(self):
        self.install()
        for enabled in [True,False]:
            s=self.call();self.call(action='toggle',name='demo',token=s['token'],previous=p.digest(SOURCE),enabled=enabled)
            self.assertEqual(tomllib.loads(self.config.read_text())['main']['plugins']['demo']['enabled'],enabled)
if __name__=='__main__':unittest.main()
