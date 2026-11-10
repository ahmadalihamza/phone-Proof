"""Create an Ed25519 key only at an explicitly supplied local path; never overwrite."""
import argparse
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization
parser=argparse.ArgumentParser()
parser.add_argument('path')
args=parser.parse_args()
path=Path(args.path)
path.parent.mkdir(parents=True,exist_ok=True)
key=Ed25519PrivateKey.generate()
with path.open('xb') as file:
    file.write(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
path.chmod(0o600)
print('Signing key written. Keep it private and back it up securely.')
