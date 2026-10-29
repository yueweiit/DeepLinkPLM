from __future__ import annotations

import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

from phone_watch import db
from phone_watch.webapp import PhoneWatchHandler


class WebappDingTalkTipoStatusTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmpdir.name) / "phones.sqlite"
        db.init_db(self.db_path)
        PhoneWatchHandler.db_path = str(self.db_path)
        with db.connect(self.db_path) as conn:
            db.upsert_candidate(
                conn,
                {
                    "brand": "MOTOROLA",
                    "source_country": "CN",
                    "missing_country": "MX",
                    "model": "Moto DingTalk Done Test",
                    "normalized_model": "motodingtalkdonetest",
                    "source_url": "https://example.com/dingtalk-done-test",
                    "source_name": "Manual",
                    "reason": "test",
                    "first_seen_at": "2026-07-10T00:00:00+00:00",
                    "last_seen_at": "2026-07-10T00:00:00+00:00",
                    "sampling_status": db.DEFAULT_STATUS,
                },
            )
            row = conn.execute(
                "SELECT id FROM candidates WHERE normalized_model = ?",
                ("motodingtalkdonetest",),
            ).fetchone()
            self.candidate_id = int(row["id"])

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), PhoneWatchHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.tmpdir.cleanup()

    def post_json(self, path: str, payload: dict) -> tuple[int, dict]:
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            return exc.code, json.loads(exc.read().decode("utf-8"))

    def test_retired_single_and_bulk_leave_historical_status_unchanged(self) -> None:
        for path in ["/api/dingtalk/sync-one", "/api/dingtalk/sync-selected"]:
            with self.subTest(path=path), patch("phone_watch.webapp.db.connect") as connect:
                status, body = self.post_json(path, {"type": "candidates", "id": self.candidate_id,
                    "items": [{"type": "candidates", "id": self.candidate_id}], "tipo_values": ["Tipo Alpha"]})
                self.assertEqual(status, 410)
                self.assertFalse(body["ok"])
                self.assertIn("PLM", body["error"])
                connect.assert_not_called()
        with db.connect(self.db_path) as conn:
            self.assertEqual(db.molding_tipos_for_model(conn, "MOTOROLA", "motodingtalkdonetest"), [])
            row = conn.execute("SELECT sampling_status FROM candidates WHERE id = ?", (self.candidate_id,)).fetchone()
            self.assertEqual(row["sampling_status"], db.DEFAULT_STATUS)

    def test_retired_connector_reports_disabled_and_rejects_submit(self) -> None:
        from phone_watch.dingtalk_http import DingTalkSyncHandler
        server = ThreadingHTTPServer(("127.0.0.1", 0), DingTalkSyncHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        previous = self.base_url
        self.base_url = f"http://127.0.0.1:{server.server_address[1]}"
        try:
            with urllib.request.urlopen(self.base_url + "/health", timeout=5) as response:
                self.assertFalse(json.load(response)["enabled"])
            status, body = self.post_json("/dingtalk/sync", {"run_sync": True})
            self.assertEqual(status, 410)
            self.assertIn("PLM", body["error"])
        finally:
            self.base_url = previous
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
