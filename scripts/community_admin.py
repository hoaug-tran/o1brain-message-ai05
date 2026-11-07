import argparse
from pathlib import Path

from ai05.infrastructure.community import CommunityStore


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Local operator access; never expose over HTTP"
    )
    parser.add_argument("--data", type=Path, default=Path("private/community"))
    sub = parser.add_subparsers(dest="command", required=True)
    revoke = sub.add_parser("revoke")
    revoke.add_argument("device")
    args = parser.parse_args()
    store = CommunityStore(args.data)
    store.revoke(args.device)


if __name__ == "__main__":
    main()
