from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field, field_validator, model_validator
from .healthcheck import select_probe_host


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file='.env', extra='ignore')
    app_env: str = 'development'
    database_url: str = 'sqlite:///./runtime/phoneproof.db'
    jwt_secret: str = ''
    public_report_base_url: str = 'http://localhost:8000'
    signing_key_path: str = './runtime/report-signing.pem'
    signing_key_version: str = 'v1'
    media_dir: str = './runtime/media'
    redis_url: str = ''
    redis_max_connections: int = Field(default=50,ge=1,le=1000)
    readiness_timeout_seconds: float = Field(default=5,ge=0.1,le=30)
    healthcheck_host: str = ''
    s3_bucket: str = ''
    s3_endpoint: str = ''
    s3_region: str = 'auto'
    s3_access_key: str = ''
    s3_secret_key: str = ''
    model_manifest: str = ''
    trial_enabled: bool = True
    trial_report_limit: int = 1
    trial_duration_days: int = 14
    development_payments: bool = False
    require_cosmetic: bool = True
    allowed_hosts: str = 'localhost,127.0.0.1,testserver,10.0.2.2'
    mfa_encryption_key: str = ''
    smtp_host: str = ''
    smtp_port: int = 587
    smtp_username: str = ''
    smtp_password: str = ''
    smtp_from: str = ''
    smtp_implicit_tls: bool = False

    @field_validator('database_url',mode='before')
    @classmethod
    def normalize_postgres_driver(cls,value):
        # Managed hosts commonly supply native PostgreSQL URLs. This project
        # installs psycopg 3; retain every byte after the scheme (including
        # percent-encoded credentials and connection options).
        if isinstance(value,str):
            for native in ('postgres://','postgresql://'):
                if value.startswith(native):
                    return 'postgresql+psycopg://'+value[len(native):]
        return value

    @model_validator(mode='after')
    def secure_production(self):
        if self.app_env == 'production':
            hosts=[value.strip() for value in self.allowed_hosts.split(',') if value.strip()]
            if not hosts or '*' in hosts:
                raise ValueError('Production requires specific ALLOWED_HOSTS; a blanket wildcard is prohibited')
            select_probe_host(self.allowed_hosts,self.public_report_base_url,self.healthcheck_host)
            if len(self.jwt_secret) < 48 or not self.database_url.startswith('postgresql'):
                raise ValueError('Production requires PostgreSQL and a 48+ character JWT_SECRET')
            if not self.public_report_base_url.startswith('https://') or not self.redis_url:
                raise ValueError('Production requires HTTPS PUBLIC_REPORT_BASE_URL and REDIS_URL')
            if self.development_payments or not Path(self.signing_key_path).is_file():
                raise ValueError('Production requires provisioned signing key; development payments prohibited')
            if not self.s3_bucket:
                raise ValueError('Production requires private S3-compatible object storage')
            try:
                mfa_key = bytes.fromhex(self.mfa_encryption_key)
            except ValueError:
                raise ValueError('Production MFA_ENCRYPTION_KEY must be 64 hex characters') from None
            if len(mfa_key) != 32:
                raise ValueError('Production requires a 64-hex-character MFA_ENCRYPTION_KEY')
        return self


settings = Settings()
Path('runtime').mkdir(exist_ok=True)
if not settings.jwt_secret:
    import secrets
    secret_file = Path('runtime/dev-jwt-secret')
    if not secret_file.exists():
        secret_file.write_text(secrets.token_urlsafe(64))
    settings.jwt_secret = secret_file.read_text().strip()
