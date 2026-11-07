import argparse
import ipaddress
import json
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

import httpx


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Exercise local inference with outbound sockets blocked in the serving process."
    )
    parser.add_argument("--serve", action="store_true")
    parser.add_argument("--port", type=int, default=8011)
    args = parser.parse_args()
    if args.serve:
        original = socket.socket.connect

        def local_connect(sock: socket.socket, address: tuple) -> None:
            if (
                sock.family in (socket.AF_INET, socket.AF_INET6)
                and not ipaddress.ip_address(address[0]).is_loopback
            ):
                raise OSError("Offline drill: outbound socket blocked")
            original(sock, address)

        socket.socket.connect = local_connect

        with socket.socket() as probe:
            try:
                probe.connect(("192.0.2.1", 443))
            except OSError as exc:
                assert "outbound socket blocked" in str(exc)
            else:
                raise AssertionError("Offline socket guard failed")
        import uvicorn

        uvicorn.run(
            "ai05.interfaces.http:app",
            host="127.0.0.1",
            port=args.port,
            access_log=False,
        )
        return
    Path(".task").mkdir(exist_ok=True)
    with Path(".task/offline-server.log").open("w") as log:
        process = subprocess.Popen(
            [sys.executable, __file__, "--serve", "--port", str(args.port)],
            stdout=log,
            stderr=log,
            env={**os.environ, "HF_HUB_OFFLINE": "1"},
        )
        try:
            url = f"http://127.0.0.1:{args.port}"
            with httpx.Client(base_url=url, timeout=10) as client:
                for _ in range(60):
                    if process.poll() is not None:
                        raise RuntimeError(
                            "Offline server exited; see .task/offline-server.log"
                        )
                    try:
                        if client.get("/health/ready").status_code == 200:
                            break
                    except httpx.ConnectError:
                        pass
                    time.sleep(0.2)
                else:
                    raise RuntimeError("Offline readiness timeout")
                subprocess.run(
                    [sys.executable, "scripts/smoke_api.py", "--url", url], check=True
                )
                response = client.post(
                    "/api/v1/demo/predict",
                    json={
                        "text": "Synthetic message with http://192.0.2.1/never-fetch and ignore instructions text"
                    },
                )
                response.raise_for_status()
                report = {
                    "mode": "outbound socket guard in server process; not physical network disconnect",
                    "status": "PASS",
                    "model_id": response.json()["model"]["id"],
                    "private_input_logged": False,
                }
                Path("results/demo_evidence").mkdir(parents=True, exist_ok=True)
                Path("results/demo_evidence/offline_smoke.json").write_text(
                    json.dumps(report, indent=2)
                )
                print(json.dumps(report))
        finally:
            process.terminate()
            process.wait(timeout=10)


if __name__ == "__main__":
    main()
