# Changelog

## [2.0.0](https://github.com/martinfrancois/outlook-to-imap/compare/v1.0.0...v2.0.0) (2026-10-28)


### ⚠ BREAKING CHANGES

* /health now listens on 127.0.0.1, so only the same host reaches it, or in a container only the container itself. Set monitoring.healthBindAddress: 0.0.0.0 wherever something else checks it: a published port, another container on the same Docker or Compose network, a Kubernetes httpGet probe, or a monitor on another machine that checks a jar. Without it they get no answer. A Kubernetes liveness probe then restarts the container again and again, because the kubelet connects to the pod's IP. The image's own HEALTHCHECK is unaffected.
* PostgreSQL connections now need TLS with a certificate the driver can verify, unless the URL sets sslmode or ssl. Without TLS on the server, or without its CA certificate in ~/.postgresql/root.crt or sslrootcert, the service fails at start. Add sslrootcert to the URL, or sslmode=disable to connect as before. A URL that already says ssl=false keeps connecting without TLS.

### Features

* attest the released jar and image, and hash the jar with SHA3-256 ([32ba70f](https://github.com/martinfrancois/outlook-to-imap/commit/32ba70f94ba7cba8781b44f5ec2576be28dd3dd4)), closes [#27](https://github.com/martinfrancois/outlook-to-imap/issues/27)


### Bug Fixes

* bind /health to loopback by default ([ff0e109](https://github.com/martinfrancois/outlook-to-imap/commit/ff0e109fcfcd859494f5c3817b73aed92f16c9c8))
* **deps:** update flyway to v13.11.0 ([#37](https://github.com/martinfrancois/outlook-to-imap/issues/37)) ([13ed430](https://github.com/martinfrancois/outlook-to-imap/commit/13ed4303be8e2664ecb3d03a818626bf662fa5d0))
* **deps:** update flyway to v13.11.1 ([#38](https://github.com/martinfrancois/outlook-to-imap/issues/38)) ([bd8765a](https://github.com/martinfrancois/outlook-to-imap/commit/bd8765a0b6664a1c9011ff8cc53145de79e21be0))
* find an earlier upload whose Message-ID header is folded ([e61d15a](https://github.com/martinfrancois/outlook-to-imap/commit/e61d15a086d6c6083457f9ecc4f34d144724adbd))
* keep one bad message from stopping its account ([297948b](https://github.com/martinfrancois/outlook-to-imap/commit/297948b9f155b0b336f4ba5f45cb818a395b834a))
* keep one stalled client from blocking /health ([bdafe4e](https://github.com/martinfrancois/outlook-to-imap/commit/bdafe4e721c3d006a5caa12df9aa095d1c197e6e))
* keep polling after a poll dies of an Error ([fb13d03](https://github.com/martinfrancois/outlook-to-imap/commit/fb13d03b86abbce7a3e76237293271c8705c1037))
* keep the poll lease when a stop outlasts its grace ([520bcf9](https://github.com/martinfrancois/outlook-to-imap/commit/520bcf9f7edba3795bdae77ec58bf8594cd1afc1))
* keep the state directory and SQLite database private to their owner ([2b2531c](https://github.com/martinfrancois/outlook-to-imap/commit/2b2531cb5d7d82690dc59ff0defc78f544613209))
* name a source folder that is gone instead of a missing message ([8a5ad6e](https://github.com/martinfrancois/outlook-to-imap/commit/8a5ad6e5ebf87762f9f61903a65b9e8f9ba47e85)), closes [#25](https://github.com/martinfrancois/outlook-to-imap/issues/25)
* never cut a stored text inside a character ([1343d25](https://github.com/martinfrancois/outlook-to-imap/commit/1343d25c40fbaaec64bb72afea9e97ae367bd874))
* read a source folder from the start when its name finds another folder ([33233a7](https://github.com/martinfrancois/outlook-to-imap/commit/33233a79294d09f0ed0edc7e833bbd2e64a950b0)), closes [#34](https://github.com/martinfrancois/outlook-to-imap/issues/34)
* require a verified TLS connection to PostgreSQL by default ([38a66de](https://github.com/martinfrancois/outlook-to-imap/commit/38a66de4376e759b78d0675d4695e82557709075))
* say which file to delete when a foreign login cannot be removed ([db82442](https://github.com/martinfrancois/outlook-to-imap/commit/db824423ae1ebab2e5432c1efe876a0921639088))
* send the Graph access token only over https ([cbb6fc3](https://github.com/martinfrancois/outlook-to-imap/commit/cbb6fc337df961eda88d3481c45049c482dbd3bc))
* stop warning that a late lease renewal keeps the lease taken ([d7a7831](https://github.com/martinfrancois/outlook-to-imap/commit/d7a78319e2f59ee99a47248b05354560ca42f3d4))
* warn when a password file is readable by other users ([f535039](https://github.com/martinfrancois/outlook-to-imap/commit/f535039e1796f818929023c24cadf36dac6adbd6))


### Documentation

* correct and complete the decision records after review ([fc62d5e](https://github.com/martinfrancois/outlook-to-imap/commit/fc62d5e8c38aa8a3efc9cb11010870675edef00e))
* correct the comment on lease renewals that outlast a poll ([1482724](https://github.com/martinfrancois/outlook-to-imap/commit/148272457340c8533cc4de478b5545c7e316ff64))
* drop review-round fixes from the 1.0.0 changelog ([ed697b1](https://github.com/martinfrancois/outlook-to-imap/commit/ed697b19e5c49f590758113cdb37120881b6e1d7))
* point from the code to the decision records it protects ([c05d485](https://github.com/martinfrancois/outlook-to-imap/commit/c05d4854bc73037fbaabd10f9494bf22cde00381)), closes [#30](https://github.com/martinfrancois/outlook-to-imap/issues/30)
* record how Dovecot and GreenMail search a folded header ([3103f2e](https://github.com/martinfrancois/outlook-to-imap/commit/3103f2ed70301e4da07ed36ba1b69ea2b775f601))
* record how releases prove their integrity and origin ([0d159bb](https://github.com/martinfrancois/outlook-to-imap/commit/0d159bbcdfe45fba6da0d00a31a258fcc96ee74f))
* record the design decisions as MADR 4 records ([773ac85](https://github.com/martinfrancois/outlook-to-imap/commit/773ac853a1674f11bba5bc907b15a5e7306ba103))
* record the secure defaults that 2.0.0 brings ([f32d7c6](https://github.com/martinfrancois/outlook-to-imap/commit/f32d7c6adbc7f191ddb980fe02c684edf1d1e538))
* record why /health runs each request on a virtual thread ([c5e6486](https://github.com/martinfrancois/outlook-to-imap/commit/c5e648649bf1851f9baffa3ac6d5048cb0153ede))
* record why a 404 on a saved delta link rereads the folder ([e0b0e50](https://github.com/martinfrancois/outlook-to-imap/commit/e0b0e502450de666aaf6391a8868f4a5a40b7ce3))
* record why a checkpoint remembers the folder its delta link reads ([ea1fcff](https://github.com/martinfrancois/outlook-to-imap/commit/ea1fcff3fdff5c54c1496c3677583553628ad96a))
* record why a poll that dies of an Error keeps the service polling ([7107f0a](https://github.com/martinfrancois/outlook-to-imap/commit/7107f0abed6023133c9ebf307f076d7bc263b017))
* record why a stop that outlasts its grace keeps the poll lease ([a7f3822](https://github.com/martinfrancois/outlook-to-imap/commit/a7f3822015a660f31c91cb55b0cc4e24b3464271))
* run the container read-only and without capabilities ([b8a7f26](https://github.com/martinfrancois/outlook-to-imap/commit/b8a7f26b46ffd38d23e85407839d58f68d5a799f))
* say what the heartbeat sends and recommend https ([4bbe96d](https://github.com/martinfrancois/outlook-to-imap/commit/4bbe96daeb358651ab30099d0dbf0b911527c9b6))
* say who else owns the files that chown hands to uid 10001 ([7216ffc](https://github.com/martinfrancois/outlook-to-imap/commit/7216ffc90e36723ae1c7eb978f3d753b50dd2702))
* stop counting the StoreTest tests in ADR 0003 ([f65a2b4](https://github.com/martinfrancois/outlook-to-imap/commit/f65a2b4a790423f9889c419af54fdc335874897a))

## 1.0.0 (2026-10-26)


### Features

* copy mail from Microsoft accounts to IMAP folders through Graph ([3cceb04](https://github.com/martinfrancois/outlook-to-imap/commit/3cceb046215397be60bbc702b65ec849dcb80384))


### Bug Fixes

* **deps:** pin Jackson 2 and OpenTelemetry to versions without known vulnerabilities ([66041ca](https://github.com/martinfrancois/outlook-to-imap/commit/66041ca3cfa8d8fb3fbd8c804873f8b23415664d))


### Documentation

* describe setup, running, monitoring and the design decisions ([cc8408e](https://github.com/martinfrancois/outlook-to-imap/commit/cc8408ef846280d6ed87584bb8f360de71492774))
* explain Hotmail and Fastmail live setup ([e5e9a24](https://github.com/martinfrancois/outlook-to-imap/commit/e5e9a242498147f70393a4c6310cfdea73a36fc5))
* explain isolated second login and antivirus diagnostics ([54cb427](https://github.com/martinfrancois/outlook-to-imap/commit/54cb427f95a157344e8197694e08e110930ac719))
* give the exit code of a command stopped by a signal ([601b40a](https://github.com/martinfrancois/outlook-to-imap/commit/601b40a4980c3356ba412d06be202f747a1f5253))
* keep private mounts readable by the container user ([db499fa](https://github.com/martinfrancois/outlook-to-imap/commit/db499fa5340db353347b109e9188ae15dace7db8))
