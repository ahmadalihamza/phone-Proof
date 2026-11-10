# Current limitations and release gates

Status: development release candidate. Automated fixtures exercise backend behavior; they do not establish that the complete two-phone flow works on physical hardware. See testing.md for the actual executed checks.

## Physical Android acceptance

A TECNO CK6n running Android 14 was connected on 19 October. Actual camera JPEGs, microphone routing/capture, accelerometer events, private-storage/SQLite probes and microphone lifecycle cancellation were observed; see testing.md for the final device test result. These observations are not optical/acoustic certification or an OEM matrix. A second phone, full scanner/finalization journey, process death, WorkManager reconnect, external audio-route transitions and CameraX lens behavior across devices remain acceptance work. Test Android 10 through 16 across OEM families and a low-memory device, including font scaling, gesture navigation and low-light capture.

Counts include 265 unscored metadata/observation templates and 61 scored functional templates. A public API field demonstrates reported metadata, not functional performance. Platform access restrictions are recorded as inaccessible. Cameras hidden by the OEM are unavailable. There is no privileged access, root bypass, modem calibration, battery capacity certification, genuine-part detection or hidden damage detection. Touch/display surfaces do not inspect pixels beneath system overlays. Sensor variation may be inconclusive when the device remains stationary.

## Scanner and AI

Real image decoding, resolution, sharpness/exposure indicators and near-duplicate checks run on uploaded evidence. These heuristics require device/camera calibration and can reject textureless valid images or accept sharp irrelevant images. The inspector must confirm phone framing, view angles and all twelve photographs. Manual cosmetic scores are rule-based human assessments, not laboratory measurements.

No trained PhoneProof weights or validation dataset were supplied. The model worker is an optional integration boundary; it has not run a real inference here. Segmentation, phone rectification and multi-view defect reconciliation are not implemented. Confidence calibration, versioned validation and model-serving load tests remain necessary. No generic pretrained detections are presented as real cosmetic results.

Captured files/queue entries survive restart. Annotation drafts, reviewed-image IDs and the selected view are saved in app-private preferences, isolated by backend/account/inspection. Physical-device restart validation remains pending; abrupt power loss can interrupt a pending preference write. Refreshing images drops annotations tied to replaced photos and resets framing confirmation when the image set changes. The server also rejects a review that does not match all current image IDs. Old object versions need the deployment's documented retention/lifecycle policy.

## Commercial and account scope

The development provider is explicitly simulated and disabled by default; production rejects it and release Android hides its controls. Immutable checkout terms, attempt/event records, independent-verification orchestration, callback deduplication, a reconciliation worker, private receipt PDFs and targeted idempotent refunds are implemented and exercised with explicit test adapters. No genuine merchant adapter, production webhook authentication, real payment/refund settlement or merchant certification is implemented. Next-plan scheduling can be completed through an explicit manual renewal after expiry; automatic recurring charges, grace periods, proration and tax-compliant invoicing remain unfinished. Promotions currently grant one report; percentage discounts, credit wallets and stacking are not implemented. Development prices are examples. See payment-readiness.md for exact activation gates and historical purchase handling.

Email verification and password recovery have backend and Android flows, hashed single-use codes, session revocation and encrypted SMTP outbox delivery. They require configured SMTP and a separate mail worker; real inbox delivery remains pending. Refresh reuse and billing/report concurrency were exercised against PostgreSQL 16. Customer MFA enrollment/recovery, staff invitations, dealer organizations, branch hierarchies and full enterprise billing are not implemented. Admin MFA is provisioned through the CLI and remains required for administrator password reset. Offline inspections bind to the signed-in account/backend on first sync; changing an account or backend does not migrate them.

SMTP delivery is at least once: a worker crash after SMTP acceptance can produce a duplicate email, while the code remains single use. Resend/reset invalidates obsolete codes and clears queued ciphertext; an email already sent cannot be recalled. A configured endpoint indicates service configuration, not guaranteed inbox arrival.

## Trust, hosting and operations

Reports attest to the server's immutable record and declared observation methods. A modified client can submit dishonest evidence; Play Integrity/device attestation is not integrated. Device time is checked for reasonable bounds but is not a secure hardware timestamp. IMEI is manually supplied/OCR assisted and cannot establish device ownership or authenticity. PTA is inspector-confirmed; no official lookup integration exists.

New inspections now use shared category-weighted scoring v2, with explicit caps, coverage, lost points and a conservative overall grade. Existing inspections retain v1; signed reports are not rewritten. These implemented rules still require product/field calibration. Policy versions are bundled configuration; an admin editor and approval/promotion workflow for new rules remain future work. The complete SRS inspection lifecycle and expiry enforcement also remain unfinished.

Connectivity probes currently retain zero scoring weight pending field validation. Their observations remain visible, but the connectivity share does not contribute until a versioned, scored functional definition is enabled. Empty or unsupported categories are excluded transparently; unmeasured applicable categories reduce coverage. A configured category share is not evidence that every category has been functionally certified.

Public verification intentionally removes private measured values, evidence and free-text messages. Its JSON is a privacy projection and cannot independently reproduce the full signed hash. The authenticated owner report contains the canonical payload. Signing-key rotation/trusted-key publication and hardware-backed signing need an operational design before relying on external/offline verifiers.

Local PostgreSQL 16 concurrency/migration tests and real encrypted private MinIO round trips passed. A portable service helper also rehearses synthetic PostgreSQL backup/restore; the result is recorded in the release gate record. These do not certify hosted PostgreSQL/PITR, Redis, the selected deployment bucket, merchant services, container startup, load, security assessment or alert delivery. Docker configuration is supplied but the host Docker daemon was unavailable. The proposed Render Blueprint is schema-validated and remains undeployed. No authorized hosting/merchant account or release keystore was supplied, so no public deployment or Play release was performed.

Retention/deletion/export requests, data migration policies and audit retention need a documented operational process consistent with append-only evidence. Review this repository's security and privacy notes before collecting customer data. Production readiness depends on resolving these concrete gaps, not on an APK compiling successfully.
