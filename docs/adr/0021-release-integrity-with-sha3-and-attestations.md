---
status: accepted
date: 2026-10-26
decision-makers: [martinfrancois]
consulted: [implementing coding agent]
informed: [Future maintainers and agents working on this repository]
---

# Release integrity with a SHA3-256 checksum and artifact attestations

## Context and Problem Statement

Release 1.0.0 attached the jar and `outlook-to-imap-1.0.0.jar.sha256`, and pushed the image to GHCR
without a signature or provenance. A checksum shows that a download is complete, not who built it:
whoever can replace the jar can replace the checksum too
([outlook-to-imap issue #27](https://github.com/martinfrancois/outlook-to-imap/issues/27)).
Separately, the Maven wrapper downloaded Maven itself with only TLS protecting it, and that Maven
builds the release jar and the image. Which hash does the project publish, how does a user check
who built a release, and how is the wrapper's download checked?

## Decision Drivers

* A user must be able to check that a jar or an image came from this repository's release
  workflow, with a tool they can install in a minute.
* No signing key that someone has to keep secret, rotate or lose.
* Where the project picks a hash itself, it uses SHA3, the owner's standing preference, as
  [ADR 0014](0014-verify-each-upload-and-settle-unknown-ones-by-content.md) does for message
  content.
* Generated files that Renovate updates, such as `mvnw`, stay as their tool writes them.

## Considered Options

* SHA3-256 checksum, artifact attestations, and SHA-256 in the Maven wrapper
* SHA-256 checksum and artifact attestations
* SHA3-256 checksum and cosign signatures
* SHA3-256 everywhere, with a patched Maven wrapper
* A checksum alone

## Decision Outcome

Chosen option: "SHA3-256 checksum, artifact attestations, and SHA-256 in the Maven wrapper",
because attestations prove the origin without a key, the checksum uses SHA3 where the project
chooses, and the wrapper keeps the only hash it supports.

* `.github/workflows/release-please.yml` writes `outlook-to-imap-<version>.jar.sha3-256` with
  `openssl dgst -sha3-256 -r`, which prints the hash and the file name like `sha256sum` does.
* The same job runs `actions/attest` v4.2.2, pinned by commit, once for the jar
  and once for the pushed image digest with `push-to-registry: true`. GitHub signs the attestations
  with the workflow's OIDC identity through Sigstore and stores them; the image's is also pushed to
  GHCR. The job has `id-token: write` and `attestations: write`, and already had `packages: write`.
  `create-storage-record` is off, because GitHub creates storage records only for repositories owned
  by an organization.
* The README shows `gh attestation verify` for the jar and for
  `oci://ghcr.io/martinfrancois/outlook-to-imap:<version>`, and how to compare the SHA3-256 file.
* `.mvn/wrapper/maven-wrapper.properties` holds `distributionSha256Sum`, the SHA-256 of the Maven
  zip. The owner chose on 2026-10-26 to keep SHA-256 there.
* The `Dockerfile` build stage installs `unzip`. Without it the wrapper downloads Maven's `.tar.gz`
  instead of the zip, and the SHA-256 check fails.

### Consequences

* Good, because `gh attestation verify` fails for any jar or image that this repository's workflow
  did not build, and needs no key on either side.
* Good, because the checksum file follows the project's hash choice.
* Bad, because `sha256sum -c` no longer checks the published file. Users need OpenSSL 1.1.1 or
  newer, or another SHA3 tool.
* Bad, because the wrapper's check uses SHA-256 while the rest of the project uses SHA3.
* Neutral, because 1.0.0 keeps its `.sha256` file and has no attestation.

### Confirmation

`.github/workflows/release-please.yml` has the steps "Attest the jar" and "Attest the image", and
the checksum line uses `openssl dgst -sha3-256`. After a release,
`gh attestation verify outlook-to-imap-<version>.jar -R martinfrancois/outlook-to-imap` and the
`oci://` variant succeed. `.mvn/wrapper/maven-wrapper.properties` has `distributionSha256Sum`, and
a wrong value makes `./mvnw` stop with "Failed to validate Maven distribution SHA-256".

## Pros and Cons of the Options

### SHA3-256 checksum, artifact attestations, and SHA-256 in the Maven wrapper

The release publishes a SHA3-256 file and GitHub attestations; the wrapper checks Maven with the
SHA-256 it supports.

* Good, because origin and integrity are both checkable, without a key.
* Good, because each hash is the strongest choice the tool in question allows.
* Bad, because the project uses two hash families.

### SHA-256 checksum and artifact attestations

Keep `sha256sum` for the release file and add attestations.

* Good, because `sha256sum -c` works on every Linux system.
* Bad, because it goes against the owner's preference for SHA3 where the project picks the hash.

### SHA3-256 checksum and cosign signatures

Sign the image digest with keyless `cosign sign`, and the jar with `cosign sign-blob`.

* Good, because cosign signatures are a common format that registries and admission controllers
  check.
* Bad, because users must install cosign and give the certificate identity and issuer by hand,
  where `gh attestation verify -R` needs only the repository name.
* Bad, because a key-based variant would need a secret key in the repository settings, kept and
  rotated.

### SHA3-256 everywhere, with a patched Maven wrapper

Change `mvnw` and `mvnw.cmd` to check a SHA3-256 of the Maven zip.

* Good, because the project would use one hash family.
* Bad, because Maven Wrapper 3.3.4 supports only SHA-256: the property is `distributionSha256Sum`,
  and the scripts call `sha256sum` or `shasum -a 256`.
* Bad, because Renovate's wrapper updates regenerate `mvnw` and `mvnw.cmd` and would undo the patch.
* Bad, because Apache publishes no SHA3 file to compare with. For apache-maven-3.10.0-bin.zip,
  repo.maven.apache.org has `.sha512`, `.sha1` and `.md5`, and downloads.apache.org has `.sha512`.

### A checksum alone

Keep only a checksum file next to the jar.

* Good, because nothing new is needed.
* Bad, because it proves nothing about who built the jar, which is what
  [outlook-to-imap issue #27](https://github.com/martinfrancois/outlook-to-imap/issues/27) asks
  for.

## More Information

The wrapper's SHA-256 was computed from the zip on repo.maven.apache.org after its SHA-512 matched
the one Apache publishes on downloads.apache.org. Renovate's maven-wrapper manager rewrites
`distributionSha256Sum` together with `distributionUrl` when the property is present. The temurin
JDK image has no `unzip`, and without it the wrapper downloads Maven's `.tar.gz` instead of the zip
and fails the check, so the image's build stage installs `unzip`.

The workflow uses `actions/attest` rather than `actions/attest-build-provenance`. Since version 4
the latter is only a wrapper around `actions/attest`, and its README says new setups should use
`actions/attest`. Both create a SLSA build provenance attestation by default.
