# Runs as a shaded jar and as a container image

The original plan was container only. The service also ships as one runnable jar on every GitHub
Release, so it also runs on a machine with Java but no container runtime. The jar costs
nothing extra: the image is that same jar on a JRE.

Both are tested. CI runs the test suite on every Java LTS from 21 up, then builds the image and
smoke-tests it (`scripts/smoke-image.sh`). Configuration is the same file either way; the only
difference is the default state directory, `data` next to the config for the jar and `/data` in the
image, set by `OUTLOOK_TO_IMAP_STATE_DIRECTORY`.
