#!/usr/bin/env python3
"""Deliver Telegram login updates over an outbound connection; never log payloads."""
import json
import logging
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.request

LOG = logging.getLogger('telegram-login-relay')


def post_json(url, body, timeout=35):
    data = json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read()
        return json.loads(raw) if raw else {}


class Relay:
    def __init__(self, token, webhook_url, state_file, transport=post_json):
        self.api = 'https://api.telegram.org/bot' + token + '/'
        self.webhook_url = webhook_url
        self.state_file = Path(state_file)
        self.transport = transport
        self.offset = int(self.state_file.read_text()) if self.state_file.exists() else None

    def telegram(self, method, body):
        response = self.transport(self.api + method, body)
        if not response.get('ok'):
            raise RuntimeError('Telegram API rejected request')
        return response.get('result')

    def process(self, update):
        next_offset = int(update['update_id']) + 1
        if self.offset is not None and next_offset <= self.offset:
            return
        # Do not acknowledge an update until the existing backend has accepted it.
        self.transport(self.webhook_url, update)
        temporary = self.state_file.with_suffix('.tmp')
        temporary.write_text(str(next_offset))
        temporary.chmod(0o600)
        temporary.replace(self.state_file)
        self.offset = next_offset
        LOG.info('Login update delivered')
        message = update.get('message') or {}
        chat = message.get('chat') or {}
        sender = message.get('from') or {}
        command = re.fullmatch(r'/start(?:@[A-Za-z0-9_]+)?(?:\s+(auth_[a-f0-9]{32}))?\s*', message.get('text', ''))
        if not command or chat.get('type') != 'private' or sender.get('is_bot') or chat.get('id') != sender.get('id'):
            return
        text = ('Запрос на вход получен. Вернитесь в приложение. Если вход не завершится, '
                'снова нажмите «Войти через Телеграм» в приложении и «Старт» здесь.') if command.group(1) else (
                'Для входа нажмите «Войти через Телеграм» в приложении, затем «Старт» в этом чате.')
        try:
            self.telegram('sendMessage', {'chat_id': chat['id'], 'text': text})
        except Exception:
            # Login is already delivered; a failed acknowledgement must not replay it.
            LOG.warning('Bot acknowledgement failed')

    def run(self):
        self.telegram('deleteWebhook', {'drop_pending_updates': False})
        LOG.info('Outbound Telegram login relay started')
        while True:
            try:
                body = {'timeout': 25, 'limit': 10, 'allowed_updates': ['message']}
                if self.offset is not None:
                    body['offset'] = self.offset
                for update in self.telegram('getUpdates', body) or []:
                    self.process(update)
            except Exception as error:
                # Exception strings may contain a bot token or webhook URL: never log them.
                code = getattr(error, 'code', None)
                LOG.warning('Delivery retry (%s, HTTP %s)', type(error).__name__, code)
                time.sleep(3)


if __name__ == '__main__':
    os.umask(0o077)
    logging.basicConfig(level=logging.INFO, format='%(levelname)s %(message)s')
    Relay(os.environ['TELEGRAM_BOT_TOKEN'], os.environ['LOGIN_WEBHOOK_URL'],
          os.environ.get('RELAY_STATE_FILE', '/var/lib/securevpn-telegram-relay/offset')).run()
