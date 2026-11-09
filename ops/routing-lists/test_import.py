import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('importer', Path(__file__).with_name('import.py'))
importer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(importer)

class ImportTest(unittest.TestCase):
    def test_filters_russian_zones_shared_dns_and_invalid_values(self):
        values, excluded = importer.normalize('example.ru\nsub.example.su\nпример.рф\nhabr.com\ncloudflare-dns.com\nhttps://bad.test\n*.bad.test\ncom\ngood.example\n'.encode())
        self.assertEqual(['good.example'], values)
        self.assertEqual(8, len(excluded))

    def test_suffix_compaction_does_not_widen_parent(self):
        values, _ = importer.normalize(b'example.com\nsub.example.com\nexample.com\na.example.net\n')
        self.assertEqual(['a.example.net', 'example.com'], values)

    def test_checked_in_snapshot_is_reproducible(self):
        root = importer.ROOT
        raw = (root / 'backend/src/main/resources/routing/upstream-community.txt').read_bytes()
        domains, _ = importer.normalize(raw)
        actual = (root / 'backend/src/main/resources/db/migration/V15__mandatory_services_community_domains.sql').read_text()
        self.assertEqual(importer.render(domains, importer.REVISION), actual)
        self.assertGreater(len(domains), 100)
        self.assertLess(len(domains), 2000)

if __name__ == '__main__':
    unittest.main()
