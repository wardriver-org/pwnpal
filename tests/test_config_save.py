import base64, hashlib, importlib.util, pathlib, tempfile, unittest
source = pathlib.Path(__file__).parents[1] / 'app/src/main/assets/config_save.py'
spec=importlib.util.spec_from_file_location('config_save', source)
module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class ConfigTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.path=pathlib.Path(self.temp.name)/'config.toml'
        self.original=b'[main]\nname="before"\n\n[ui.web]\nauth=true\n'
        self.path.write_bytes(self.original);self.path.chmod(0o640)
        self.hash=hashlib.sha256(self.original).hexdigest()
    def save(self,data,hash=None):
        return module.save_config(self.path,hash or self.hash,base64.b64encode(data).decode())
    def test_valid_save_preserves_backup_and_permissions(self):
        data=b'[main]\nname="after"\n[ui.web]\nauth=true\n'
        result=self.save(data)
        self.assertEqual(self.path.read_bytes(),data)
        self.assertEqual(pathlib.Path(result['backup']).read_bytes(),self.original)
        self.assertEqual(self.path.stat().st_mode & 0o777,0o640)
        self.assertEqual(pathlib.Path(result['backup']).stat().st_mode & 0o777,0o600)
        self.assertEqual(result['hash'],hashlib.sha256(data).hexdigest())
    def test_invalid_toml_leaves_original(self):
        with self.assertRaises(Exception):self.save(b'[main\nname=oops')
        self.assertEqual(self.path.read_bytes(),self.original)
    def test_conflict_leaves_external_edit(self):
        external=b'[main]\nname="elsewhere"\n';self.path.write_bytes(external)
        with self.assertRaisesRegex(ValueError,'changed'):self.save(b'[main]\nname="new"')
        self.assertEqual(self.path.read_bytes(),external)
    def test_empty_rejected(self):
        with self.assertRaisesRegex(ValueError,'empty'):self.save(b'')
    def test_symlink_rejected(self):
        target=self.path.with_suffix('.real');self.path.rename(target);self.path.symlink_to(target)
        with self.assertRaisesRegex(ValueError,'symlink'):self.save(b'[main]\nname="new"')
        self.assertEqual(target.read_bytes(),self.original)
    def test_large_file_rejected(self):
        with self.assertRaisesRegex(ValueError,'128 KiB'):self.save(b'#' * 131073)
if __name__=='__main__': unittest.main()
