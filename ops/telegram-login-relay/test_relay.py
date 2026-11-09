import tempfile
from pathlib import Path
import unittest
from relay import Relay


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.state = Path(self.directory.name) / 'offset'
        self.calls = []

    def transport(self, url, body):
        self.calls.append((url, body))
        return {'ok': True, 'result': True}

    def update(self, **message):
        return {'update_id': 4, 'message': {'text': '/start auth_' + 'a' * 32,
            'from': {'id': 123}, 'chat': {'id': 123, 'type': 'private'}, **message}}

    def test_backend_failure_is_not_acknowledged(self):
        def unavailable(url, body):
            raise ConnectionError()
        relay = Relay('test', 'https://backend/login', self.state, unavailable)
        with self.assertRaises(ConnectionError):
            relay.process(self.update())
        self.assertFalse(self.state.exists())
        self.assertIsNone(relay.offset)

    def test_delivery_persists_offset_and_duplicate_cannot_repeat_login_or_reply(self):
        relay = Relay('test', 'https://backend/login', self.state, self.transport)
        relay.process(self.update())
        restarted = Relay('test', 'https://backend/login', self.state, self.transport)
        restarted.process(self.update())
        self.assertEqual('5', self.state.read_text())
        self.assertEqual(2, len(self.calls))
        self.assertTrue(self.calls[1][0].endswith('/sendMessage'))

    def test_group_messages_are_forwarded_but_never_answered(self):
        relay = Relay('test', 'https://backend/login', self.state, self.transport)
        relay.process(self.update(chat={'id': -1, 'type': 'group'}))
        self.assertEqual(1, len(self.calls))

    def test_reply_failure_does_not_replay_login(self):
        def no_reply(url, body):
            self.calls.append(url)
            if url.endswith('/sendMessage'):
                raise ConnectionError()
            return {}
        relay = Relay('test', 'https://backend/login', self.state, no_reply)
        relay.process(self.update())
        relay.process(self.update())
        self.assertEqual(2, len(self.calls))
        self.assertEqual('5', self.state.read_text())


if __name__ == '__main__':
    unittest.main()
