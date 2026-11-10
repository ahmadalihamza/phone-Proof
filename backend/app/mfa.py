"""RFC 6238 TOTP, with encrypted server-side seeds and replay prevention."""
import base64
import hashlib
import hmac
import secrets
import struct
import time
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from .config import settings

def encryption_key():
    if settings.mfa_encryption_key:
        value=bytes.fromhex(settings.mfa_encryption_key)
        if len(value)!=32:raise ValueError('MFA_ENCRYPTION_KEY must be 64 hex characters')
        return value
    if settings.app_env=='production':raise ValueError('Production admin MFA requires MFA_ENCRYPTION_KEY')
    path=Path('runtime/mfa-encryption-key')
    if not path.exists():
        try:
            with path.open('xb') as file:file.write(secrets.token_bytes(32))
            path.chmod(0o600)
        except FileExistsError:pass
    return path.read_bytes()

def encrypt_secret(secret):
    nonce=secrets.token_bytes(12)
    return base64.b64encode(nonce+AESGCM(encryption_key()).encrypt(nonce,secret.encode(),b'phoneproof-totp-v1')).decode()

def decrypt_secret(encrypted):
    value=base64.b64decode(encrypted)
    return AESGCM(encryption_key()).decrypt(value[:12],value[12:],b'phoneproof-totp-v1').decode()

def code_at(secret,step):
    raw=base64.b32decode(secret)
    digest=hmac.new(raw,struct.pack('>Q',step),hashlib.sha1).digest()
    offset=digest[-1]&15
    return f'{(struct.unpack(">I",digest[offset:offset+4])[0]&0x7fffffff)%1000000:06d}'

def validate(secret,code,last_step=-1,now=None):
    if not code or len(code)!=6 or not code.isdigit():return None
    step=int((time.time() if now is None else now)//30)
    for candidate in (step-1,step,step+1):
        if candidate>last_step and hmac.compare_digest(code_at(secret,candidate),code):return candidate
    return None
