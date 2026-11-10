# Privacy and retention

PhoneProof requests no contacts, SMS, call logs, installed-app inventory, gallery or documents permissions. Camera/microphone/location/Bluetooth permissions are requested at the diagnostic that needs them. Denial produces an explained inaccessible result.

Raw microphone samples are processed in memory and released. GPS tests retain only fix age, acquisition time and accuracy; no coordinates. Wi-Fi network names, BSSIDs, Bluetooth device addresses and phone numbers are not retained. Scanner capture is in-app. Server re-encoding strips EXIF/GPS. IMEI is validated, reduced to a last-four suffix and HMAC; the full value is not stored. PTA status is explicitly an inspector confirmation unless a future official integration exists.

Owner-only diagnostic evidence is stored in Room and the authenticated backend. Public verification excludes arbitrary measurements, evidence, user notes, email, inspector identity, full IMEI, raw photographs and precise location. It includes model, date, masked suffix, statuses, methods, scores and signature information. Photos require authenticated ownership.

Scanner files remain private until server acknowledgement, then the queue deletes the local capture. Rejected images remain available locally for recovery/recapture. Raw diagnostic attempts remain in Room and server audit history. Before release, publish a jurisdiction-reviewed privacy policy and implement explicit retention/account-deletion workflows consistent with signed-report/audit obligations. Retention is not silently represented as indefinite consent.
