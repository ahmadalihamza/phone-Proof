# External inputs and activation

The proposed hosting path is Render with managed PostgreSQL/Key Value and private AWS S3. The reviewed template and activation steps are in deployment.md. Required to activate it: authorized host/project, owned HTTPS domains, private bucket and credentials, durable signing-key storage, secret manager and monitoring destination. Generate JWT/MFA/report keys locally or in the secret manager; do not send private keys in chat. These service accounts cannot be inferred from source code.

Required for Play distribution: chosen application ID (default is pk.phoneproof.app), release keystore, Play Console access, store listing, privacy policy, terms, support/retention process and device matrix results. Play Integrity additionally needs Google Cloud/Play project configuration and a tested server token-verification policy.

Cosmetic defect detection requires trained PhoneProof weights and a validation manifest/dataset for the twelve supported classes. Until supplied, only real image QC and manual human review are active. Generic YOLO weights are rejected by the class/manifest gate. GPU hosting is optional until a validated model's resource needs are known.

Real payments require merchant-approved Easypaisa/JazzCash/card API credentials, official sandbox documentation, webhook verification keys, settlement/refund/reconciliation tests and business pricing. No real provider adapter is currently implemented. The explicit development provider is disabled by default and impossible to activate in production. PTA integration needs an official usable API and authorization; no scraping is implemented.

Email verification/password recovery require an SMTP endpoint, sender address and any required credentials, plus the supervised mail worker. Configure these through environment/secret management. Both API and worker need the same stable MFA_ENCRYPTION_KEY to decrypt queued mail and administrator seeds. Real delivery needs controlled staging-inbox acceptance; automated tests use mocked SMTP.

No external credential is required to compile Android, run local diagnostics, test the local backend, capture photos, manually review cosmetics, or create trial reports in an isolated development environment.
